package org.tiqian.math.scanner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.tiqian.math.core.MathMode
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.skia.SkiaMathFontFace
import org.tiqian.math.font.skia.formulaCapabilityEngine
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutOptions

fun main(args: Array<String>) {
    val arguments = ScannerArguments.parse(args)
    val sources = Files.readAllLines(arguments.input)
    val mathFont = when (arguments.font) {
        ScannerFont.Lete -> LeteSansMath.load()
        ScannerFont.Stix -> StixTwoMath.load()
    }
    val report = SkiaMathFontFace(mathFont).use { face ->
        MathFormulaCorpusScanner(
            capabilityEngine = face.formulaCapabilityEngine(),
            options = MathLayoutOptions(
                mode = arguments.mode,
                fontSizePx = arguments.fontSizePx,
            ),
            maxSamplesPerCategory = arguments.maxSamples,
        ).scan(sources)
    }
    val json = report.toJson() + "\n"
    arguments.output?.writeText(json) ?: print(json)
}

private enum class ScannerFont { Lete, Stix }

private data class ScannerArguments(
    val input: Path,
    val output: Path?,
    val font: ScannerFont,
    val mode: MathMode,
    val fontSizePx: Float,
    val maxSamples: Int,
) {
    companion object {
        fun parse(args: Array<String>): ScannerArguments {
            var input: Path? = null
            var output: Path? = null
            var font = ScannerFont.Lete
            var mode = MathMode.Inline
            var fontSizePx = 24f
            var maxSamples = 3
            var index = 0
            while (index < args.size) {
                val argument = args[index]
                when {
                    argument == "--output" -> output = Path.of(args.valueAfter(index++))
                    argument.startsWith("--output=") -> output = Path.of(argument.substringAfter('='))
                    argument.startsWith("--font=") -> font = when (argument.substringAfter('=').lowercase()) {
                        "lete" -> ScannerFont.Lete
                        "stix" -> ScannerFont.Stix
                        else -> error("--font must be lete or stix")
                    }
                    argument.startsWith("--mode=") -> mode = when (argument.substringAfter('=').lowercase()) {
                        "inline" -> MathMode.Inline
                        "display" -> MathMode.Display
                        else -> error("--mode must be inline or display")
                    }
                    argument.startsWith("--font-size=") ->
                        fontSizePx = argument.substringAfter('=').toFloat().also { require(it > 0f) }
                    argument.startsWith("--max-samples=") ->
                        maxSamples = argument.substringAfter('=').toInt().also { require(it >= 0) }
                    argument.startsWith("--") -> error("Unknown option: $argument")
                    input == null -> input = Path.of(argument)
                    else -> error("Only one input file is accepted")
                }
                index += 1
            }
            return ScannerArguments(
                input = requireNotNull(input) {
                    "Usage: math-formula-scanner <one-formula-per-line file> " +
                        "[--output=report.json] [--font=lete|stix] [--mode=inline|display] " +
                        "[--font-size=24] [--max-samples=3]"
                },
                output = output,
                font = font,
                mode = mode,
                fontSizePx = fontSizePx,
                maxSamples = maxSamples,
            )
        }

        private fun Array<String>.valueAfter(index: Int): String =
            getOrNull(index + 1) ?: error("${get(index)} requires a value")
    }
}
