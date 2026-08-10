package org.tiqian.math.scanner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tiqian.math.font.opentype.LeteSansMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MathFormulaCorpusScannerTest {
    @Test
    fun cliProducesTheReviewedStableMachineReport() {
        val input = resourcePath("formulas.txt")
        val expected = resourcePath("expected-report.json").readText()
        val outputA = Files.createTempFile("math-compose-scan-a", ".json")
        val outputB = Files.createTempFile("math-compose-scan-b", ".json")
        try {
            runScanner(input, outputA, "lete")
            runScanner(input, outputB, "stix")
            assertEquals(expected, outputA.readText())
            assertEquals(outputA.readText(), outputB.readText())
        } finally {
            Files.deleteIfExists(outputA)
            Files.deleteIfExists(outputB)
        }
    }

    @Test
    fun zhihuFormulaIndexKeepsExactLatexSourcesIncludingEmbeddedNewlines() {
        assertEquals(
            listOf(
                "x_1^2+\\frac{a}{b}",
                "\\widehat{x+y}\n+\\overline{z}",
                "\\unknowncommand{中文}",
            ),
            MathFormulaCorpusInput.read(
                resourcePath("zhihu-formulas.json"),
                MathFormulaCorpusInputFormat.Auto,
            ),
        )
    }

    @Test
    fun cliScansZhihuFormulaIndexWithTheSameStableReportForBothMathFonts() {
        val input = resourcePath("zhihu-formulas.json")
        val expected = resourcePath("expected-zhihu-report.json").readText()
        val outputA = Files.createTempFile("math-compose-zhihu-scan-a", ".json")
        val outputB = Files.createTempFile("math-compose-zhihu-scan-b", ".json")
        try {
            runScanner(input, outputA, "lete")
            runScanner(input, outputB, "stix")
            assertEquals(expected, outputA.readText())
            assertEquals(outputA.readText(), outputB.readText())
        } finally {
            Files.deleteIfExists(outputA)
            Files.deleteIfExists(outputB)
        }
    }

    @Test
    fun zhihuFormulaIndexRejectsEntriesWithoutStringLatex() {
        val input = Files.createTempFile("math-compose-invalid-corpus", ".json")
        try {
            input.toFile().writeText("[{\"latex\": 42}]")
            assertFailsWith<IllegalArgumentException> {
                MathFormulaCorpusInput.read(input, MathFormulaCorpusInputFormat.ZhihuFormulaIndexJson)
            }
        } finally {
            Files.deleteIfExists(input)
        }
    }

    @Test
    fun autoFormatDoesNotMistakeBracketLeadingLineFormulaForJson() {
        val input = Files.createTempFile("math-compose-bracket-formula", ".txt")
        try {
            input.toFile().writeText("[x]\n\\left(y\\right)\n")
            assertEquals(
                listOf("[x]", "\\left(y\\right)"),
                MathFormulaCorpusInput.read(input, MathFormulaCorpusInputFormat.Auto),
            )
        } finally {
            Files.deleteIfExists(input)
        }
    }

    @Test
    fun cliCanInjectAnExplicitHostTextProviderForCorpusCoverage() {
        val input = Files.createTempFile("math-compose-text-corpus", ".txt")
        val output = Files.createTempFile("math-compose-text-report", ".json")
        val font = Files.createTempFile("math-compose-text-face", ".otf")
        try {
            input.toFile().writeText("\\text{hello world}\n")
            Files.write(font, LeteSansMath.loadBytes())
            main(arrayOf(
                input.toString(),
                "--output=$output",
                "--font=lete",
                "--font-size=24",
                "--text-font=$font",
                "--text-font-weight=400",
            ))
            val report = Json.parseToJsonElement(output.readText()).jsonObject
            assertEquals("1", report.getValue("total").jsonPrimitive.content)
            assertEquals("1", report.getValue("ready").jsonPrimitive.content)
            assertEquals("0", report.getValue("fallbackRequired").jsonPrimitive.content)
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
            Files.deleteIfExists(font)
        }
    }

    @Test
    fun mathJaxBboxIsCountedAsReadyRatherThanAnUnsupportedCommand() {
        val input = Files.createTempFile("math-compose-bbox-corpus", ".txt")
        val output = Files.createTempFile("math-compose-bbox-report", ".json")
        try {
            input.toFile().writeText("\\bbox[5px,border:1px solid red]{x^2}\n")
            main(arrayOf(input.toString(), "--output=$output", "--font=lete", "--font-size=32"))
            val report = Json.parseToJsonElement(output.readText()).jsonObject
            assertEquals("1", report.getValue("ready").jsonPrimitive.content)
            assertEquals(emptyMap(), report.getValue("byUnsupportedCommand").jsonObject)
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }

    private fun runScanner(input: Path, output: Path, font: String) {
        main(arrayOf(
            input.toString(),
            "--output=$output",
            "--font=$font",
            "--font-size=24",
            "--max-samples=2",
        ))
    }

    private fun resourcePath(name: String): Path = Path.of(
        requireNotNull(javaClass.classLoader.getResource(name)) { "missing test resource $name" }.toURI(),
    )
}
