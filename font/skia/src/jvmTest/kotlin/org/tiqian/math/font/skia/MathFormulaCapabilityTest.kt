package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.DiagnosticSeverity
import org.tiqian.math.core.MathConstructionOutlinePolicy
import org.tiqian.math.core.MathConstructionPaintGroup
import org.tiqian.math.core.MathConstructionPaintKind
import org.tiqian.math.core.MathConstructionShapeKind
import org.tiqian.math.core.MathDiagnostic
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.layout.MathFormulaCapabilityCategory
import org.tiqian.math.layout.MathFormulaCapabilityClassifier
import org.tiqian.math.layout.MathFormulaCapabilityEngine
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFormulaRenderPreflight
import org.tiqian.math.layout.MathFormulaStrictException
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MathFormulaCapabilityTest {
    @Test
    fun supportedFormulaPreservesTheLowLevelLayoutResult() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val source = "\\sqrt[3]{\\frac{a+b}{\\sqrt{x}}}+\\left(\\frac{a}{b}\\right)+\\sum_{i=1}^{n}i"
            val options = MathLayoutOptions(fontSizePx = 32f)
            val lowLevel = MathLayoutEngine(face).layout(source, options)
            val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                face.formulaCapabilityEngine().evaluate(source, options),
            )

            assertEquals(lowLevel, ready.layoutResult)
            assertTrue(ready.layoutResult.box.glyphs.isNotEmpty())
            assertTrue(face.constructionOutlineCacheStats().entries > 0, "ready closes construction replay")
        }
    }

    @Test
    fun parserErrorsAndMissingGlyphRequireWholeFormulaFallback() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val engine = face.formulaCapabilityEngine()
            val unsupported = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                engine.evaluate("x+\\text{kept}"),
            )
            assertEquals("x+\\text{kept}", unsupported.source)
            assertEquals(
                listOf(MathFormulaCapabilityCategory.UnsupportedSyntax),
                unsupported.reasons.map { it.category },
            )
            assertEquals(SourceRange(2, 7), unsupported.diagnostics.single().range)

            val malformed = assertIs<MathFormulaCapabilityResult.FallbackRequired>(engine.evaluate("x^"))
            assertEquals(
                listOf(MathFormulaCapabilityCategory.MalformedSource),
                malformed.reasons.map { it.category },
            )
            assertEquals(DiagnosticCode.MissingScriptArgument, malformed.diagnostics.single().code)

            val missingGlyphSource = "x\uDBFF\uDFFF+y"
            val missingGlyph = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                engine.evaluate(missingGlyphSource),
            )
            assertEquals(missingGlyphSource, missingGlyph.source)
            assertTrue(missingGlyph.diagnostics.any { it.code == DiagnosticCode.MissingGlyph })
            assertTrue(missingGlyph.reasons.any { it.category == MathFormulaCapabilityCategory.MissingGlyph })
        }
    }

    @Test
    fun everyErrorAndEveryIncompleteCapabilityWarningBlocks() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val layout = MathLayoutEngine(face).layout("x")
            DiagnosticCode.entries.forEach { code ->
                val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    MathFormulaCapabilityClassifier.classify(
                        layout,
                        listOf(MathDiagnostic(code, code.name, SourceRange(0, 1))),
                    ),
                    code.name,
                )
                assertEquals(MathFormulaCapabilityClassifier.category(code), fallback.reasons.single().category)
            }

            listOf(
                DiagnosticCode.MissingGlyph,
                DiagnosticCode.MissingMathConstruction,
                DiagnosticCode.MathVariantTooShort,
                DiagnosticCode.MissingConstructionOutlineEvidence,
                DiagnosticCode.UnsupportedMathDeviceAdjustment,
            ).forEach { code ->
                assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    MathFormulaCapabilityClassifier.classify(
                        layout,
                        listOf(
                            MathDiagnostic(
                                code,
                                code.name,
                                SourceRange(0, 1),
                                DiagnosticSeverity.Warning,
                            ),
                        ),
                    ),
                    code.name,
                )
            }
        }
    }

    @Test
    fun namedConstructionFailuresRemainDistinctFormulaCategories() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val layout = MathLayoutEngine(face).layout("x")
            val expectations = mapOf(
                DiagnosticCode.MissingMathConstruction to MathFormulaCapabilityCategory.MissingMathConstruction,
                DiagnosticCode.MathVariantTooShort to MathFormulaCapabilityCategory.InsufficientMathConstruction,
                DiagnosticCode.MissingConstructionOutlineEvidence to
                    MathFormulaCapabilityCategory.ConstructionOutlineUnavailable,
            )
            expectations.forEach { (code, category) ->
                val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    MathFormulaCapabilityClassifier.classify(
                        layout,
                        listOf(MathDiagnostic(code, code.name, SourceRange(0, 1), DiagnosticSeverity.Warning)),
                    ),
                )
                assertEquals(category, fallback.reasons.single().category)
            }
        }
    }

    @Test
    fun parserFailureSkipsRenderPreflightAndStrictThrowsTheSameDecision() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            var preflightCalls = 0
            val engine = MathFormulaCapabilityEngine(
                MathLayoutEngine(face),
                MathFormulaRenderPreflight {
                    preflightCalls += 1
                    emptyList()
                },
            )
            val source = "\\sqrt{x}+\\text{bad}"
            val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(engine.evaluate(source))
            assertEquals(0, preflightCalls)
            assertEquals(0, face.constructionOutlineCacheStats().entries)
            val failure = assertFailsWith<MathFormulaStrictException> { engine.requireReady(source) }
            assertEquals(fallback.source, failure.fallback.source)
            assertEquals(fallback.reasons.map { it.category }, failure.fallback.reasons.map { it.category })
            assertEquals(0, preflightCalls)
        }
    }

    @Test
    fun skiaPreflightReportsAnUnreplayableSelectedConstructionBeforeDrawing() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            val original = MathLayoutEngine(face).layout("x")
            val group = MathConstructionPaintGroup(
                id = 97,
                kind = MathConstructionPaintKind.Radical,
                shapeKind = MathConstructionShapeKind.BaseGlyph,
                sourceRange = SourceRange(0, 1),
                outlinePolicy = MathConstructionOutlinePolicy.RequireOutlineUnion,
            )
            val invalidBox = original.box.copy(
                glyphs = listOf(original.box.glyphs.single().copy(
                    glyphId = UShort.MAX_VALUE,
                    constructionGroupId = group.id,
                )),
                rules = emptyList(),
                constructionPaintGroups = listOf(group),
            )
            val diagnostic = SkiaMathFormulaRenderPreflight(face)
                .inspect(original.copy(box = invalidBox, fragments = emptyList()))
                .single()

            assertEquals(DiagnosticCode.MissingConstructionOutlineEvidence, diagnostic.code)
            assertEquals(SourceRange(0, 1), diagnostic.range)
            assertEquals(0, face.constructionOutlineCacheStats().entries)
            val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                MathFormulaCapabilityClassifier.classify(original, listOf(diagnostic)),
            )
            assertEquals(
                MathFormulaCapabilityCategory.ConstructionOutlineUnavailable,
                fallback.reasons.single().category,
            )
        }
    }
}
