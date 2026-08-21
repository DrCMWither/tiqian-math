package org.tiqian.math.font.skia

import org.tiqian.math.core.*
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.breakResponsiveDisplayLines
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathGlyphBoundsSource
import org.tiqian.math.layout.MathTextRunProvider
import org.tiqian.math.layout.MathTextRunProviderResult
import org.tiqian.math.layout.MathTextRunRequest
import org.tiqian.math.layout.MeasuredMathGlyph
import org.tiqian.math.layout.MeasuredMathRun
import kotlin.test.*

class MathFontFamilyFallbackTest {
    @Test
    fun bundledLeteResolvesRequestedWeightAndFallsBackWholeMathAtomToRegular() {
        SkiaMathFontFamily.loadBundledLete().use { regular ->
            val bold = regular.selectWeight(MathFontWeight.Bold) as SkiaMathFontFamily
            val regularX = MathLayoutEngine(regular).layout("x", MathLayoutOptions(fontSizePx = 32f)).box.glyphs.single()
            val boldX = MathLayoutEngine(bold).layout("x", MathLayoutOptions(fontSizePx = 32f)).box.glyphs.single()
            assertEquals(MathFaceId("lete-sans-math-regular"), regularX.faceId)
            assertEquals(MathFaceId("lete-sans-math-bold"), boldX.faceId)
            assertEquals(MathFontWeight.Bold, boldX.requestedWeight)
            assertEquals(MathFontWeight.Bold, boldX.resolvedWeight)
            assertNotEquals(regularX.glyphId, boldX.glyphId, "glyph ids are scoped to different official faces")

            val aleph = MathLayoutEngine(bold).layout("\\aleph_0", MathLayoutOptions(fontSizePx = 32f))
            assertTrue(aleph.diagnostics.isEmpty(), aleph.diagnostics.toString())
            val alephGlyph = aleph.box.glyphs.first { it.sourceRange == SourceRange(0, 6) }
            assertEquals(MathFaceId("lete-sans-math-regular"), alephGlyph.faceId)
            assertEquals(MathFontWeight.Bold, alephGlyph.requestedWeight)
            assertEquals(MathFontWeight.Regular, alephGlyph.resolvedWeight)
            assertEquals(MathFontFallbackReason.MissingGlyphInRequestedWeight, alephGlyph.fallbackReason)
            assertIs<MathFormulaCapabilityResult.Ready>(bold.formulaCapabilityEngine().evaluate("\\aleph_0"))
        }
    }

    @Test
    fun hostTextProviderOwnsCjkRunsAtTheActualMathStyleSizeAndMissingProviderBlocks() {
        SkiaMathFontFamily.loadBundledLete().use { regular ->
            val bold = regular.selectWeight(MathFontWeight.Bold) as SkiaMathFontFamily
            listOf(regular to MathFontWeight.Regular, bold to MathFontWeight.Bold).forEach { (face, weight) ->
                val providerFace = SkiaMathFontFace(
                    org.tiqian.math.font.opentype.LeteSansMath.load(),
                    MathFaceId("test-host-text-${weight.name.lowercase()}"),
                    resolvedWeight = weight,
                    requestedWeight = weight,
                )
                val provider = TestHostTextProvider(providerFace)
                val source = "\\text{A中文2}+原始+x^{中文2}+x_{中文2}"
                val result = MathLayoutEngine(face, textRunProvider = provider).layout(
                    source,
                    MathLayoutOptions(fontSizePx = 40f, textLocale = "zh-Hans"),
                )
                assertTrue(result.diagnostics.isEmpty(), "$weight ${result.diagnostics}")
                val cjk = result.box.glyphs.filter { it.faceId == providerFace.faceId }
                assertTrue(cjk.isNotEmpty(), "$weight CJK must use text fallback")
                assertTrue(cjk.all { it.requestedWeight == weight })
                assertTrue(cjk.all { it.resolvedWeight == weight })
                assertTrue(cjk.all { it.fallbackReason == null })
                assertTrue(cjk.all { it.hostTextDecision?.selectionReason == "TestHostSelection" })
                val scriptCjk = cjk.filter { it.style.level == MathStyleLevel.Script }
                assertTrue(scriptCjk.isNotEmpty(), "$weight mixed CJK scripts retain Script style")
                assertTrue(scriptCjk.all { it.fontSizePx < 40f }, "$weight scripts shape directly at MATH script size")
                val scriptMathDigits = result.box.glyphs.filter {
                    it.sourceRange.start in 22..source.lastIndex && it.style.level == MathStyleLevel.Script &&
                        it.faceId.value.startsWith("lete-sans-math")
                }
                assertTrue(scriptMathDigits.isNotEmpty(), "$weight digits remain on a math face")
                assertTrue(provider.requests.all { it.locale == "zh-Hans" })
                val textOnly = MathLayoutEngine(face, textRunProvider = provider).layout(
                    "\\text{中文}",
                    MathLayoutOptions(fontSizePx = 40f, textLocale = "zh-Hans"),
                )
                val textRun = provider.runs.last()
                assertEquals(textRun.ascent, textOnly.box.ascent)
                assertEquals(textRun.descent, textOnly.box.descent)
                assertTrue(MathTeXCleanBoxEvidence.HostTextRunMetrics in textOnly.box.texCleanBoxMetrics.evidence)
                assertIs<MathFormulaCapabilityResult.Ready>(face.formulaCapabilityEngine(provider).evaluate(source, MathLayoutOptions(fontSizePx = 40f)))
                val providerWithoutReplayCatalog = org.tiqian.math.layout.MathTextRunProvider(provider::shapeTextAtom)
                val unreplayable = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    face.formulaCapabilityEngine(providerWithoutReplayCatalog).evaluate("\\text{中文}"),
                )
                assertTrue(unreplayable.diagnostics.any { it.code == DiagnosticCode.MissingGlyphOutlineEvidence })
                provider.close()
            }
            val missing = regular.formulaCapabilityEngine().evaluate("\\text{中文}+原始")
            val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(missing)
            assertTrue(fallback.diagnostics.any { it.code == DiagnosticCode.MissingTextRunProvider })
            assertTrue(fallback.reasons.any { it.category == org.tiqian.math.layout.MathFormulaCapabilityCategory.MissingTextProvider })
        }
    }

    @Test
    fun fullwidthClauseSeparatorBreaksAsTrailingPunctuationWithoutExtraGlue() {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            val hostFaceId = MathFaceId("test-host-clause-separator")
            TestHostTextProvider(
                SkiaMathFontFace(
                    org.tiqian.math.font.opentype.LeteSansMath.load(),
                    hostFaceId,
                ),
            ).use { provider ->
                val source = "C_1=1-C，C_2=C-\\frac14"
                val result = MathLayoutEngine(math, textRunProvider = provider).layout(
                    source,
                    MathLayoutOptions(fontSizePx = 32f, textLocale = "zh-Hans"),
                )
                assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())

                val separator = result.fragments.single { it.sourceRange == SourceRange(7, 8) }
                assertEquals(MathAtomClass.Punctuation, separator.atomClass)
                assertEquals(MathBreakKind.PunctuationTrailing, separator.breakAfter?.kind)
                // FullwidthClauseSeparatorCarriesOwnSpace: no pair glue stacked on the glyph.
                assertEquals(0f, separator.trailingGlue.naturalPx)

                // With no relation/binary competitor, the separator is the only legal boundary.
                val commaOnly = MathLayoutEngine(math, textRunProvider = provider).layout(
                    "abc，def",
                    MathLayoutOptions(fontSizePx = 32f, textLocale = "zh-Hans"),
                )
                assertTrue(commaOnly.diagnostics.isEmpty(), commaOnly.diagnostics.toString())
                val narrow = commaOnly.breakResponsiveDisplayLines(
                    maxWidthPx = commaOnly.box.visualWidth * 0.7f,
                )
                assertEquals(2, narrow.lines.size)
                assertEquals(MathBreakKind.PunctuationTrailing, narrow.lines[1].breakKind)
            }
        }
    }

    @Test
    fun fullwidthCjkPunctuationUsesReplayableHostTextInsteadOfTheMathFontNotdefGlyph() {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            val hostFaceId = MathFaceId("test-host-cjk-punctuation")
            TestHostTextProvider(
                SkiaMathFontFace(
                    org.tiqian.math.font.opentype.LeteSansMath.load(),
                    hostFaceId,
                ),
            ).use { provider ->
                val source = "C_1=1-C，C_2=C-\\frac14"
                val result = MathLayoutEngine(math, textRunProvider = provider).layout(
                    source,
                    MathLayoutOptions(fontSizePx = 32f, textLocale = "zh-Hans"),
                )

                assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
                val request = provider.requests.single { it.text == "，" }
                assertEquals(SourceRange(7, 8), request.sourceRange)
                assertEquals(MathTextOrigin.ImplicitCjk, request.origin)
                assertTrue(result.box.glyphs.any {
                    it.faceId == hostFaceId && it.sourceRange == SourceRange(7, 8)
                })
                assertTrue(result.box.glyphs.none {
                    it.faceId.value.startsWith("lete-sans-math") && it.sourceRange == SourceRange(7, 8)
                })
                assertIs<MathFormulaCapabilityResult.Ready>(
                    math.formulaCapabilityEngine(provider).evaluate(
                        source,
                        MathLayoutOptions(fontSizePx = 32f, textLocale = "zh-Hans"),
                    ),
                )

                val withoutHostText = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    math.formulaCapabilityEngine().evaluate(
                        source,
                        MathLayoutOptions(fontSizePx = 32f, textLocale = "zh-Hans"),
                    ),
                )
                assertTrue(withoutHostText.diagnostics.any {
                    it.code == DiagnosticCode.MissingTextRunProvider && it.range == SourceRange(7, 8)
                })
                assertTrue(withoutHostText.diagnostics.none {
                    it.code == DiagnosticCode.MissingGlyph && it.range == SourceRange(7, 8)
                })
            }
        }
    }

    @Test
    fun stretchConstructionKeepsOneMathFaceForConstantsGlyphsAndPaintOwnership() {
        SkiaMathFontFamily.loadBundledLete().use { regular ->
            val bold = regular.selectWeight(MathFontWeight.Bold) as SkiaMathFontFamily
            val result = MathLayoutEngine(bold).layout(
                "\\sqrt{\\frac{\\frac{a}{b}}{\\frac{c}{d}}}+\\left(\\frac{a}{b}\\right)",
                MathLayoutOptions(MathMode.Display, 48f),
            )
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            result.box.constructionPaintGroups.forEach { group ->
                val owned = result.box.glyphs.filter { it.constructionGroupId == group.id }
                assertTrue(owned.isNotEmpty())
                assertTrue(owned.all { it.faceId == group.faceId })
                assertNotNull(bold.mathFontForOrNull(group.faceId), "a construction face must own a MATH table")
            }
            assertIs<MathFormulaCapabilityResult.Ready>(bold.formulaCapabilityEngine().evaluate(
                "\\sqrt{\\frac{\\frac{a}{b}}{\\frac{c}{d}}}+\\left(\\frac{a}{b}\\right)",
                MathLayoutOptions(MathMode.Display, 48f),
            ))
        }
    }

    @Test
    fun familySpecRejectsSilentSerifSansCrossingAndLegacyFaceRemainsSerif() {
        val lete = org.tiqian.math.font.opentype.LeteSansMath.loadBytes()
        assertFailsWith<IllegalArgumentException> {
            MathFontFamilySpec(
                "invalid",
                MathFontClass.Serif,
                listOf(MathFontFaceSpec(MathFaceId("sans"), lete, MathFontClass.SansSerif)),
            )
        }
        SkiaMathFontFace(org.tiqian.math.font.opentype.OpenTypeMathReader().read(lete)).use { legacy ->
            assertEquals(MathFontClass.Serif, legacy.fontClass)
            assertEquals(MathFaceId.LegacySingleFace, legacy.faceId)
        }
    }

    @Test
    fun hostTextFaceIdCollisionIsRejectedBeforeReplay() {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            val collidingHostFace = SkiaMathFontFace(
                org.tiqian.math.font.opentype.LeteSansMath.load(),
                MathFaceId("lete-sans-math-regular"),
            )
            FixtureMultiFaceHostProvider(listOf(collidingHostFace)).use { provider ->
                    val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                        math.formulaCapabilityEngine(provider).evaluate("\\text{中}"),
                    )
                    val diagnostic = fallback.diagnostics.single {
                        it.code == DiagnosticCode.ReplayFaceOwnershipConflict
                    }
                    assertEquals(SourceRange(6, 7), diagnostic.range)
                    assertEquals(
                        org.tiqian.math.layout.MathFormulaCapabilityCategory.ReplayFaceOwnershipConflict,
                        fallback.reasons.single().category,
                    )
                    val combined = combineSkiaReplayCatalogs(math, provider)
                    assertEquals(
                        MathReplayFaceOwnership.Conflict,
                        combined.replayFaceOwnership(MathFaceId("lete-sans-math-regular")),
                    )
                    assertEquals(null, combined.replayFace(MathFaceId("lete-sans-math-regular")))
            }
        }
    }

    @Test
    fun multiFaceHostRunPreservesClustersReplayOwnersAndStructuredDecisions() {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            val primary = SkiaMathFontFace(
                org.tiqian.math.font.opentype.LeteSansMath.load(),
                MathFaceId("fixture-misans-primary"),
            )
            val extension = SkiaMathFontFace(
                org.tiqian.math.font.opentype.LeteSansMath.load(),
                MathFaceId("fixture-misans-l3"),
            )
            FixtureMultiFaceHostProvider(listOf(primary, extension)).use { provider ->
                val source = "\\text{中文A}"
                val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                    math.formulaCapabilityEngine(provider).evaluate(source, MathLayoutOptions(fontSizePx = 32f)),
                )
                val glyphs = ready.layoutResult.box.glyphs
                assertEquals(
                    listOf("fixture-misans-primary", "fixture-misans-l3", "fixture-misans-primary"),
                    glyphs.map { it.faceId.value },
                )
                assertEquals(listOf(SourceRange(6, 7), SourceRange(7, 8), SourceRange(8, 9)), glyphs.map { it.sourceRange })
                assertEquals(listOf(0f, 12f, 25f), glyphs.map { it.x })
                assertEquals(39f, ready.layoutResult.box.width)
                assertEquals(listOf("PrimaryCoverage", "ExtensionCoverage", "PrimaryCoverage"),
                    glyphs.map { it.hostTextDecision?.selectionReason })
                assertEquals("HanBody", glyphs.first().hostTextDecision?.hostRole)
                assertTrue(ready.layoutResult.debugDump.contains("fixture-misans-l3"))
                val decision = ready.layoutResult.decisions.single { it.name == "TeXEmbeddedText" }
                assertEquals("PrimaryCoverage,ExtensionCoverage", decision.details["hostSelectionReasons"])
                assertEquals("fixture-misans-primary,fixture-misans-l3", decision.details["faceIds"])
            }
        }
    }

    @Test
    fun standaloneProviderRejectsRtlAndPlatformStringReplayIssueIsStructured() {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            SkiaMathTextRunProvider.fromBytes(
                MathFaceId("restricted-standalone"),
                org.tiqian.math.font.opentype.LeteSansMath.loadBytes(),
            ).use { standalone ->
                val rtl = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    math.formulaCapabilityEngine(standalone).evaluate("\\text{abc אבג}"),
                )
                assertTrue(rtl.diagnostics.any { it.code == DiagnosticCode.UnsupportedHostTextShaping })
                assertTrue(rtl.reasons.any {
                    it.category == org.tiqian.math.layout.MathFormulaCapabilityCategory.HostTextShapingUnsupported
                })
            }

            val nonReplayable = MathTextRunProvider { request ->
                MathTextRunProviderResult.CapabilityIssue(MathHostTextCapabilityIssue(
                    MathHostTextCapabilityIssueCode.PlatformMultiFaceStringDraw,
                    "Host shaping requires one platform string replay token",
                    request.sourceRange,
                ))
            }
            val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                math.formulaCapabilityEngine(nonReplayable).evaluate("\\text{中文}"),
            )
            assertTrue(fallback.diagnostics.any { it.code == DiagnosticCode.NonReplayableHostTextRun })
            assertTrue(fallback.reasons.any {
                it.category == org.tiqian.math.layout.MathFormulaCapabilityCategory.HostTextReplayUnavailable
            })
        }
    }

    @Test
    fun preflightRejectsCatalogOwnedButInvalidGlyphIds() {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            val hostFace = SkiaMathFontFace(
                org.tiqian.math.font.opentype.LeteSansMath.load(),
                MathFaceId("fixture-invalid-glyph-host"),
            )
            FixtureMultiFaceHostProvider(listOf(hostFace), glyphBase = 0xffff).use { provider ->
                val fallback = assertIs<MathFormulaCapabilityResult.FallbackRequired>(
                    math.formulaCapabilityEngine(provider).evaluate("\\text{中}"),
                )
                assertTrue(fallback.diagnostics.any { it.code == DiagnosticCode.MissingGlyphOutlineEvidence })
            }
        }
    }

    @Test
    fun malformedHostRunMetricsBecomeDiagnosticsBeforeMathBoxConstruction() {
        SkiaMathFontFamily.loadBundledLete().use { math ->
            val provider = MathTextRunProvider {
                MathTextRunProviderResult.Ready(MeasuredMathRun(
                    glyphs = emptyList(),
                    width = -1f,
                    ascent = 20f,
                    descent = 5f,
                    missingGlyph = false,
                ))
            }
            val result = MathLayoutEngine(math, textRunProvider = provider).layout("\\text{中}")
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.InvalidHostTextRunEvidence })
            assertTrue(result.box.glyphs.isEmpty())
        }
    }
}

private class FixtureMultiFaceHostProvider(
    private val faces: List<SkiaMathFontFace>,
    private val glyphBase: Int = 600,
) : MathTextRunProvider, SkiaReplayCatalog, AutoCloseable {
    init { require(faces.isNotEmpty()) }

    override fun shapeTextAtom(request: MathTextRunRequest): MathTextRunProviderResult {
        var x = 0f
        val glyphs = request.text.indices.map { index ->
            val face = if (faces.size > 1 && index == 1) faces[1] else faces[0]
            val advance = 12f + index
            MeasuredMathGlyph(
                glyphId = (glyphBase + index).toUShort(),
                x = x.also { x += advance },
                advance = advance,
                inkBounds = MathRect(0f, -20f, advance - 1f, 5f),
                textCluster = index,
                faceId = face.faceId,
                fontClass = null,
                requestedWeight = request.requestedWeight,
                resolvedWeight = face.resolvedWeight,
                fallbackReason = null,
                hostTextDecision = MathHostTextFaceDecision(
                    sourceRange = SourceRange(request.sourceRange.start + index, request.sourceRange.start + index + 1),
                    clusterRangeUtf16 = SourceRange(index, index + 1),
                    hostRole = "HanBody",
                    faceId = face.faceId,
                    fontKey = face.faceId.value,
                    requestedWeight = request.requestedWeight,
                    resolvedWeight = face.resolvedWeight,
                    selectionReason = if (index == 1 && faces.size > 1) "ExtensionCoverage" else "PrimaryCoverage",
                    substitutionReason = if (index == 1 && faces.size > 1) "PrimaryMissingCluster" else null,
                ),
            )
        }
        return MathTextRunProviderResult.Ready(MeasuredMathRun(
            glyphs = glyphs,
            width = x,
            ascent = 20f,
            descent = 5f,
            missingGlyph = false,
            boundsSource = MathGlyphBoundsSource.Outline,
        ))
    }

    override fun replayFace(faceId: MathFaceId): SkiaReplayFace? = faces.firstOrNull { it.faceId == faceId }
    override fun constructionFace(faceId: MathFaceId): SkiaMathFontFace? = null
    override fun close() = faces.forEach(SkiaMathFontFace::close)
}

internal class TestHostTextProvider(
    private val face: SkiaMathFontFace,
) : org.tiqian.math.layout.MathTextRunProvider, SkiaReplayCatalog, AutoCloseable {
    val requests = mutableListOf<org.tiqian.math.layout.MathTextRunRequest>()
    val runs = mutableListOf<org.tiqian.math.layout.MeasuredMathRun>()

    override fun shapeTextAtom(request: org.tiqian.math.layout.MathTextRunRequest): org.tiqian.math.layout.MathTextRunProviderResult {
        requests += request
        val replacement = buildString { repeat(request.text.codePointCount(0, request.text.length)) { append('x') } }
        val run = face.shape(replacement, request.fontSizePx, MathStyle.Text, request.sourceRange)
        val measured = run.copy(
            ascent = run.ascent + 4f,
            descent = run.descent + 3f,
            glyphs = run.glyphs.map { glyph -> glyph.copy(
                fontClass = null,
                requestedWeight = request.requestedWeight,
                resolvedWeight = face.resolvedWeight,
                fallbackReason = null,
                hostTextDecision = MathHostTextFaceDecision(
                    sourceRange = SourceRange(
                        request.sourceRange.start + glyph.textCluster,
                        (request.sourceRange.start + glyph.textCluster + 1).coerceAtMost(request.sourceRange.endExclusive),
                    ),
                    clusterRangeUtf16 = SourceRange(
                        glyph.textCluster,
                        (glyph.textCluster + 1).coerceAtMost(request.text.length),
                    ),
                    hostRole = request.origin.name,
                    faceId = face.faceId,
                    fontKey = "test-host-font",
                    requestedWeight = request.requestedWeight,
                    resolvedWeight = face.resolvedWeight,
                    selectionReason = "TestHostSelection",
                ),
            ) },
        ).also(runs::add)
        return org.tiqian.math.layout.MathTextRunProviderResult.Ready(measured)
    }
    override fun replayFace(faceId: MathFaceId): SkiaReplayFace? = face.takeIf { it.faceId == faceId }
    override fun constructionFace(faceId: MathFaceId): SkiaMathFontFace? = null
    override fun close() = face.close()
}
