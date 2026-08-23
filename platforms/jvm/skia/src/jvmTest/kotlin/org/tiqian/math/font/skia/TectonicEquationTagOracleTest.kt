package org.tiqian.math.font.skia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions

/** Reproducer: `preview/tectonic/equation-tag-oracle-{lete,stix}.tex`. */
class TectonicEquationTagOracleTest {
    @Test
    fun currentZhihuAnswerRowsAndBoxedTerminalSeparatorRemainResponsivelyLayoutable() {
        val sources = listOf(
            Triple("35", "TaggedDisplayBodyLineBreak", "\\arg \\Omega_k^+(t)=\\tan^{-1}\\left[\\frac{2(-1)^{k+1}tC(t)\\left[e+(-1)^k\\frac{\\pi}{2}\\left| t \\right| \\right]}{\\left[e+(-1)^k\\frac{\\pi}{2}\\left| t \\right|\\right]^2-t^2[M-\\pi\\Delta(k)]^2-t^2C^2(t)}\\right] \\tag{35}\\\\"),
            Triple("36", "TaggedDisplayBodyLineBreak", "E=M-e(M-\\pi)[e^2\\Omega_0(iy)E_0^2(iy)-y^2(M-\\pi)^2]^{-1/2},\\{e,M\\}\\in R_2 \\tag{36}\\\\"),
            Triple("38", "BoxedResponsiveDisplayLineBreak", "\\boxed{E=M-e(M-\\pi)\\left[(e+1)^2-(M-\\pi)^2\\frac{2}{\\pi}\\int_0^1t\\arg \\Omega_0^+(t)\\mathrm{d}t\\right]^{-1/2},\\{e,M\\}\\in R_2 \\tag {38}}"),
            Triple("43", "TaggedDisplayBodyLineBreak", "D_k(\\alpha,\\beta,\\gamma)=\\frac{1}{6}\\left[A_{1k}(\\alpha,\\beta,\\gamma)A_{2k}(\\alpha,\\beta,\\gamma)-3A_{0k}(\\alpha,\\beta,\\gamma)\\right]-[\\frac{1}{3}A_{2k}(\\alpha,\\beta,\\gamma)]^3 \\tag{43}\\\\"),
            Triple("48", "TaggedDisplayBodyLineBreak", "E=M+e(2-k)\\left[S_{1k}(0,1,2)+S_{2k}(0,1,2)-\\frac{1}{3}A_{2k}(0,1,2)\\right]^{-1/2},\\\\ \\{e,M\\}\\in R_k,k=1 \\ or\\ 3 \\tag{48}\\\\"),
            Triple("49", "BoxedResponsiveDisplayLineBreak", "\\boxed{E=M+e(2-k)\\left[S_{1k}+S_{2k}-\\frac{1}{3}A_{2k}\\right]^{-1/2}, \\{e,M\\}\\in R_k,k=1 \\ or\\ 3 \\tag{49}\\\\}"),
        )
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("zhihu-responsive-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    sources.forEach { (label, breakDecisionName, source) ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = 48f,
                            displayWidthPx = 1248f,
                            softWrapDisplay = true,
                        ),
                    )
                        assertTrue(
                            result.diagnostics.isEmpty(),
                            "${oracle.label}/$label: ${result.diagnostics}",
                        )
                        val wrapping = result.decisions.single { it.name == breakDecisionName }
                        assertEquals(
                            "MinimumCostOverLegalBreaksAtResolvedSharedIndent",
                            wrapping.details["selection"],
                            "${oracle.label}/$label",
                        )
                        assertEquals("0", wrapping.details["overfullLineCount"], "${oracle.label}/$label")
                        val replay = assertNotNull(result.taggedDisplayReplay)
                        assertTrue(
                            replay.body.visualRight <= replay.viewportWidthPx + 0.02f,
                            "${oracle.label}/$label body=${replay.body.visualRight} viewport=${replay.viewportWidthPx}",
                        )
                        val tag = replay.tags.single()
                        assertTrue(tag.box.glyphs.isNotEmpty(), "${oracle.label}/$label tag")
                        when (label) {
                            "36" -> {
                                assertEquals("1", wrapping.details["lineCount"], "${oracle.label}/$label")
                                val bodyBottom = maxOf(replay.body.descent, replay.body.inkBounds.bottom)
                                val tagTop = tag.baselineY - maxOf(tag.box.ascent, -tag.box.inkBounds.top)
                                assertEquals(24f, tagTop - bodyBottom, 0.02f, "${oracle.label}/$label")
                                val separation = result.decisions.single {
                                    it.name == "ShiftedEquationTagVerticalSeparation"
                                }
                                assertEquals("true", separation.details["responsiveElectronicBody"])
                                assertEquals("false", separation.details["responsiveMultilineBody"])
                            }

                            "38" -> {
                                // The boxed equation breaks at its only clean boundary, the
                                // top-level comma, keeping the whole product intact on line one
                                // and right-aligning the domain condition clause below it.
                                assertEquals("2", wrapping.details["lineCount"], "${oracle.label}/$label")
                                assertEquals("7..108;108..122", wrapping.details["lineSourceRanges"])
                                assertEquals(
                                    "PunctuationTrailing",
                                    wrapping.details["continuationBreakKinds"],
                                )
                                assertEquals(
                                    "ClauseContinuationRightAligned",
                                    wrapping.details["clausePlacement"],
                                )
                            }

                            "48" -> {
                                // The trailing comma is a clause junction: the author rows rejoin
                                // into one formula whose condition becomes a responsive clause.
                                val rowsDecision = result.decisions.single {
                                    it.name == "MarkdownExplicitDisplayRows"
                                }
                                assertEquals("2", rowsDecision.details["authorRowCount"], "${oracle.label}/$label")
                                assertEquals("1", rowsDecision.details["rowCount"], "${oracle.label}/$label")
                                assertEquals(
                                    "OperatorJunctionRowsRejoin",
                                    rowsDecision.details["rowJoinPolicy"],
                                    "${oracle.label}/$label",
                                )
                            }

                            "49" -> {
                                val frameRules = replay.body.rules.filter { it.sourceRange == SourceRange(0, 6) }
                                assertEquals(4, frameRules.size, "${oracle.label}/$label frame")
                                val frameBottom = frameRules.maxOf { it.bottom }
                                val tagInkTop = tag.baselineY + tag.box.inkBounds.top
                                assertTrue(
                                    tagInkTop - frameBottom >= 24f - 0.02f,
                                    "${oracle.label}/$label frameBottom=$frameBottom tagInkTop=$tagInkTop",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun taggedRowInsideMultiRowDisplayWrapsInsteadOfOverflowing() {
        // Equation 45a shape: author-broken rows with operator junctions and the tag on the
        // widest row. The rows rejoin into one formula (OperatorJunctionRowsRejoin), re-break
        // responsively for the narrow viewport, and the tag clears the completed body.
        val source = "A_{0k}(\\alpha,\\beta,\\gamma)=\\alpha^2\\beta^2\\gamma^2\\\\" +
            "+\\beta^2\\gamma^2(\\beta^2-\\gamma^2)TF_k(i\\alpha)+\\\\" +
            "\\gamma^2\\alpha^2(\\gamma^2-\\alpha^2)TF_k(i\\beta)+" +
            "\\alpha^2\\beta^2(\\alpha^2-\\beta^2)TF_k(i\\gamma)\\tag{45a}\\\\"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("tagged-row-wrap-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = 48f,
                            displayWidthPx = 416f,
                            softWrapDisplay = true,
                        ),
                    )
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    val wrapping = result.decisions.single { it.name == "TaggedDisplayBodyLineBreak" }
                    assertTrue(
                        wrapping.details.getValue("lineCount").toInt() >= 2,
                        "${oracle.label}: tagged row must wrap",
                    )
                    assertEquals("0", wrapping.details["overfullLineCount"], oracle.label)
                    val rowsDecision = result.decisions.single { it.name == "MarkdownExplicitDisplayRows" }
                    assertEquals("3", rowsDecision.details["authorRowCount"], oracle.label)
                    assertEquals("1", rowsDecision.details["rowCount"], oracle.label)
                    assertEquals(
                        "OperatorJunctionRowsRejoin",
                        rowsDecision.details["rowJoinPolicy"],
                        oracle.label,
                    )
                    val replay = assertNotNull(result.taggedDisplayReplay)
                    val tag = replay.tags.single()
                    assertEquals(MathEquationTagPlacement.ShiftedBelowRight, tag.placement)
                    val bodyBottom = maxOf(replay.body.descent, replay.body.inkBounds.bottom)
                    val tagInkTop = tag.baselineY + tag.box.inkBounds.top
                    assertTrue(
                        tagInkTop >= bodyBottom + 24f - 0.02f,
                        "${oracle.label}: tag must clear the completed body " +
                            "(bodyBottom=$bodyBottom tagInkTop=$tagInkTop)",
                    )
                }
            }
        }
    }

    @Test
    fun nestedRadicandNeverBreaksInsideAFunctionArgumentList() {
        // Equation 42 at the phone ratio: the DP once bought a depth-2 argument-list comma with
        // the raggedness saved by fuller lines, splitting "D_k(alpha,beta," / "gamma)". Fenced
        // boundaries must never be purchasable by line balance — only depth-0 relations and
        // binaries may break here.
        val source = "S_{jk}(\\alpha,\\beta,\\gamma)=[D_k(\\alpha,\\beta,\\gamma)-(-1)^j" +
            "\\left[D_k^2(\\alpha,\\beta,\\gamma)+Q_k^3(\\alpha,\\beta,\\gamma)\\right]^{1/2}]^{1/3}" +
            "\\tag{42}\\\\"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("fence-lock-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                val result = MathLayoutEngine(face, textRunProvider = text).layout(
                    source,
                    MathLayoutOptions(
                        mode = MathMode.Display,
                        fontSizePx = 60f,
                        displayWidthPx = 1248f,
                        softWrapDisplay = true,
                    ),
                )
                assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                val wrapping = result.decisions.single { it.name == "TaggedDisplayBodyLineBreak" }
                assertTrue(
                    wrapping.details.getValue("lineCount").toInt() >= 2,
                    "${oracle.label}: must wrap at this ratio",
                )
                assertEquals("0", wrapping.details["overfullLineCount"], oracle.label)
                val kinds = wrapping.details.getValue("continuationBreakKinds")
                    .split(",").filter { it.isNotEmpty() }
                assertTrue(
                    kinds.all { it == "RelationLeading" || it == "BinaryOperatorLeading" },
                    "${oracle.label}: fenced boundaries must not be chosen, got $kinds",
                )
                }
            }
        }
    }

    @Test
    fun untaggedBoxedFieldKeepsItsClauseInTheScrolledFrame() {
        // Without a tagged completion there is no consumer to anchor a pinned clause, so the
        // clause must stay in the frame's scrolled body — never silently vanish from paint.
        val source = "\\boxed{E=M-e(M-\\pi)\\left[(e+1)^2-(M-\\pi)^2\\frac{2}{\\pi}" +
            "\\int_0^1t\\arg \\Omega_0^+(t)\\mathrm{d}t\\right]^{-1/2},\\{e,M\\}\\in R_2}"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                val result = MathLayoutEngine(face).layout(
                    source,
                    MathLayoutOptions(
                        mode = MathMode.Display,
                        fontSizePx = 60f,
                        displayWidthPx = 416f,
                        softWrapDisplay = true,
                    ),
                )
                assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                val clauseStart = source.indexOf("\\{e,M\\}")
                assertTrue(clauseStart > 0)
                assertTrue(
                    result.box.glyphs.any { it.sourceRange.start >= clauseStart },
                    "${oracle.label}: the clause must be painted inside the frame",
                )
            }
        }
    }

    @Test
    fun overwideSingleRowAlignmentFlattensIntoResponsiveLines() {
        // Real corpus: a one-row align whose & only fences the chain into unbreakable cells.
        // The cells must flatten and break at the top-level relations instead of scrolling.
        val source = "\\begin{align}I=\\int\\frac{{\\rm d}x}{y^{5}} &=\\int\\frac{y^{4}-x^{4}}{y^{5}}{\\rm d}x " +
            "=\\int\\frac{y^{4}{\\rm d}x-x\\cdot \\color{red}{x^{3}{\\rm d}x}}{y^{5}} " +
            "=\\int\\frac{y{\\rm d}x-x\\color{red}{{\\rm d}y}}{y^{2}} " +
            "=\\frac{x}{y}=\\bbox[#fc5,7px]{\\frac{x}{\\sqrt[4]{x^{4}+1}}+C} \\end{align}"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                val result = MathLayoutEngine(face).layout(
                    source,
                    MathLayoutOptions(
                        mode = MathMode.Display,
                        fontSizePx = 60f,
                        displayWidthPx = 1248f,
                        softWrapDisplay = true,
                    ),
                )
                assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                val wrapping = result.decisions.single { it.name == "SingleRowAlignmentLineBreak" }
                assertTrue(
                    wrapping.details.getValue("lineCount").toInt() >= 2,
                    "${oracle.label}: the flattened chain must break responsively",
                )
                assertEquals("0", wrapping.details["overfullLineCount"], oracle.label)
                val kinds = wrapping.details.getValue("continuationBreakKinds")
                    .split(",").filter { it.isNotEmpty() }
                assertTrue(
                    kinds.all { it == "RelationLeading" },
                    "${oracle.label}: expected align-at-relation breaks, got $kinds",
                )
            }
        }
    }

    @Test
    fun parallelEquationRowsWithoutOperatorJunctionAreNeverRejoined() {
        val source = "a=b\\\\c=d\\\\"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                val result = MathLayoutEngine(face).layout(
                    source,
                    MathLayoutOptions(
                        mode = MathMode.Display,
                        fontSizePx = 48f,
                        displayWidthPx = 416f,
                        softWrapDisplay = true,
                    ),
                )
                assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                val rowsDecision = result.decisions.single { it.name == "MarkdownExplicitDisplayRows" }
                assertEquals("2", rowsDecision.details["authorRowCount"], oracle.label)
                assertEquals("2", rowsDecision.details["rowCount"], oracle.label)
                assertEquals("AuthorRowsPreserved", rowsDecision.details["rowJoinPolicy"], oracle.label)
                // Preserved display rows share the responsive DisplayRowJot inter-row leading.
                val table = result.decisions.single { it.name == "TeXMathTable" }
                assertEquals("DisplayRowJotInterRowGlue", table.details["rowSpacingPolicy"], oracle.label)
                assertEquals("0.3", table.details["rowGapEm"], oracle.label)
            }
        }
    }

    @Test
    fun singleDisplayTagMatchesSameFontTectonicBodyCenterAndRightEdge() {
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("tectonic-tag-text-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        "x+y\\tag{1}",
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = DISPLAY_WIDTH_PX,
                        ),
                    )
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    val decision = result.decisions.single { it.name == "AmsmathEquationTag" }
                    assertEquals(DISPLAY_WIDTH_PX, result.box.width, EPSILON_PX)
                    assertEquals(oracle.bodyWidthPx, decision.float("bodyWidthPx"), EPSILON_PX)
                    assertEquals(oracle.bodyX, decision.float("bodyX"), EPSILON_PX)
                    assertEquals(oracle.tagWidthPx, decision.float("tagWidthPx"), EPSILON_PX)
                    assertEquals(DISPLAY_WIDTH_PX - oracle.tagWidthPx, decision.float("tagX"), EPSILON_PX)
                    assertEquals(0f, decision.float("tagBaselineY"), 0.001f)
                    assertEquals("AmsmathDisplayBodyCenteredTagRightAlignedAtHostDisplayWidth", decision.details["policy"])
                    val tagGlyphs = result.box.glyphs.filter {
                        it.hostTextDecision?.hostRole == MathTextOrigin.EquationTag.name
                    }
                    assertTrue(tagGlyphs.isNotEmpty())
                    assertEquals(DISPLAY_WIDTH_PX, tagGlyphs.maxOf { it.x + it.advance }, EPSILON_PX)
                    assertTrue(tagGlyphs.any { it.sourceRange == SourceRange(8, 9) }, "content digit keeps its source range")
                }
            }
        }
    }

    @Test
    fun missingWidthAndInlineTagAreFormulaWideCapabilityErrors() {
        SkiaMathFontFace(LeteSansMath.load()).use { face ->
            SkiaMathTextRunProvider.fromBytes(MathFaceId("tag-text-errors"), LeteSansMath.loadBytes()).use { text ->
                val engine = MathLayoutEngine(face, textRunProvider = text)
                val missingWidth = engine.layout("x\\tag{1}", MathLayoutOptions(mode = MathMode.Display, fontSizePx = 32f))
                assertTrue(missingWidth.diagnostics.any { it.code == DiagnosticCode.MissingEquationTagDisplayWidth })
                val inline = engine.layout(
                    "x\\tag{1}",
                    MathLayoutOptions(mode = MathMode.Inline, fontSizePx = 32f, displayWidthPx = 400f),
                )
                assertTrue(inline.diagnostics.any { it.code == DiagnosticCode.MisplacedEquationTag })
            }
        }
    }

    @Test
    fun alignmentRowsShareTheDisplayRightEdgeAndKeepTectonicBaselineDistance() {
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("tectonic-align-tag-text-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        "\\begin{align*}a&=b\\tag{1}\\\\c&=d\\tag{2}\\end{align*}",
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = DISPLAY_WIDTH_PX,
                        ),
                    )
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    val tags = result.decisions.filter { it.name == "AmsmathEquationTag" }
                    assertEquals(2, tags.size)
                    assertEquals(tags[0].float("tagX"), tags[1].float("tagX"), 0.01f)
                    assertEquals(
                        oracle.alignBaselineDistancePx,
                        tags[1].float("tagBaselineY") - tags[0].float("tagBaselineY"),
                        EPSILON_PX,
                    )
                    assertEquals(DISPLAY_WIDTH_PX, result.box.width, 0.01f)
                }
            }
        }
    }

    @Test
    fun overfullSingleEquationKeepsItsBodyAndMovesTheTagToTheNextDisplayLine() {
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("tectonic-narrow-tag-text-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        "x+y\\tag{1}",
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = NARROW_DISPLAY_WIDTH_PX,
                        ),
                    )
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    val fit = result.decisions.single { it.name == "AmsmathEquationTagFit" }
                    val tag = result.decisions.single { it.name == "AmsmathEquationTag" }
                    assertEquals("false", fit.details["fits"])
                    assertEquals("ShiftedBelowRight", fit.details["placement"])
                    assertEquals("ShiftedBelowRight", tag.details["placement"])
                    assertEquals((NARROW_DISPLAY_WIDTH_PX - oracle.bodyWidthPx) / 2f, tag.float("bodyX"), EPSILON_PX)
                    assertEquals(NARROW_DISPLAY_WIDTH_PX - oracle.tagWidthPx, tag.float("tagX"), EPSILON_PX)
                    // Tectonic 0.17 / XeTeX uses one 24.09pt baseline skip here: 32px nominal size.
                    assertEquals(
                        32f,
                        tag.float("tagBaselineY"),
                        EPSILON_PX,
                        "tag=${tag.details} body=${result.taggedDisplayReplay?.body}",
                    )
                    assertEquals(NARROW_DISPLAY_WIDTH_PX, result.box.width, 0.01f)
                    assertTrue(result.box.descent > tag.float("tagBaselineY"))
                    val replay = assertNotNull(result.taggedDisplayReplay)
                    assertEquals(MathEquationTagPlacement.ShiftedBelowRight, replay.tags.single().placement)
                    assertTrue(replay.body.glyphs.none {
                        it.hostTextDecision?.hostRole == MathTextOrigin.EquationTag.name
                    })
                    assertTrue(replay.tags.single().box.glyphs.any {
                        it.hostTextDecision?.hostRole == MathTextOrigin.EquationTag.name
                    })
                    assertTrue(result.debugDump.contains("equationTagReplay[0]"))
                }
            }
        }
    }

    @Test
    fun horizontallyScrollableBodyKeepsHalfEmCompletedBoxGapBeforeItsFixedTag() {
        val source =
            "E=M+e(2-k)\\left[S_{1k}+S_{2k}-\\frac{1}{3}A_{2k}\\right]^{-1/2}," +
                " \\{e,M\\}\\in R_k,k=1 \\operatorname{or} 3\\tag{49}"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("scrolling-tag-gap-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = 416f,
                            softWrapDisplay = false,
                        ),
                    )

                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    val replay = assertNotNull(result.taggedDisplayReplay)
                    assertTrue(replay.body.visualWidth > replay.viewportWidthPx)
                    val tag = replay.tags.single()
                    val bodyCompletedBottom = maxOf(replay.body.descent, replay.body.inkBounds.bottom)
                    val tagCompletedAscent = maxOf(tag.box.ascent, -tag.box.inkBounds.top)
                    val completedBoxGap = tag.baselineY - tagCompletedAscent - bodyCompletedBottom
                    val decision = result.decisions.single {
                        it.name == "ShiftedEquationTagVerticalSeparation"
                    }
                    assertEquals("true", decision.details["horizontallyScrollableBody"])
                    assertEquals(FONT_SIZE_PX / 2f, decision.float("minimumSeparationPx"), 0.001f)
                    assertEquals(FONT_SIZE_PX / 2f, completedBoxGap, 0.001f)
                    assertEquals(
                        completedBoxGap,
                        decision.float("resolvedCompletedBoxGapPx"),
                        0.001f,
                    )
                    assertEquals(
                        "CompletedBodyToShiftedTagHalfEmMinimumWithOneEmBaselineFloor",
                        decision.details["policy"],
                    )
                }
            }
        }
    }

    @Test
    fun terminalRowSeparatorDoesNotTurnAWidthCollisionIntoFormulaFallback() {
        val source =
            "\\sin^{-1}(1/z)=k\\pi +(-1)^k\\left[\\frac{\\pi}{2}-i \\log " +
                "\\left[f(z)+\\frac{1}{z}\\right]\\right],k=0,\\pm1,\\pm2,...\\tag{5}\\\\"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("terminal-row-tag-text-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = 48f,
                            displayWidthPx = 1152f,
                            softWrapDisplay = true,
                        ),
                    )

                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    assertTrue(result.decisions.any { it.name == "MarkdownExplicitDisplayRows" })
                    assertEquals(
                        MathEquationTagPlacement.ShiftedBelowRight,
                        assertNotNull(result.taggedDisplayReplay).tags.single().placement,
                    )
                }
            }
        }
    }

    @Test
    fun taggedDisplayBreaksAtLegalOperatorsBeforeLeavingHorizontalOverflow() {
        val source = "a+b+c+d+e+f+g+h+i+j\\tag{9}"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("soft-wrapped-tag-text-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val wrapped = MathLayoutEngine(face, textRunProvider = text).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = 180f,
                            softWrapDisplay = true,
                        ),
                    )
                    assertTrue(wrapped.diagnostics.isEmpty(), "${oracle.label}: ${wrapped.diagnostics}")
                    val breakDecision = wrapped.decisions.single { it.name == "TaggedDisplayBodyLineBreak" }
                    assertTrue(breakDecision.details.getValue("lineCount").toInt() > 1)
                    assertEquals(0, breakDecision.details.getValue("overfullLineCount").toInt())
                    val replay = assertNotNull(wrapped.taggedDisplayReplay)
                    assertEquals(180f, replay.body.width, 0.01f)
                    assertTrue(replay.body.visualLeft >= -0.01f)
                    assertTrue(replay.body.visualRight <= 180.01f)
                    val tag = replay.tags.single()
                    assertEquals(MathEquationTagPlacement.ShiftedBelowRight, tag.placement)
                    assertTrue(tag.baselineY > FONT_SIZE_PX)

                    val preserved = MathLayoutEngine(face, textRunProvider = text).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = 180f,
                            softWrapDisplay = false,
                        ),
                    )
                    assertTrue(preserved.decisions.none { it.name == "TaggedDisplayBodyLineBreak" })
                    assertTrue(assertNotNull(preserved.taggedDisplayReplay).body.visualWidth > 180f)
                }
            }
        }
    }

    @Test
    fun outermostBoxedTagIsPromotedAndItsMathFieldWrapsInsideOneCompletedFrame() {
        val source = "\\boxed{E=a+b+c+d+e+f+g+h+i+j\\tag{12}}"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("boxed-tag-text-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = 180f,
                            softWrapDisplay = true,
                        ),
                    )

                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    val wrapping = result.decisions.single { it.name == "BoxedResponsiveDisplayLineBreak" }
                    assertTrue(wrapping.details.getValue("lineCount").toInt() > 1, oracle.label)
                    assertEquals(0, wrapping.details.getValue("overfullLineCount").toInt(), oracle.label)
                    assertEquals("SingleFrameAroundCompletedMultilineMathField", wrapping.details["framePolicy"])

                    val replay = assertNotNull(result.taggedDisplayReplay)
                    val frameRules = replay.body.rules.filter { it.sourceRange == SourceRange(0, 6) }
                    assertEquals(4, frameRules.size, oracle.label)
                    assertEquals(MathEquationTagPlacement.ShiftedBelowRight, replay.tags.single().placement)
                    assertTrue(replay.body.glyphs.none {
                        it.hostTextDecision?.hostRole == MathTextOrigin.EquationTag.name
                    }, oracle.label)
                    assertTrue(replay.tags.single().box.glyphs.any {
                        it.hostTextDecision?.hostRole == MathTextOrigin.EquationTag.name
                    }, oracle.label)
                }
            }
        }
    }

    @Test
    fun splitBodyKeepsTheEquationTagOnTheCompletedMultilineBoxCenterBaseline() {
        val source = "\\begin{equation*}\\begin{split}a&=b\\\\c&=\\frac{d}{e}\\end{split}\\tag{7}\\end{equation*}"
        oracles.forEach { oracle ->
            SkiaMathFontFace(oracle.mathFont).use { face ->
                SkiaMathTextRunProvider.fromBytes(
                    MathFaceId("split-tag-text-${oracle.label}"),
                    oracle.bytes,
                ).use { text ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout(
                        source,
                        MathLayoutOptions(
                            mode = MathMode.Display,
                            fontSizePx = FONT_SIZE_PX,
                            displayWidthPx = DISPLAY_WIDTH_PX,
                        ),
                    )
                    assertTrue(result.diagnostics.isEmpty(), "${oracle.label}: ${result.diagnostics}")
                    val tag = result.decisions.single { it.name == "AmsmathEquationTag" }
                    assertEquals("SingleDisplayEnvironment", tag.details["layoutRole"])
                    assertEquals(0f, tag.float("tagBaselineY"), 0.001f)
                    assertEquals("CenteredBesideMultiline", tag.details["placement"])
                    assertEquals(
                        MathEquationTagPlacement.CenteredBesideMultiline,
                        assertNotNull(result.taggedDisplayReplay).tags.single().placement,
                    )
                    assertTrue(result.decisions.any {
                        it.name == "TeXMathTable" && it.details["environmentName"] == "split"
                    })
                }
            }
        }
    }

    private fun MathLayoutDecision.float(name: String): Float = checkNotNull(details[name]).toFloat()

    private data class Oracle(
        val label: String,
        val mathFont: org.tiqian.math.font.opentype.OpenTypeMathFont,
        val bytes: ByteArray,
        val bodyWidthPx: Float,
        val bodyX: Float,
        val tagWidthPx: Float,
        val alignBaselineDistancePx: Float,
    )

    private companion object {
        const val FONT_SIZE_PX = 32f
        const val DISPLAY_WIDTH_PX = 400f
        const val NARROW_DISPLAY_WIDTH_PX = 80.3f
        const val EPSILON_PX = 0.3f

        val oracles = listOf(
            Oracle(
                "lete", LeteSansMath.load(), LeteSansMath.loadBytes(),
                71.08769f, 164.45618f, 38.72f, 38.65045f,
            ),
            Oracle(
                "stix", StixTwoMath.load(), StixTwoMath.loadBytes(),
                72.27169f, 163.86418f, 38.68799f, 38.45859f,
            ),
        )
    }
}
