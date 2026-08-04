package org.tiqian.math.scanner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

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
