package org.tiqian.math.font.android

internal object NativeMathBridge {
    init {
        System.loadLibrary("tiqian_math_android")
    }

    external fun createFace(fontBytes: ByteArray): Long
    external fun destroyFace(handle: Long)
    external fun shape(handle: Long, text: String, fontSizePx: Float, scriptStyleLevel: Int): FloatArray
    external fun measureGlyph(handle: Long, glyphId: Int, fontSizePx: Float): FloatArray
    external fun glyphOutline(handle: Long, glyphId: Int, fontSizePx: Float): FloatArray?
    external fun nativeVersions(): String
}
