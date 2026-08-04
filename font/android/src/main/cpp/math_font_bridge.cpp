#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <harfbuzz/hb-ot.h>
#include <harfbuzz/hb.h>

#include <ft2build.h>
#include FT_BBOX_H
#include FT_FREETYPE_H
#include FT_OUTLINE_H

namespace {

constexpr int kRunHeaderSize = 5;
constexpr int kRunGlyphStride = 9;

enum PathVerb : int {
    kMove = 0,
    kLine = 1,
    kQuad = 2,
    kCubic = 3,
    kClose = 4,
};

struct NativeFace {
    std::vector<std::uint8_t> bytes;
    FT_Library ft_library = nullptr;
    FT_Face ft_face = nullptr;
    hb_blob_t* hb_blob = nullptr;
    hb_face_t* hb_face = nullptr;
    hb_font_t* hb_font = nullptr;
    std::mutex mutex;

    ~NativeFace() {
        if (hb_font != nullptr) hb_font_destroy(hb_font);
        if (hb_face != nullptr) hb_face_destroy(hb_face);
        if (hb_blob != nullptr) hb_blob_destroy(hb_blob);
        if (ft_face != nullptr) FT_Done_Face(ft_face);
        if (ft_library != nullptr) FT_Done_FreeType(ft_library);
    }
};

struct GlyphBounds {
    float left = 0.0f;
    float top = 0.0f;
    float right = 0.0f;
    float bottom = 0.0f;
    bool has_outline = false;
};

void throw_illegal_state(JNIEnv* env, const std::string& message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message.c_str());
}

NativeFace* from_handle(JNIEnv* env, jlong handle) {
    if (handle == 0) {
        throw_illegal_state(env, "Android math font face is closed");
        return nullptr;
    }
    return reinterpret_cast<NativeFace*>(static_cast<std::uintptr_t>(handle));
}

bool load_glyph_bounds(NativeFace* face, hb_codepoint_t glyph_id, float font_size, GlyphBounds* out) {
    const FT_Int32 flags = FT_LOAD_NO_SCALE | FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP;
    if (FT_Load_Glyph(face->ft_face, glyph_id, flags) != 0) return false;
    const float scale = font_size / static_cast<float>(face->ft_face->units_per_EM);
    FT_GlyphSlot slot = face->ft_face->glyph;
    if (slot->format == FT_GLYPH_FORMAT_OUTLINE && slot->outline.n_points > 0) {
        FT_BBox bounds;
        if (FT_Outline_Get_BBox(&slot->outline, &bounds) != 0) return false;
        out->left = static_cast<float>(bounds.xMin) * scale;
        out->top = -static_cast<float>(bounds.yMax) * scale;
        out->right = static_cast<float>(bounds.xMax) * scale;
        out->bottom = -static_cast<float>(bounds.yMin) * scale;
        out->has_outline = true;
    } else {
        const FT_Glyph_Metrics& metrics = slot->metrics;
        out->left = static_cast<float>(metrics.horiBearingX) * scale;
        out->top = -static_cast<float>(metrics.horiBearingY) * scale;
        out->right = static_cast<float>(metrics.horiBearingX + metrics.width) * scale;
        out->bottom = static_cast<float>(metrics.height - metrics.horiBearingY) * scale;
        out->has_outline = false;
    }
    return true;
}

jfloatArray to_float_array(JNIEnv* env, const std::vector<float>& values) {
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

struct OutlineCollector {
    std::vector<float> commands;
    float scale = 1.0f;
    bool contour_open = false;

    void close_contour() {
        if (!contour_open) return;
        commands.push_back(static_cast<float>(kClose));
        contour_open = false;
    }
};

float path_x(const FT_Vector* value, const OutlineCollector* collector) {
    return static_cast<float>(value->x) * collector->scale;
}

float path_y(const FT_Vector* value, const OutlineCollector* collector) {
    return -static_cast<float>(value->y) * collector->scale;
}

int outline_move_to(const FT_Vector* to, void* user) {
    auto* collector = static_cast<OutlineCollector*>(user);
    collector->close_contour();
    collector->commands.push_back(static_cast<float>(kMove));
    collector->commands.push_back(path_x(to, collector));
    collector->commands.push_back(path_y(to, collector));
    collector->contour_open = true;
    return 0;
}

int outline_line_to(const FT_Vector* to, void* user) {
    auto* collector = static_cast<OutlineCollector*>(user);
    collector->commands.push_back(static_cast<float>(kLine));
    collector->commands.push_back(path_x(to, collector));
    collector->commands.push_back(path_y(to, collector));
    return 0;
}

int outline_conic_to(const FT_Vector* control, const FT_Vector* to, void* user) {
    auto* collector = static_cast<OutlineCollector*>(user);
    collector->commands.push_back(static_cast<float>(kQuad));
    collector->commands.push_back(path_x(control, collector));
    collector->commands.push_back(path_y(control, collector));
    collector->commands.push_back(path_x(to, collector));
    collector->commands.push_back(path_y(to, collector));
    return 0;
}

int outline_cubic_to(
    const FT_Vector* control_one,
    const FT_Vector* control_two,
    const FT_Vector* to,
    void* user
) {
    auto* collector = static_cast<OutlineCollector*>(user);
    collector->commands.push_back(static_cast<float>(kCubic));
    collector->commands.push_back(path_x(control_one, collector));
    collector->commands.push_back(path_y(control_one, collector));
    collector->commands.push_back(path_x(control_two, collector));
    collector->commands.push_back(path_y(control_two, collector));
    collector->commands.push_back(path_x(to, collector));
    collector->commands.push_back(path_y(to, collector));
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_tiqian_math_font_android_NativeMathBridge_createFace(
    JNIEnv* env,
    jobject,
    jbyteArray font_bytes
) {
    if (font_bytes == nullptr || env->GetArrayLength(font_bytes) == 0) {
        throw_illegal_state(env, "OpenType math font bytes must not be empty");
        return 0;
    }
    auto face = std::make_unique<NativeFace>();
    const jsize byte_count = env->GetArrayLength(font_bytes);
    face->bytes.resize(static_cast<std::size_t>(byte_count));
    env->GetByteArrayRegion(
        font_bytes,
        0,
        byte_count,
        reinterpret_cast<jbyte*>(face->bytes.data())
    );
    if (env->ExceptionCheck()) return 0;

    if (FT_Init_FreeType(&face->ft_library) != 0 ||
        FT_New_Memory_Face(
            face->ft_library,
            reinterpret_cast<const FT_Byte*>(face->bytes.data()),
            static_cast<FT_Long>(face->bytes.size()),
            0,
            &face->ft_face
        ) != 0) {
        throw_illegal_state(env, "FreeType could not open the supplied OpenType math font");
        return 0;
    }

    face->hb_blob = hb_blob_create(
        reinterpret_cast<const char*>(face->bytes.data()),
        static_cast<unsigned int>(face->bytes.size()),
        HB_MEMORY_MODE_READONLY,
        nullptr,
        nullptr
    );
    face->hb_face = hb_face_create(face->hb_blob, 0);
    face->hb_font = hb_font_create(face->hb_face);
    hb_ot_font_set_funcs(face->hb_font);
    const int upem = static_cast<int>(face->ft_face->units_per_EM);
    hb_font_set_scale(face->hb_font, upem, upem);
    hb_font_set_ppem(face->hb_font, 0, 0);

    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(face.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_org_tiqian_math_font_android_NativeMathBridge_destroyFace(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete reinterpret_cast<NativeFace*>(static_cast<std::uintptr_t>(handle));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_tiqian_math_font_android_NativeMathBridge_shape(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring text,
    jfloat font_size,
    jint script_style_level
) {
    NativeFace* face = from_handle(env, handle);
    if (face == nullptr || text == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(face->mutex);

    const jsize text_length = env->GetStringLength(text);
    const jchar* characters = env->GetStringChars(text, nullptr);
    if (characters == nullptr) return nullptr;
    hb_buffer_t* buffer = hb_buffer_create();
    hb_buffer_add_utf16(
        buffer,
        reinterpret_cast<const std::uint16_t*>(characters),
        text_length,
        0,
        text_length
    );
    env->ReleaseStringChars(text, characters);
    hb_buffer_set_direction(buffer, HB_DIRECTION_LTR);
    hb_buffer_set_script(buffer, hb_script_from_string("Zmth", 4));
    hb_buffer_set_language(buffer, hb_language_from_string("und", 3));
    hb_buffer_set_cluster_level(buffer, HB_BUFFER_CLUSTER_LEVEL_MONOTONE_CHARACTERS);

    hb_feature_t feature;
    unsigned int feature_count = 0;
    if (script_style_level == 1) {
        hb_feature_from_string("ssty=1", -1, &feature);
        feature_count = 1;
    } else if (script_style_level >= 2) {
        hb_feature_from_string("ssty=2", -1, &feature);
        feature_count = 1;
    }
    hb_shape(face->hb_font, buffer, feature_count == 0 ? nullptr : &feature, feature_count);

    unsigned int glyph_count = 0;
    const hb_glyph_info_t* infos = hb_buffer_get_glyph_infos(buffer, &glyph_count);
    const hb_glyph_position_t* positions = hb_buffer_get_glyph_positions(buffer, &glyph_count);
    const float scale = font_size / static_cast<float>(face->ft_face->units_per_EM);
    std::vector<float> packed(
        static_cast<std::size_t>(kRunHeaderSize + glyph_count * kRunGlyphStride),
        0.0f
    );
    packed[0] = static_cast<float>(glyph_count);
    float pen_x = 0.0f;
    float ascent = 0.0f;
    float descent = 0.0f;
    bool missing = false;
    for (unsigned int index = 0; index < glyph_count; ++index) {
        const hb_codepoint_t glyph_id = infos[index].codepoint;
        const float glyph_x = pen_x + static_cast<float>(positions[index].x_offset) * scale;
        const float baseline_offset = -static_cast<float>(positions[index].y_offset) * scale;
        const float advance = static_cast<float>(positions[index].x_advance) * scale;
        GlyphBounds bounds;
        if (!load_glyph_bounds(face, glyph_id, font_size, &bounds)) {
            hb_buffer_destroy(buffer);
            throw_illegal_state(env, "FreeType could not measure shaped glyph " + std::to_string(glyph_id));
            return nullptr;
        }
        ascent = std::max(ascent, -(bounds.top + baseline_offset));
        descent = std::max(descent, bounds.bottom + baseline_offset);
        missing = missing || glyph_id == 0;
        const std::size_t base = static_cast<std::size_t>(kRunHeaderSize + index * kRunGlyphStride);
        packed[base] = static_cast<float>(glyph_id);
        packed[base + 1] = static_cast<float>(infos[index].cluster);
        packed[base + 2] = glyph_x;
        packed[base + 3] = baseline_offset;
        packed[base + 4] = advance;
        packed[base + 5] = bounds.left;
        packed[base + 6] = bounds.top;
        packed[base + 7] = bounds.right;
        packed[base + 8] = bounds.bottom;
        pen_x += advance;
    }
    packed[1] = pen_x;
    packed[2] = std::max(0.0f, ascent);
    packed[3] = std::max(0.0f, descent);
    packed[4] = missing ? 1.0f : 0.0f;
    hb_buffer_destroy(buffer);
    return to_float_array(env, packed);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_tiqian_math_font_android_NativeMathBridge_measureGlyph(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint glyph_id,
    jfloat font_size
) {
    NativeFace* face = from_handle(env, handle);
    if (face == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(face->mutex);
    GlyphBounds bounds;
    if (!load_glyph_bounds(face, static_cast<hb_codepoint_t>(glyph_id), font_size, &bounds)) {
        throw_illegal_state(env, "FreeType could not measure glyph " + std::to_string(glyph_id));
        return nullptr;
    }
    const float scale = font_size / static_cast<float>(face->ft_face->units_per_EM);
    const float advance = static_cast<float>(
        hb_font_get_glyph_h_advance(face->hb_font, static_cast<hb_codepoint_t>(glyph_id))
    ) * scale;
    return to_float_array(env, {
        static_cast<float>(glyph_id),
        advance,
        bounds.left,
        bounds.top,
        bounds.right,
        bounds.bottom,
        bounds.has_outline ? 1.0f : 0.0f,
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_tiqian_math_font_android_NativeMathBridge_glyphOutline(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint glyph_id,
    jfloat font_size
) {
    NativeFace* face = from_handle(env, handle);
    if (face == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(face->mutex);
    const FT_Int32 flags = FT_LOAD_NO_SCALE | FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP;
    if (FT_Load_Glyph(face->ft_face, static_cast<FT_UInt>(glyph_id), flags) != 0 ||
        face->ft_face->glyph->format != FT_GLYPH_FORMAT_OUTLINE ||
        face->ft_face->glyph->outline.n_points == 0) {
        return nullptr;
    }
    OutlineCollector collector;
    collector.scale = font_size / static_cast<float>(face->ft_face->units_per_EM);
    FT_Outline_Funcs callbacks = {
        outline_move_to,
        outline_line_to,
        outline_conic_to,
        outline_cubic_to,
        0,
        0,
    };
    if (FT_Outline_Decompose(&face->ft_face->glyph->outline, &callbacks, &collector) != 0) {
        return nullptr;
    }
    collector.close_contour();
    return to_float_array(env, collector.commands);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_tiqian_math_font_android_NativeMathBridge_nativeVersions(
    JNIEnv* env,
    jobject
) {
    int major = 0;
    int minor = 0;
    int patch = 0;
    FT_Library library = nullptr;
    if (FT_Init_FreeType(&library) == 0) {
        FT_Library_Version(library, &major, &minor, &patch);
        FT_Done_FreeType(library);
    }
    const std::string value =
        "FreeType " + std::to_string(major) + "." + std::to_string(minor) + "." +
        std::to_string(patch) + "; HarfBuzz " + hb_version_string();
    return env->NewStringUTF(value.c_str());
}
