package org.tiqian.math.font.generator

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.tiqian.math.font.opentype.LeteSansMathPrebakedData
import org.tiqian.math.font.opentype.OpenTypeMathReader

private data class BundledFace(
    val fileName: String,
    val expectedSha256: String,
)

fun main(args: Array<String>) {
    require(args.size in 1..2) { "Usage: metadata-generator <math-compose repository root> [--verify]" }
    val verifyOnly = args.getOrNull(1)?.also { require(it == "--verify") } != null
    val root = Path.of(args.first()).toAbsolutePath().normalize()
    val jvmFonts = root.resolve("font/opentype/src/jvmMain/resources/org/tiqian/math/fonts")
    val androidFonts = root.resolve("font/android/src/main/assets/org/tiqian/math/fonts")
    val faces = listOf(
        BundledFace(LeteSansMathPrebakedData.RegularFileStem, LeteSansMathPrebakedData.RegularSha256),
        BundledFace(LeteSansMathPrebakedData.BoldFileStem, LeteSansMathPrebakedData.BoldSha256),
    )

    faces.forEach { face ->
        val otfPath = jvmFonts.resolve("${face.fileName}.otf")
        val fontBytes = Files.readAllBytes(otfPath)
        val androidFontBytes = Files.readAllBytes(androidFonts.resolve("${face.fileName}.otf"))
        require(fontBytes.contentEquals(androidFontBytes)) {
            "JVM and Android bundled font bytes differ for ${face.fileName}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(fontBytes).toHex()
        require(digest == face.expectedSha256) {
            "${face.fileName} SHA-256 changed: expected ${face.expectedSha256}, found $digest"
        }
        val snapshot = OpenTypeMathSnapshotEncoder.encode(digest, OpenTypeMathReader().read(fontBytes))
        val outputName = "${face.fileName}.tqmath"
        listOf(jvmFonts.resolve(outputName), androidFonts.resolve(outputName)).forEach { output ->
            if (verifyOnly) {
                require(Files.exists(output) && Files.readAllBytes(output).contentEquals(snapshot)) {
                    "$output is stale; run ./gradlew :font:metadata-generator:run"
                }
            } else {
                Files.write(output, snapshot)
            }
        }
        println("${face.fileName}: sha256=$digest metadataBytes=${snapshot.size} verified=$verifyOnly")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
