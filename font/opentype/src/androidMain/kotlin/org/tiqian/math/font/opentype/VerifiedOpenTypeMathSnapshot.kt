package org.tiqian.math.font.opentype

import java.security.MessageDigest

/**
 * A decoded MATH snapshot that cannot be attached to font bytes without verifying their SHA-256.
 * The attached byte array becomes the font face's immutable ownership unit.
 */
class VerifiedOpenTypeMathSnapshot internal constructor(
    private val decoded: DecodedOpenTypeMathSnapshot,
) {
    val fontSha256: String get() = decoded.fontSha256

    fun attach(fontBytes: ByteArray): OpenTypeMathFont {
        val actualSha256 = fontBytes.sha256Hex()
        check(actualSha256 == decoded.fontSha256) {
            "Bundled font SHA-256 mismatch: expected ${decoded.fontSha256}, found $actualSha256"
        }
        return decoded.metadata.copy(bytes = fontBytes)
    }
}

object VerifiedOpenTypeMathSnapshotLoader {
    fun prepare(snapshotBytes: ByteArray, expectedSha256: String): VerifiedOpenTypeMathSnapshot {
        requireValidSha256(expectedSha256)
        val decoded = OpenTypeMathSnapshotDecoder.decode(snapshotBytes)
        check(decoded.fontSha256 == expectedSha256) {
            "Bundled MATH snapshot belongs to ${decoded.fontSha256}, expected $expectedSha256"
        }
        return VerifiedOpenTypeMathSnapshot(decoded)
    }

    fun load(
        fontBytes: ByteArray,
        snapshotBytes: ByteArray,
        expectedSha256: String,
    ): OpenTypeMathFont = prepare(snapshotBytes, expectedSha256).attach(fontBytes)
}

private fun requireValidSha256(value: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "expectedSha256 must be a lowercase SHA-256 hex digest"
    }
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
