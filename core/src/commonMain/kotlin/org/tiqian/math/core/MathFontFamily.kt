package org.tiqian.math.core

import kotlin.jvm.JvmInline

/** Stable replay identity. Glyph ids are meaningful only together with this value. */
@JvmInline
value class MathFaceId(val value: String) {
    init {
        require(value.isNotBlank()) { "math face id must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        val LegacySingleFace = MathFaceId("legacy-single-face")
    }
}

enum class MathFontClass { Serif, SansSerif }

enum class MathFontWeight(val cssWeight: Int) {
    Regular(400),
    Bold(700);

    companion object {
        fun nearest(cssWeight: Int): MathFontWeight = if (cssWeight >= 600) Bold else Regular
    }
}

enum class MathFontFallbackReason {
    RequestedFace,
    RequestedWeightUnavailable,
    MissingGlyphInRequestedWeight,
    MissingMathConstructionInRequestedWeight,
}

/** Why a host text run cannot currently cross the measure/replay boundary losslessly. */
enum class MathHostTextCapabilityIssueCode {
    NonReplayableHostTextRun,
    PlatformMultiFaceStringDraw,
    UnsupportedBidirectionalText,
    UnsupportedComplexScript,
    InvalidHostTextRunEvidence,
}

data class MathHostTextCapabilityIssue(
    val code: MathHostTextCapabilityIssueCode,
    val message: String,
    val sourceRange: SourceRange,
)

/**
 * Host-owned font-selection evidence. These fields deliberately do not reuse
 * [MathFontFallbackReason], whose values describe MATH-family fallback only.
 */
data class MathHostTextFaceDecision(
    val sourceRange: SourceRange,
    /** UTF-16 offsets within the text atom passed to the host provider. */
    val clusterRangeUtf16: SourceRange,
    val hostRole: String,
    val faceId: MathFaceId,
    val fontKey: String?,
    val requestedWeight: MathFontWeight,
    val resolvedWeight: MathFontWeight,
    val selectionReason: String,
    val substitutionReason: String? = null,
    val capabilityIssue: MathHostTextCapabilityIssue? = null,
)

enum class MathReplayFaceOwnership {
    Missing,
    Unique,
    Conflict,
}

/** Immutable host-supplied font bytes plus the semantic facts needed before shaping. */
class MathFontFaceSpec(
    val faceId: MathFaceId,
    fontBytes: ByteArray,
    val fontClass: MathFontClass = MathFontClass.Serif,
    val weight: MathFontWeight = MathFontWeight.Regular,
) {
    val fontBytes: ByteArray = fontBytes.copyOf()
}

/** A class-safe family of OpenType MATH faces. Host text faces are intentionally not members. */
class MathFontFamilySpec(
    val familyId: String,
    val fontClass: MathFontClass = MathFontClass.Serif,
    val faces: List<MathFontFaceSpec>,
) {
    init {
        require(familyId.isNotBlank()) { "math family id must not be blank" }
        require(faces.isNotEmpty()) { "a math family needs at least one MATH face" }
        require(faces.map { it.faceId }.toSet().size == faces.size) { "face ids must be unique" }
        require(faces.all { it.fontClass == fontClass }) {
            "font fallback cannot silently cross Serif/SansSerif class"
        }
    }
}

/** Frontend semantic classifier only; font fallback and segmentation belong to the host provider. */
fun Int.isCjkMathTextScalar(): Boolean = this in 0x2E80..0x2FFF ||
    this in 0x3040..0x30FF || // Hiragana, Katakana
    this in 0x3100..0x312F || // Bopomofo
    this in 0x31A0..0x31BF ||
    this in 0x31F0..0x31FF ||
    this in 0x3400..0x4DBF ||
    this in 0x4E00..0x9FFF ||
    this in 0xAC00..0xD7AF || // Hangul syllables
    this in 0xF900..0xFAFF ||
    this in 0x20000..0x323AF
