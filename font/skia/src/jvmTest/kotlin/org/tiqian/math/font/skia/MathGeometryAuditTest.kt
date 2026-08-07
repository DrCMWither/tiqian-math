package org.tiqian.math.font.skia

import org.tiqian.math.core.DiagnosticCode
import org.tiqian.math.core.MathAdjustmentPriority
import org.tiqian.math.core.MathBreakKind
import org.tiqian.math.core.MathGlueKind
import org.tiqian.math.core.MathLayoutDecision
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathLineAdjustmentMode
import org.tiqian.math.core.MathMode
import org.tiqian.math.core.MathStyle
import org.tiqian.math.core.SourceRange
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.opentype.MathGlyphAssembly
import org.tiqian.math.font.opentype.MathGlyphAssemblyPart
import org.tiqian.math.font.opentype.MathGlyphKernInfo
import org.tiqian.math.font.opentype.MathGlyphConstruction
import org.tiqian.math.font.opentype.MathGlyphVariant
import org.tiqian.math.font.opentype.MathKernTable
import org.tiqian.math.font.opentype.OpenTypeMathFont
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathOperatorGlyphRequest
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.MeasuredMathRun
import org.tiqian.math.layout.MeasuredOutlineConstructionRun
import org.tiqian.math.layout.ResolvedMathSymbol
import org.tiqian.math.layout.ResolvedMathSymbolRun
import org.tiqian.math.layout.ResolvedMathOperator
import org.tiqian.math.layout.breakIntoLines
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MathGeometryAuditTest {
    @Test
    fun binomialDelimitersUseNormalGlyphBeforeLargerVariants() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 40f
            val range = SourceRange(0, 1)
            val left = delegate.shapeOutlineConstructionBase("(", size, range).run.glyphs.single().glyphId
            val right = delegate.shapeOutlineConstructionBase(")", size, range).run.glyphs.single().glyphId
            val unrelated = delegate.shapeConstructionBase("√", size, range).glyphs.single().glyphId
            val constants = delegate.mathFont.constants.copy(
                delimitedSubFormulaMinHeight = 0,
                stackTopShiftUp = 0,
                stackTopDisplayStyleShiftUp = 0,
                stackBottomShiftDown = 0,
                stackBottomDisplayStyleShiftDown = 0,
                stackGapMin = 0,
                stackDisplayStyleGapMin = 0,
            )
            val font = delegate.mathFont.copy(
                constants = constants,
                verticalConstructions = delegate.mathFont.verticalConstructions + mapOf(
                    left to MathGlyphConstruction(listOf(MathGlyphVariant(unrelated, 10_000)), null),
                    right to MathGlyphConstruction(listOf(MathGlyphVariant(unrelated, 10_000)), null),
                ),
            )
            val result = MathLayoutEngine(FontOverrideFace(delegate, font)).layout(
                "\\binom{}{}",
                MathLayoutOptions(MathMode.Inline, size, initialStyle = MathStyle.ScriptScript),
            )
            val delimiters = result.decisions.filter { it.name == "BinomialDelimiter" }
            assertEquals(2, delimiters.size)
            delimiters.forEach { decision ->
                assertEquals("BaseGlyph", decision.details["construction"])
                assertEquals("MathMLCore5.3.2NormalGlyph", decision.details["constructionPolicy"])
                assertEquals("1.35", decision.details["targetEmFactor"])
            }
            assertTrue(result.box.glyphs.none { it.glyphId == unrelated })
        }
    }

    @Test
    fun binomialFixedTargetCanSelectAValidatedAssemblyWithoutUsingStackHeight() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 40f
            val range = SourceRange(0, 1)
            val left = delegate.shapeConstructionBase("(", size, range).glyphs.single().glyphId
            val right = delegate.shapeConstructionBase(")", size, range).glyphs.single().glyphId
            val extender = delegate.shapeConstructionBase("√", size, range).glyphs.single().glyphId
            val assembly = MathGlyphAssembly(
                parts = listOf(
                    MathGlyphAssemblyPart(left, 0, 300, 1_000, false),
                    MathGlyphAssemblyPart(extender, 300, 300, 1_000, true),
                    MathGlyphAssemblyPart(left, 300, 0, 1_000, false),
                ),
                minimumConnectorOverlap = 100,
            )
            val rightAssembly = assembly.copy(
                parts = assembly.parts.map { part ->
                    part.copy(glyphId = if (part.glyphId == left) right else part.glyphId)
                },
            )
            val font = delegate.mathFont.copy(
                verticalConstructions = delegate.mathFont.verticalConstructions + mapOf(
                    left to MathGlyphConstruction(emptyList(), assembly),
                    right to MathGlyphConstruction(emptyList(), rightAssembly),
                ),
            )
            val result = MathLayoutEngine(FontOverrideFace(delegate, font)).layout(
                "\\binom{n}{k}",
                MathLayoutOptions(MathMode.Display, size),
            )
            val delimiters = result.decisions.filter { it.name == "BinomialDelimiter" }
            assertEquals(2, delimiters.size)
            assertTrue(delimiters.all {
                it.details["construction"] == "Assembly" &&
                    abs(it.details.getValue("targetPx").toFloat() - 95.6f) <= 0.02f &&
                    it.details["stackCoverageRequired"] == "false"
            }, delimiters.toString())
            assertTrue(delimiters.all { it.details.getValue("extenderRepetitions").toInt() >= 1 })
            assertTrue(result.decisions.any { it.name == "TeXBinomialFractionNoadPacking" })
        }
    }

    @Test
    fun ordinaryGroupsAreIndependentSubMlistsForBothFonts() = withAuditFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val plain = engine.layout("abc", MathLayoutOptions(fontSizePx = 40f))
        val grouped = engine.layout("a{b}c", MathLayoutOptions(fontSizePx = 40f))

        assertEquals(plain.box.glyphs.map { it.glyphId }, grouped.box.glyphs.map { it.glyphId })
        assertEquals(1, plain.fragments.size, "$label plain compatible Ord sequence is one shaped run")
        assertEquals(3, grouped.fragments.size, "$label braces interrupt the outer shaping run")
        assertTrue(plain.decisions.any {
            it.name == "TeXCompatibleOrdRunShaping" && it.range == SourceRange(0, 3)
        })
        assertTrue(grouped.decisions.none { it.name == "TeXCompatibleOrdRunShaping" })
        assertTrue(grouped.decisions.any { it.name == "TeXOrdSubMlist" })

        val parenthesized = engine.layout("(\\frac{a}{b})", MathLayoutOptions(fontSizePx = 40f))
        assertEquals(3, parenthesized.fragments.size, "$label opening/fraction/closing atoms")
        assertEquals(MathGlueKind.None, parenthesized.fragments[0].trailingGlue.kind, "$label open-inner has no glue")
        assertEquals(MathGlueKind.None, parenthesized.fragments[1].trailingGlue.kind, "$label inner-close has no glue")

        val edgeBinary = engine.layout("a{+}b", MathLayoutOptions(fontSizePx = 40f))
        assertEquals(3, edgeBinary.fragments.size, "$label group is one outer atom")
        assertEquals(SourceRange(1, 4), edgeBinary.fragments[1].sourceRange)
        assertEquals(SourceRange(1, 4), edgeBinary.fragments[1].box.range)
        assertTrue(edgeBinary.breakOpportunities.isEmpty(), "$label edge binary is reclassified inside group")
        assertTrue(edgeBinary.decisions.any {
            it.name == "TeXBinaryAtomReclassification" &&
                it.range == SourceRange(2, 3) &&
                it.details["to"] == "Ordinary"
        }, "$label a{+}b internal Bin becomes Ord")

        val insideGroup = engine.layout("a{b+c}d", MathLayoutOptions(fontSizePx = 40f))
        assertEquals(listOf(SourceRange(0, 1), SourceRange(1, 6), SourceRange(6, 7)),
            insideGroup.fragments.map { it.sourceRange }, "$label sub-mlist is indivisible outside")
        assertTrue(insideGroup.breakOpportunities.isEmpty(), "$label group-internal Bin penalty does not escape")
        val groupDecision = insideGroup.decisions.single { it.name == "TeXOrdSubMlist" }
        assertEquals("Ordinary,Binary,Ordinary", groupDecision.details["innerClasses"])
        assertEquals("false", groupDecision.details["innerBreaksExported"])

        val scriptedGroup = engine.layout("{x}^2", MathLayoutOptions(fontSizePx = 40f))
        val scriptDecision = scriptedGroup.decisions.single { it.name == "OpenTypeMathScriptPlacement" }
        assertEquals("CompoundBox", scriptDecision.details["baseKind"], "$label braced base does not use glyph MathKern")
        assertTrue(scriptedGroup.decisions.any {
            it.name == "OpenTypeMathKern" && it.details["strategy"] == "box-zero"
        }, "$label compound base has explicit zero-kern policy")
    }

    @Test
    fun styleDeclarationsAffectTheRemainderOfOnlyTheirContainingList() = withAuditFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val ungrouped = engine.layout("\\scriptstyle x+y", MathLayoutOptions(fontSizePx = 40f))
        assertTrue(ungrouped.box.glyphs.all { it.style == MathStyle.Script }, "$label declaration reaches following atoms")
        assertTrue(ungrouped.decisions.any {
            it.name == "TeXMathStyleDeclaration" && it.details["to"] == "Script"
        })

        val source = "{\\scriptstyle x+y}z"
        val grouped = engine.layout(source, MathLayoutOptions(fontSizePx = 40f))
        listOf('x', '+', 'y').forEach { character ->
            assertEquals(MathStyle.Script, grouped.glyphAt(source.lastIndexOf(character)).style, "$label inner $character")
        }
        assertEquals(MathStyle.Text, grouped.glyphAt(source.lastIndexOf('z')).style, "$label style resets after group")
        val trailing = engine.layout("x+\\scriptstyle", MathLayoutOptions(fontSizePx = 40f))
        assertTrue(trailing.diagnostics.isEmpty(), "$label declaration at list end")
        assertEquals(2, trailing.fragments.size)
    }

    @Test
    fun tightSpacingAndCrampedSuperscriptsReachFinalGlyphGeometryForBothFonts() = withAuditFaces { label, face ->
        val engine = MathLayoutEngine(face)
        val tight = engine.layout("x_{k-1}", MathLayoutOptions(fontSizePx = 40f))
        listOf(SourceRange(4, 5), SourceRange(5, 6)).forEach { range ->
            val spacing = tight.spacingDecision(range)
            assertEquals("tight", spacing.details["table"], "$label tight table at $range")
            assertEquals("None", spacing.details["kind"], "$label no binary glue in scripts at $range")
        }
        val normal = engine.layout("k-1", MathLayoutOptions(fontSizePx = 40f))
        listOf(SourceRange(1, 2), SourceRange(2, 3)).forEach { range ->
            assertEquals("Medium", normal.spacingDecision(range).details["kind"], "$label normal binary glue")
        }

        val source = "\\frac{a}{x^{y^z}}"
        val nested = engine.layout(source, MathLayoutOptions(MathMode.Inline, 48f))
        val x = nested.glyphAt(source.indexOf('x'))
        val y = nested.glyphAt(source.indexOf('y'))
        val z = nested.glyphAt(source.indexOf('z'))
        assertEquals(MathStyle.ScriptCramped, x.style, "$label fraction denominator is cramped")
        assertEquals(MathStyle.ScriptScriptCramped, y.style, "$label cramped superscript maps to SSc")
        assertEquals(MathStyle.ScriptScriptCramped, z.style, "$label nested superscript remains SSc")
        assertTrue(y.baselineY < x.baselineY && z.baselineY < y.baselineY, "$label nested superscript baselines rise")
        assertTrue(nested.debugDump.contains("superscriptStyle=ScriptScriptCramped"))
    }

    @Test
    fun scriptAndScriptScriptBinomialsUseFixedTargetsAndTextStyleDelimiterSelection() = withAuditFaces { label, face ->
        val size = 40f
        val cases = listOf(
            "inline-denominator" to "\\frac{a}{\\binom{n}{k}}",
            "script" to "\\scriptstyle{\\binom{n}{k}}",
            "scriptscript" to "\\scriptscriptstyle{\\binom{n}{k}}",
        )
        cases.forEach { (case, source) ->
            val result = MathLayoutEngine(face).layout(source, MathLayoutOptions(MathMode.Inline, size))
            assertFalse(
                result.diagnostics.any {
                    it.code == DiagnosticCode.MissingMathConstruction || it.code == DiagnosticCode.MathVariantTooShort
                },
                "$label/$case delimiter diagnostics: ${result.diagnostics}",
            )
            val delimiters = result.decisions.filter { it.name == "BinomialDelimiter" }
            assertEquals(2, delimiters.size, "$label/$case has two delimiter decisions")
            delimiters.forEach { decision ->
                assertAtLeast(
                    decision.details.getValue("achievedAdvancePx").toFloat(),
                    decision.details.getValue("targetPx").toFloat(),
                    "$label/$case reaches target",
                )
                assertTrue(decision.details.getValue("baseGlyphId").toInt() > 0)
                assertEquals("Text", decision.details["delimiterStyle"])
                assertEquals(size.toString(), decision.details["delimiterFontSizePx"])
                assertEquals("false", decision.details["stackCoverageRequired"])
            }
            val expectedFactor = if (case == "scriptscript") 1.35f else 1.45f
            assertTrue(delimiters.all { it.details["targetEmFactor"] == expectedFactor.toString() })
            assertTrue(result.decisions.any { it.name == "TeXBinomialFractionNoadPacking" })
        }

        if (label == "Lete Sans Math") {
            val scriptSize = size * face.mathFont.constants.scriptPercentScaleDown / 100f
            val styled = face.shape("(", scriptSize, MathStyle.ScriptCramped, SourceRange(0, 1)).glyphs.single().glyphId
            val coverageBase = face.shapeConstructionBase("(", size, SourceRange(0, 1)).glyphs.single().glyphId
            assertTrue(styled != coverageBase, "Lete regression fixture must exercise the ssty/base glyph distinction")
            val inline = MathLayoutEngine(face).layout("\\frac{a}{\\binom{n}{k}}", MathLayoutOptions(fontSizePx = size))
            val left = inline.decisions.first { it.name == "BinomialDelimiter" && it.details["side"] == "left" }
            assertEquals(coverageBase.toString(), left.details["baseGlyphId"])
        }
    }

    @Test
    fun veryTallBinomialContentDoesNotChangeTheFixedFractionDelimiter() = withAuditFaces { label, face ->
        var tallNumerator = "a"
        repeat(10) { tallNumerator = "\\frac{$tallNumerator}{b}" }
        val source = "\\binom{$tallNumerator}{k}"
        val engine = MathLayoutEngine(face)
        val result = engine.layout(source, MathLayoutOptions(MathMode.Display, 54f))
        val simple = engine.layout("\\binom{n}{k}", MathLayoutOptions(MathMode.Display, 54f))
        val decisions = result.decisions.filter { it.name == "BinomialDelimiter" }
        val simpleDecisions = simple.decisions.filter { it.name == "BinomialDelimiter" }
        assertEquals(2, decisions.size)
        assertEquals(simpleDecisions.map { it.details["construction"] }, decisions.map { it.details["construction"] })
        assertEquals(simpleDecisions.map { it.details["targetPx"] }, decisions.map { it.details["targetPx"] })
        assertTrue(decisions.all {
            it.details["stackCoverageRequired"] == "false" &&
                !(it.details["coversStackTop"] == "true" && it.details["coversStackBottom"] == "true")
        }, "$label $decisions")
        assertFalse(result.diagnostics.any { it.code == DiagnosticCode.MathVariantTooShort }, "$label fixed target is reached")
    }

    @Test
    fun delimiterCoverageFailureIsNeverSilent() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val font = delegate.mathFont.copy(
                verticalConstructions = emptyMap(),
            )
            val result = MathLayoutEngine(FontOverrideFace(delegate, font)).layout(
                "\\binom{n}{k}",
                MathLayoutOptions(MathMode.Inline, 40f),
            )
            assertEquals(2, result.diagnostics.count { it.code == DiagnosticCode.MissingMathConstruction })
            assertEquals(2, result.diagnostics.count { it.code == DiagnosticCode.MathVariantTooShort })
            assertTrue(result.debugDump.contains("diagnostic Error/MissingMathConstruction"))
            assertTrue(result.debugDump.contains("construction=BaseGlyph"))
            assertTrue(result.debugDump.contains("delimitedSubFormulaMinHeightUsed=false"))
        }
    }

    @Test
    fun mathKernTablesAffectScriptPlacementWithoutFontBranches() = withAuditFaces { label, face ->
        assertTrue(face.mathFont.mathKernInfo.isNotEmpty(), "$label exposes MATH MathKern")
        if (label == "STIX Two Math") {
            val uprightXGlyph = face.shape("x", 40f, MathStyle.Text, SourceRange(0, 1)).glyphs.single().glyphId
            assertTrue(
                face.mathFont.mathKernInfo[uprightXGlyph]?.bottomRight?.kernValues?.contains(35) == true,
                "STIX upright cmap x retains the audited 35 design-unit table",
            )
            val italicXGlyph = MathLayoutEngine(face).layout("x", MathLayoutOptions(fontSizePx = 40f))
                .box.glyphs.single().glyphId
            assertFalse(italicXGlyph == uprightXGlyph, "layout must query the final italic glyph ID")
        }

        val exercised = ('a'..'z').firstNotNullOfOrNull { character ->
            val source = "${character}_1^2"
            val result = MathLayoutEngine(face).layout(source, MathLayoutOptions(MathMode.Display, 64f))
            val nonZero = result.decisions.filter { it.name == "OpenTypeMathKern" }
                .any { abs(it.details.getValue("kernPx").toFloat()) > 0.001f }
            if (nonZero) source to result else null
        }
        val (source, withKern) = assertNotNull(exercised, "$label has a real glyph exercising MathKern")
        val withoutKernFace = FontOverrideFace(face, face.mathFont.copy(mathKernInfo = emptyMap()))
        val withoutKern = MathLayoutEngine(withoutKernFace).layout(source, MathLayoutOptions(MathMode.Display, 64f))
        val supOffset = source.indexOf('2')
        val subOffset = source.indexOf('1')
        val changed = abs(withKern.glyphAt(supOffset).x - withoutKern.glyphAt(supOffset).x) > 0.001f ||
            abs(withKern.glyphAt(subOffset).x - withoutKern.glyphAt(subOffset).x) > 0.001f
        assertTrue(changed, "$label parsed MathKern changes replayed script x")
        assertTrue(withKern.debugDump.contains("strategy=two-correction-heights"))
    }

    @Test
    fun extremeSyntheticCornerKernsAreConsumedAtEachGlyphScale() {
        SkiaMathFontFace(LeteSansMath.load()).use { delegate ->
            val size = 100f
            val scriptSize = size * delegate.mathFont.constants.scriptPercentScaleDown / 100f
            val xId = MathLayoutEngine(delegate).layout(
                "x",
                MathLayoutOptions(MathMode.Display, size),
            ).box.glyphs.single().glyphId
            val oneId = delegate.shape("1", scriptSize, MathStyle.ScriptCramped, SourceRange(0, 1)).glyphs.single().glyphId
            val twoId = delegate.shape("2", scriptSize, MathStyle.Script, SourceRange(0, 1)).glyphs.single().glyphId
            fun constant(value: Int) = MathKernTable(emptyList(), listOf(value))
            val kernInfo = mapOf(
                xId to MathGlyphKernInfo(constant(100), null, constant(200), null),
                oneId to MathGlyphKernInfo(null, constant(40), null, null),
                twoId to MathGlyphKernInfo(null, null, null, constant(30)),
            )
            val withFace = FontOverrideFace(delegate, delegate.mathFont.copy(mathKernInfo = kernInfo))
            val zeroFace = FontOverrideFace(delegate, delegate.mathFont.copy(mathKernInfo = emptyMap()))
            val with = MathLayoutEngine(withFace).layout("x_1^2", MathLayoutOptions(MathMode.Display, size))
            val zero = MathLayoutEngine(zeroFace).layout("x_1^2", MathLayoutOptions(MathMode.Display, size))
            assertNear(12.1f, with.glyphAt(4).x - zero.glyphAt(4).x, "TR base + BL superscript kern")
            assertNear(22.8f, with.glyphAt(2).x - zero.glyphAt(2).x, "BR base + TL subscript kern")
            assertTrue(with.debugDump.contains("superscriptKernPx=12.1"))
            assertTrue(with.debugDump.contains("subscriptKernPx=22.8"))
        }
    }

    @Test
    fun lineMetricsAndGlueAdjustmentAreExplicitForBothFonts() = withAuditFaces { label, face ->
        val equal = MathLayoutEngine(face).layout("=", MathLayoutOptions(fontSizePx = 40f))
        val metrics = equal.lineMetrics
        assertAtLeast(metrics.logicalAscentPx, metrics.fontAscentPx + metrics.fontLineGapPx, "$label safe ascent")
        assertAtLeast(metrics.logicalDescentPx, metrics.fontDescentPx, "$label safe descent")
        assertTrue(metrics.logicalDescentPx > equal.box.descent, "$label '=' line descent is not tight ink")
        assertTrue(equal.debugDump.contains("Os2TypographicMathLineExtents"))

        val result = MathLayoutEngine(face).layout("a,b=c+d+e+f", MathLayoutOptions(fontSizePx = 40f))
        val comma = result.fragments.first { it.sourceRange == SourceRange(1, 2) }.trailingGlue
        val relation = result.fragments.first { it.sourceRange == SourceRange(3, 4) }.trailingGlue
        val binary = result.fragments.first { it.sourceRange == SourceRange(5, 6) }.trailingGlue
        assertEquals(MathAdjustmentPriority.Punctuation, comma.priority)
        // Relation and punctuation spaces compress as well as stretch so an inline formula can be
        // justified inside a CJK line (deliberate deviation from shrink-free TeX thin/thick muskip).
        assertTrue(comma.stretchPx > 0f && comma.shrinkPx > 0f, "$label comma-after stretch and shrink")
        assertEquals(MathAdjustmentPriority.Relation, relation.priority)
        assertTrue(relation.maximumPx > relation.naturalPx && relation.minimumPx < relation.naturalPx, "$label relation stretch and shrink")
        assertEquals(MathAdjustmentPriority.BinaryOperator, binary.priority)
        assertTrue(binary.minimumPx < binary.naturalPx && binary.maximumPx > binary.naturalPx)

        val broken = result.breakIntoLines(result.box.visualWidth * 0.58f, MathLineAdjustmentMode.Justify)
        assertTrue(broken.lines.size > 1, "$label adjustment fixture breaks")
        broken.lines.forEach { line ->
            assertNear(0f, line.fragments.first().x, "$label no leading visible glue")
            assertNear(0f, line.fragments.last().resolvedTrailingAdvancePx, "$label no trailing visible glue")
            line.fragments.dropLast(1).forEach { placement ->
                val contract = result.fragments[placement.fragmentIndex].trailingGlue
                assertTrue(
                    placement.resolvedTrailingAdvancePx + 0.02f >= contract.minimumPx &&
                        placement.resolvedTrailingAdvancePx <= contract.maximumPx + 0.02f,
                    "$label resolved glue remains in contract",
                )
            }
            val replay = line.fragments.sumOf {
                val fragment = result.fragments[it.fragmentIndex]
                (fragment.box.width + fragment.trailingItalicCorrectionPx).toDouble()
            }.toFloat() +
                line.fragments.sumOf { it.resolvedTrailingAdvancePx.toDouble() }.toFloat()
            assertNear(replay, line.logicalWidth, "$label adjusted measure/draw source")
        }
    }
}

private class FontOverrideFace(
    private val delegate: SkiaMathFontFace,
    override val mathFont: OpenTypeMathFont,
) : MathFontFace {
    override fun resolveSymbol(request: MathSymbolGlyphRequest, fontSizePx: Float): ResolvedMathSymbol =
        delegate.resolveSymbol(request, fontSizePx)

    override fun resolveOperator(
        request: MathOperatorGlyphRequest,
        fontSizePx: Float,
    ): ResolvedMathOperator = delegate.resolveOperator(request, fontSizePx)

    override fun resolveSymbols(
        requests: List<MathSymbolGlyphRequest>,
        fontSizePx: Float,
    ): ResolvedMathSymbolRun = delegate.resolveSymbols(requests, fontSizePx)

    override fun shape(text: String, fontSizePx: Float, style: MathStyle, sourceRange: SourceRange): MeasuredMathRun =
        delegate.shape(text, fontSizePx, style, sourceRange)

    override fun measureGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.measureGlyph(glyphId, fontSizePx, style, sourceRange)

    override fun measureGlyphOutlineBounds(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredMathRun = delegate.measureGlyphOutlineBounds(glyphId, fontSizePx, style, sourceRange)

    override fun shapeOutlineConstructionBase(
        text: String,
        fontSizePx: Float,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun = delegate.shapeOutlineConstructionBase(text, fontSizePx, sourceRange)

    override fun measureOutlineConstructionGlyph(
        glyphId: UShort,
        fontSizePx: Float,
        style: MathStyle,
        sourceRange: SourceRange,
    ): MeasuredOutlineConstructionRun =
        delegate.measureOutlineConstructionGlyph(glyphId, fontSizePx, style, sourceRange)
}

private inline fun withAuditFaces(block: (String, SkiaMathFontFace) -> Unit) {
    listOf(
        "Lete Sans Math" to LeteSansMath.load(),
        "STIX Two Math" to StixTwoMath.load(),
    ).forEach { (label, font) -> SkiaMathFontFace(font).use { block(label, it) } }
}

private fun MathLayoutResult.spacingDecision(range: SourceRange): MathLayoutDecision =
    decisions.first { it.name == "TeXMathAtomSpacing" && it.range == range }

private fun MathLayoutResult.glyphAt(sourceOffset: Int) =
    box.glyphs.first { sourceOffset in it.sourceRange.start until it.sourceRange.endExclusive }

private fun assertAtLeast(actual: Float, minimum: Float, message: String) {
    assertTrue(actual + 0.03f >= minimum, "$message: expected >= $minimum, got $actual")
}

private fun assertNear(expected: Float, actual: Float, message: String) {
    assertTrue(abs(expected - actual) <= 0.04f, "$message: expected $expected, got $actual")
}
