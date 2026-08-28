package org.tiqian.math.font.skia

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tiqian.math.core.*
import org.tiqian.math.font.opentype.LeteSansMath
import org.tiqian.math.font.stix.StixTwoMath
import org.tiqian.math.layout.MathFormulaCapabilityResult
import org.tiqian.math.layout.MathFontFace
import org.tiqian.math.layout.MathLayoutEngine
import org.tiqian.math.layout.MathLayoutOptions
import org.tiqian.math.layout.MathSymbolGlyphRequest
import org.tiqian.math.layout.ResolvedMathSymbolWithRequiredGlyph
import org.tiqian.math.layout.constructionPaintOwnershipDiagnostics

/** Reproducer: `preview/tectonic/remaining-command-oracle.tex`, Tectonic 0.17.0 at 24bp. */
class TectonicRemainingCommandsOracleTest {
    @Test
    fun remainingCommandsPinSameFontXeTeXBoxesAndKnownSstyBoundary() = withFaces { oracle, engine ->
        val hline = engine.layout("\\begin{array}{c}a\\\\\\hline b\\end{array}", options())
        assertBox(oracle.hline, hline, "${oracle.label} hline")
        val rule = hline.box.rules.single { it.sourceRange == SourceRange(19, 25) }
        assertNear(DEFAULT_RULE_PX, rule.bottom - rule.top, "${oracle.label} hline thickness")
        assertNear(0f, rule.left, "${oracle.label} hline left")
        assertNear(hline.box.width, rule.right, "${oracle.label} hline right")

        val atop = engine.layout("{a\\atop b}", options())
        assertNear(oracle.atopWidthPx, atop.box.width, "${oracle.label} atop width")
        if (oracle.atopGlyphIds == null) {
            assertNear(oracle.atop.ascentPt * TEX_PT_TO_PX, atop.box.ascent, "${oracle.label} atop ascent")
            assertNear(oracle.atop.descentPt * TEX_PT_TO_PX, atop.box.descent, "${oracle.label} atop descent")
        } else {
            // XeTeX does not apply STIX's optional ssty substitutions here. Tiqian's established
            // OpenType style contract does, so pin that explicit backend divergence separately
            // while the generalized-fraction kernel remains shared with the reviewed fraction tests.
            assertEquals(oracle.atopGlyphIds, atop.box.glyphs.map { it.glyphId })
            assertTrue(atop.box.glyphs.all { abs(it.fontSizePx - 22.4f) <= 0.001f })
        }
        assertEquals("GeneralizedAtop", atop.decisions.single {
            it.name == "TeXFractionCommand"
        }.details["origin"])

        val choose = engine.layout("{a\\choose b}", options())
        // XeTeX does not apply STIX's optional ssty alternates to the fraction children,
        // while Tiqian's established OpenType style contract does. The external oracle still
        // pins the delimiter glyphs and full vertical box; chooseEngineWidthPx records that existing
        // child-run divergence rather than weakening the primitive delimiter assertions.
        assertNear(oracle.chooseEngineWidthPx, choose.box.width, "${oracle.label} choose width")
        assertNear(oracle.choose.ascentPt * TEX_PT_TO_PX, choose.box.ascent, "${oracle.label} choose ascent")
        assertNear(oracle.choose.descentPt * TEX_PT_TO_PX, choose.box.descent, "${oracle.label} choose descent")
        assertEquals(
            oracle.chooseDelimiterGlyphIds,
            listOf(choose.box.glyphs.first().glyphId, choose.box.glyphs.last().glyphId),
            "${oracle.label} choose delimiter glyphs",
        )
        assertEquals("GeneralizedChoose", choose.decisions.single {
            it.name == "TeXFractionCommand"
        }.details["origin"])
        assertEquals(2, choose.decisions.count { it.name == "GeneralizedChooseDelimiter" })
        assertTrue(choose.decisions.filter { it.name == "GeneralizedChooseDelimiter" }.all {
            it.details["chooseTargetParameter"] == "delim2" &&
                it.details["delimiterAxisPolicy"] == "XeTeXCurrentMathSizeVarDelimiterAxis"
        })
        assertTrue(choose.box.rules.isEmpty(), "${oracle.label} choose must be ruleless")
        assertTrue(
            choose.decisions.none { it.name == "TeXFractionNullDelimiters" },
            "${oracle.label} primitive choose must not synthesize null delimiter boxes",
        )
        val chooseCommandRange = SourceRange(2, 9)
        val delimiterGroups = choose.box.constructionPaintGroups.filter {
            it.kind == MathConstructionPaintKind.Delimiter
        }
        assertEquals(2, delimiterGroups.size, "${oracle.label} choose delimiter paint ownership")
        assertTrue(delimiterGroups.all { it.sourceRange == chooseCommandRange })
        assertTrue(choose.box.constructionPaintOwnershipDiagnostics().isEmpty(), choose.debugDump)
        assertTrue(
            choose.box.glyphs.filter { it.constructionGroupId != null }.all {
                it.sourceRange == chooseCommandRange
            },
            choose.debugDump,
        )

        val displayChoose = engine.layout("\\displaystyle{a\\choose b}", options())
        assertBox(oracle.chooseDisplay, displayChoose, "${oracle.label} display choose")
        assertEquals(
            oracle.chooseDelimiterGlyphIds,
            listOf(displayChoose.box.glyphs.first().glyphId, displayChoose.box.glyphs.last().glyphId),
            "${oracle.label} display choose delimiter glyphs",
        )
        assertTrue(displayChoose.decisions.filter { it.name == "GeneralizedChooseDelimiter" }.all {
            it.details["chooseTargetParameter"] == "delim1" &&
                it.details["delimitedSubFormulaMinHeightUsed"] == "true"
        })

        val not = engine.layout("\\mu\\not\\equiv\\mu", options())
        assertNear(oracle.not.widthPt * TEX_PT_TO_PX, not.box.width, "${oracle.label} not width")
        val negated = not.box.glyphs.single { it.sourceRange == SourceRange(3, 13) }
        assertEquals(oracle.notGlyphId, negated.glyphId, oracle.label)
        assertEquals("U+2262", not.decisions.single { it.name == "TeXNotRelation" }.details["precomposedScalar"])

        val overlay = engine.layout("\\not p", options())
        assertBox(oracle.notOverlay, overlay, "${oracle.label} not overlay")
        assertEquals(oracle.notOverlayGlyphIds, overlay.box.glyphs.map { it.glyphId }, overlay.debugDump)
        val overlaySlash = overlay.box.glyphs.single { it.sourceRange == SourceRange(0, 4) }
        val overlayBase = overlay.box.glyphs.single { it.sourceRange == SourceRange(5, 6) }
        assertNear(oracle.notOverlayXPt * TEX_PT_TO_PX, overlaySlash.x, "${oracle.label} overlay x")
        assertNear(0f, overlaySlash.inkBounds.bottom, "${oracle.label} overlay bottom")
        assertNear(0f, overlayBase.x, "${oracle.label} overlay base x")
        assertNear(0f, overlaySlash.advance, "${oracle.label} overlay logical advance")
        assertEquals(
            "XeTeXUnicodeMathNotAccentOverlayOrdinaryAtom",
            overlay.decisions.single { it.name == "TeXNotRelation" }.details["policy"],
        )

        val overlayRelation = engine.layout("a\\not\\propto b", options())
        val relationControl = engine.layout("a\\propto b", options())
        assertNear(
            oracle.notRelationControlWidthPt * TEX_PT_TO_PX,
            relationControl.box.width,
            "${oracle.label} relation control width",
        )
        assertNear(
            oracle.notOverlayRelationWidthPt * TEX_PT_TO_PX,
            overlayRelation.box.width,
            "${oracle.label} not-overlay relation width",
        )
        assertEquals(
            oracle.notOverlayRelationGlyphIds,
            overlayRelation.box.glyphs.map { it.glyphId },
            overlayRelation.debugDump,
        )
        val relationTarget = overlayRelation.box.glyphs.single { it.sourceRange == SourceRange(5, 12) }
        val relationSlash = overlayRelation.box.glyphs.single { it.sourceRange == SourceRange(1, 5) }
        val relationRight = overlayRelation.box.glyphs.single { it.sourceRange == SourceRange(13, 14) }
        assertNear(
            oracle.notOverlayRelationTargetXPt * TEX_PT_TO_PX,
            relationTarget.x,
            "${oracle.label} not-overlay relation target x",
        )
        assertNear(
            oracle.notOverlayRelationSlashXPt * TEX_PT_TO_PX,
            relationSlash.x,
            "${oracle.label} not-overlay relation slash x",
        )
        assertNear(
            oracle.notOverlayRelationRightXPt * TEX_PT_TO_PX,
            relationRight.x,
            "${oracle.label} not-overlay relation next glyph x",
        )
        assertNear(0f, relationTarget.baselineY, "${oracle.label} not-overlay relation target baseline")
        assertNear(0f, relationRight.baselineY, "${oracle.label} not-overlay relation next baseline")
        val overlayRelationDecision = overlayRelation.decisions.single { it.name == "TeXNotRelation" }
        assertEquals(MathAtomClass.Ordinary.toString(), overlayRelationDecision.details["atomClass"])
        assertEquals("XeTeXMathAccentCompletedAsOrdinary", overlayRelationDecision.details["atomClassPolicy"])
        assertEquals(
            "XeTeXUnicodeMathNotAccentOverlayOrdinaryAtom",
            overlayRelationDecision.details["policy"],
        )

        val explicitKern = engine.layout("\\not\\!p", options())
        assertEquals(oracle.notOverlayGlyphIds, explicitKern.box.glyphs.map { it.glyphId }, explicitKern.debugDump)
        val kernSlash = explicitKern.box.glyphs.single { it.sourceRange == SourceRange(0, 4) }
        val kernBase = explicitKern.box.glyphs.single { it.sourceRange == SourceRange(6, 7) }
        val negativeThinSpacePx = -3f * 32f / 18f
        // unicode-math rejects `\not\!p`: these are named compatibility invariants, not a
        // XeTeX syntax oracle. The actual target glyph and its MATH attachment translate by the
        // preserved -3mu kern while the valid `\not p` overlay geometry remains unchanged.
        assertNear(overlay.box.width + negativeThinSpacePx, explicitKern.box.width, "${oracle.label} kern width")
        assertNear(overlay.box.ascent, explicitKern.box.ascent, "${oracle.label} kern ascent")
        assertNear(overlay.box.descent, explicitKern.box.descent, "${oracle.label} kern descent")
        assertNear(negativeThinSpacePx, kernBase.x, "${oracle.label} explicit -3mu base x")
        assertNear(overlaySlash.x + negativeThinSpacePx, kernSlash.x, "${oracle.label} translated overlay x")
        assertNear(0f, kernSlash.inkBounds.bottom, "${oracle.label} kern overlay bottom")
        val kernDecision = explicitKern.decisions.single { it.name == "TeXNotRelation" }
        assertEquals("\\!", kernDecision.details["interveningSpaceCommands"])
        assertEquals("4..6", kernDecision.details["interveningSpaceRanges"])
        assertEquals(
            "ArticleNotNegativeThinKernOpenTypeOverlayCompatibility",
            kernDecision.details["policy"],
        )

        assertTrue(
            listOf(atop, choose, displayChoose, not, overlay, overlayRelation, explicitKern, hline)
                .all { it.diagnostics.isEmpty() },
            oracle.label,
        )
    }

    @Test
    fun negationOverlayPropagatesAllEightMathStylesThroughBothFonts() = withFaces { oracle, engine ->
        MathStyle.entries.forEach { style ->
            val result = engine.layout("\\not p", options().copy(initialStyle = style))
            assertTrue(result.diagnostics.isEmpty(), "${oracle.label} $style: ${result.debugDump}")
            val base = result.box.glyphs.single { it.sourceRange == SourceRange(5, 6) }
            val overlay = result.box.glyphs.single { it.sourceRange == SourceRange(0, 4) }
            val decision = result.decisions.single { it.name == "TeXNotRelation" }

            assertEquals(style, overlay.style, "${oracle.label} overlay $style")
            assertEquals(style.cramped(), base.style, "${oracle.label} nucleus $style")
            assertNear(base.fontSizePx, overlay.fontSizePx, "${oracle.label} font size $style")
            assertEquals(style.toString(), decision.details["style"])
            assertEquals(style.cramped().toString(), decision.details["nucleusStyle"])
            assertEquals(SourceRange(0, 4), overlay.sourceRange)
            assertEquals(SourceRange(5, 6), base.sourceRange)

            val explicitKern = engine.layout("\\not\\!p", options().copy(initialStyle = style))
            assertTrue(explicitKern.diagnostics.isEmpty(), "${oracle.label} $style: ${explicitKern.debugDump}")
            val kernBase = explicitKern.box.glyphs.single { it.sourceRange == SourceRange(6, 7) }
            val kernOverlay = explicitKern.box.glyphs.single { it.sourceRange == SourceRange(0, 4) }
            val expectedKernPx = -3f * base.fontSizePx / 18f
            assertNear(expectedKernPx, kernBase.x - base.x, "${oracle.label} base -3mu $style")
            assertNear(expectedKernPx, kernOverlay.x - overlay.x, "${oracle.label} overlay -3mu $style")
            assertNear(
                expectedKernPx,
                explicitKern.box.width - result.box.width,
                "${oracle.label} logical width -3mu $style",
            )
            val kernDecision = explicitKern.decisions.single { it.name == "TeXNotRelation" }
            assertEquals(style.toString(), kernDecision.details["style"])
            assertEquals(style.cramped().toString(), kernDecision.details["nucleusStyle"])
            assertNear(
                expectedKernPx,
                kernDecision.details.getValue("interveningAdvancePx").toFloat(),
                "${oracle.label} decision -3mu $style",
            )
        }
    }

    @Test
    fun negationCompatibilityRetainsTargetAtomClassAndRejectsOtherSpacingCommands() =
        withFaces { oracle, engine ->
            val plainRelation = engine.layout("a\\equiv b", options())
            val relation = engine.layout("a\\not\\!\\equiv b", options())
            assertTrue(relation.diagnostics.isEmpty(), "${oracle.label}: ${relation.debugDump}")
            val decision = relation.decisions.single { it.name == "TeXNotRelation" }
            assertEquals(MathAtomClass.Relation.toString(), decision.details["atomClass"])
            assertEquals(
                "RetainTargetAtomClassAfterArticleCompatibilityComposition",
                decision.details["atomClassPolicy"],
            )
            assertEquals("\\!", decision.details["interveningSpaceCommands"])
            assertNear(
                plainRelation.box.width - 3f * 32f / 18f,
                relation.box.width,
                "${oracle.label} relation spacing and explicit kern",
            )

            val unsupportedSpacing = engine.layout("\\not\\,p", options())
            assertTrue(
                unsupportedSpacing.diagnostics.any { it.code == DiagnosticCode.UnsupportedNegatedSymbol },
                "${oracle.label}: ${unsupportedSpacing.debugDump}",
            )
        }

    @Test
    fun missingOverlayInTheSelectedNucleusFaceCannotSilentlyResolveFromAnotherFace() {
        SkiaMathFontFace(LeteSansMath.load()).use { real ->
            val result = MathLayoutEngine(MissingNotAccentFace(real)).layout("\\not p", options())
            assertTrue(result.diagnostics.any { it.code == DiagnosticCode.MissingGlyph }, result.debugDump)
            assertEquals(1, result.box.glyphs.size, result.debugDump)
            assertTrue(result.decisions.single { it.name == "TeXNotRelation" }.details["policy"] ==
                "MissingSameFaceOpenTypeNegationOverlayGlyph")
        }
    }

    @Test
    fun photonSelfEnergyArticleFormulaIsReadyWithFourReplayableNegationOverlays() {
        val source = "\\Sigma(\\not\\!p) = -ie^2 \\int\\frac{d^4k}{(2\\pi)^4} \\gamma^\\mu " +
            "\\frac{i}{\\not\\!k - m + i\\epsilon} \\gamma_\\mu " +
            "\\frac{i}{(\\not\\!p - \\not\\!k) - m + i\\epsilon}\\\\"
        oracles.forEach { oracle ->
            oracle.faceFactory().use { face ->
                val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                    face.formulaCapabilityEngine().evaluate(
                        source,
                        options().copy(mode = MathMode.Display, initialStyle = MathStyle.Display),
                    ),
                    oracle.label,
                )
                assertTrue(ready.diagnostics.isEmpty(), "${oracle.label}: ${ready.diagnostics}")
                val decisions = ready.layoutResult.decisions.filter { it.name == "TeXNotRelation" }
                assertEquals(4, decisions.size, ready.layoutResult.debugDump)
                assertTrue(decisions.all {
                    it.details["policy"] == "ArticleNotNegativeThinKernOpenTypeOverlayCompatibility"
                }, ready.layoutResult.debugDump)
                assertEquals(
                    listOf(SourceRange(7, 14), SourceRange(70, 77), SourceRange(116, 123), SourceRange(126, 133)),
                    decisions.map { it.range },
                )
                assertTrue(ready.layoutResult.box.glyphs.count { glyph ->
                    decisions.any { glyph.sourceRange == SourceRange(it.range.start, it.range.start + 4) }
                } >= 4, ready.layoutResult.debugDump)
            }
        }
    }

    @Test
    fun pandoraArticleChooseFormulaIsProductionReadyWithAHostTextProvider() {
        val source = "\\begin{align} P_{\\text{blue}>0.5}=\\sum_{i=\\frac{A}{2}+1}^{A}" +
            "\\sum_{j=0}^{a}\\left(\\frac12\\right)^{a}{a\\choose j}" +
            "(0.8)^{A-a-i+j}(0.2)^{i-j}{A-a\\choose i-j} \\end{align}"
        SkiaMathTextRunProvider.fromBytes(
            faceId = MathFaceId("pandora-article-host-text"),
            fontBytes = LeteSansMath.loadBytes(),
        ).use { textProvider ->
            listOf(
                "Lete" to { SkiaMathFontFace(LeteSansMath.load()) },
                "STIX" to { SkiaMathFontFace(StixTwoMath.load()) },
            ).forEach { (label, factory) ->
                factory().use { face ->
                    val ready = assertIs<MathFormulaCapabilityResult.Ready>(
                        face.formulaCapabilityEngine(textProvider).evaluate(
                            source,
                            options().copy(mode = MathMode.Display, initialStyle = MathStyle.Display),
                        ),
                        label,
                    )
                    assertTrue(ready.diagnostics.isEmpty(), "$label ${ready.diagnostics}")
                    assertEquals(2, ready.layoutResult.decisions.count {
                        it.name == "TeXFractionCommand" && it.details["origin"] == "GeneralizedChoose"
                    })
                }
            }
        }
    }

    @Test
    fun cancelUsesThePackageDimensionFloorsSlopeTableAndKeepsTheArgumentAdvance() = withFaces { oracle, engine ->
        val bare = engine.layout("x+1", options())
        val cancel = engine.layout("\\cancel{x+1}", options())
        assertNear(bare.box.width, cancel.box.width, "${oracle.label} cancel logical advance")
        val rule = cancel.box.rules.single { it.paintRole == MathRulePaintRole.Cancellation }
        val line = assertNotNull(rule.lineSegment)
        assertNear(DEFAULT_RULE_PX, line.thickness, "${oracle.label} cancel thinlines")
        val decision = cancel.decisions.single { it.name == "LatexCancelStroke" }
        assertEquals("Wide", decision.details["shapeClass"])
        val classificationWidth = max(
            decision.details.getValue("contentWidthPx").toFloat(),
            2f * TEX_PT_TO_PX,
        )
        val classificationHeight = max(
            decision.details.getValue("contentTotalHeightPx").toFloat(),
            6f * TEX_PT_TO_PX,
        )
        assertNear(
            classificationWidth,
            decision.details.getValue("classificationWidthPx").toFloat(),
            "${oracle.label} cancel classification width",
        )
        assertNear(
            classificationHeight,
            decision.details.getValue("classificationTotalHeightPx").toFloat(),
            "${oracle.label} cancel classification height",
        )
        assertNear(
            max(classificationWidth, 8f * TEX_PT_TO_PX) + 2f * TEX_PT_TO_PX,
            decision.details.getValue("lineHorizontalExtentPx").toFloat(),
            "${oracle.label} cancel wide picture extent",
        )
        assertTrue(
            line.startX < 0f && line.endX > bare.box.width,
            "${oracle.label} line=${line.startX},${line.startY} -> ${line.endX},${line.endY} width=${bare.box.width}",
        )
        assertTrue(line.startY > line.endY, "${oracle.label} line=$line")
        assertTrue(cancel.box.ascent >= -rule.top && cancel.box.descent >= rule.bottom, oracle.label)
        assertTrue(cancel.diagnostics.isEmpty(), "${oracle.label}: ${cancel.diagnostics}")
    }

    @Test
    fun cancelUsesResolvedTeXPointForEveryPackagePictureDimension() = withFaces { oracle, engine ->
        val densityOneOptions = options().copy(
            fontSizePx = 4f,
            cancelPicturePointPx = TEX_PT_TO_PX,
        )
        val densityOneBare = engine.layout("1", densityOneOptions)
        val densityOne = engine.layout(
            "\\cancel{1}",
            densityOneOptions,
        )
        val scaledPoint = engine.layout(
            "\\cancel{1}",
            options().copy(
                fontSizePx = 4f,
                cancelPicturePointPx = 10f * TEX_PT_TO_PX,
            ),
        )
        val wideFloorOptions = options().copy(
            fontSizePx = 2f,
            cancelPicturePointPx = TEX_PT_TO_PX,
        )
        val minimumHeightBare = engine.layout("1111", wideFloorOptions)
        val minimumHeight = engine.layout("\\cancel{1111}", wideFloorOptions)
        val wideFloorBare = engine.layout("111111111", wideFloorOptions)
        val wideFloor = engine.layout("\\cancel{111111111}", wideFloorOptions)
        fun MathLayoutResult.strokeDecision() = decisions.single { it.name == "LatexCancelStroke" }
        fun MathLayoutResult.strokeLine() = assertNotNull(
            box.rules.single { it.paintRole == MathRulePaintRole.Cancellation }.lineSegment,
        )

        val oneXDecision = densityOne.strokeDecision()
        val tenXDecision = scaledPoint.strokeDecision()
        mapOf(
            "cancelMinimumWidthPx" to 2f,
            "cancelMinimumTotalHeightPx" to 6f,
            "cancelWideMinimumWidthPx" to 8f,
            "cancelTallMinimumHeightPx" to 8f,
            "cancelLineExtensionPx" to 2f,
        ).forEach { (field, multiple) ->
            assertNear(
                multiple * TEX_PT_TO_PX,
                oneXDecision.details.getValue(field).toFloat(),
                "${oracle.label} $field density one",
            )
            assertNear(
                oneXDecision.details.getValue(field).toFloat() * 10f,
                tenXDecision.details.getValue(field).toFloat(),
                "${oracle.label} $field scaled TeX point",
            )
        }
        assertTrue(
            scaledPoint.strokeLine().startY - scaledPoint.strokeLine().endY >
                densityOne.strokeLine().startY - densityOne.strokeLine().endY,
            "${oracle.label} resolved TeX point must change placed endpoints",
        )

        // cancel.sty first clamps the picture classification box to 2pt x 6pt. This fixture is
        // smaller in both dimensions, so the independent package equation selects the tall 1:4
        // line with an 8pt + 2pt vertical extent and a 2.5pt horizontal extent. Assert the actual
        // replayable line and completed box, rather than reading those values back from a decision.
        val tinyClean = densityOneBare.box.texCleanBoxMetrics
        assertTrue(densityOneBare.box.width < 2f * TEX_PT_TO_PX, oracle.label)
        assertTrue(tinyClean.height < 6f * TEX_PT_TO_PX, oracle.label)
        val tinyHorizontalExtent = 2.5f * TEX_PT_TO_PX
        val tinyVerticalExtent = 10f * TEX_PT_TO_PX
        val tinyCenterX = densityOneBare.box.width / 2f
        val tinyCenterY = (tinyClean.descent - tinyClean.ascent) / 2f
        val tinyLine = densityOne.strokeLine()
        assertNear(tinyCenterX - tinyHorizontalExtent / 2f, tinyLine.startX, "${oracle.label} tiny start x")
        assertNear(tinyCenterY + tinyVerticalExtent / 2f, tinyLine.startY, "${oracle.label} tiny start y")
        assertNear(tinyCenterX + tinyHorizontalExtent / 2f, tinyLine.endX, "${oracle.label} tiny end x")
        assertNear(tinyCenterY - tinyVerticalExtent / 2f, tinyLine.endY, "${oracle.label} tiny end y")
        assertNear(densityOneBare.box.width, densityOne.box.width, "${oracle.label} tiny logical width")
        assertNear(
            max(densityOneBare.box.ascent, -tinyLine.endY + tinyLine.thickness / 2f),
            densityOne.box.ascent,
            "${oracle.label} tiny completed ascent",
        )
        assertNear(
            max(densityOneBare.box.descent, tinyLine.startY + tinyLine.thickness / 2f),
            densityOne.box.descent,
            "${oracle.label} tiny completed descent",
        )

        // Isolate the 6pt total-height floor: the unmodified content is wider than its ink height,
        // but narrower than 6pt. cancel.sty therefore reclassifies it as Tall; deleting only the
        // 6pt clamp would switch this same source to Wide and change both slope and endpoints.
        val minimumHeightClean = minimumHeightBare.box.texCleanBoxMetrics
        val minimumHeightWidth = max(minimumHeightBare.box.width, 2f * TEX_PT_TO_PX)
        assertTrue(minimumHeightClean.height < minimumHeightWidth, oracle.label)
        assertTrue(minimumHeightWidth < 6f * TEX_PT_TO_PX, oracle.label)
        val minimumHeightExtended = 10f * TEX_PT_TO_PX
        val minimumHeightSlopeCase = kotlin.math.floor(
            minimumHeightWidth * 5f / minimumHeightExtended,
        ).toInt().coerceIn(0, 4)
        val minimumHeightSlope = listOf(1 to 6, 1 to 4, 1 to 2, 3 to 4, 1 to 1)[minimumHeightSlopeCase]
        val minimumHeightFactor = listOf(0.16f, 0.25f, 0.5f, 0.75f, 1f)[minimumHeightSlopeCase]
        val minimumHeightHorizontalExtent = minimumHeightFactor * minimumHeightExtended
        val minimumHeightVerticalExtent =
            minimumHeightHorizontalExtent * minimumHeightSlope.second / minimumHeightSlope.first
        val minimumHeightCenterX = minimumHeightBare.box.width / 2f
        val minimumHeightCenterY = (minimumHeightClean.descent - minimumHeightClean.ascent) / 2f
        val minimumHeightLine = minimumHeight.strokeLine()
        assertNear(
            minimumHeightCenterX - minimumHeightHorizontalExtent / 2f,
            minimumHeightLine.startX,
            "${oracle.label} 6pt floor start x",
        )
        assertNear(
            minimumHeightCenterY + minimumHeightVerticalExtent / 2f,
            minimumHeightLine.startY,
            "${oracle.label} 6pt floor start y",
        )
        assertNear(
            minimumHeightCenterX + minimumHeightHorizontalExtent / 2f,
            minimumHeightLine.endX,
            "${oracle.label} 6pt floor end x",
        )
        assertNear(
            minimumHeightCenterY - minimumHeightVerticalExtent / 2f,
            minimumHeightLine.endY,
            "${oracle.label} 6pt floor end y",
        )

        val wideDecision = wideFloor.strokeDecision()
        assertEquals("Wide", wideDecision.details["shapeClass"], oracle.label)
        assertTrue(
            wideDecision.details.getValue("classificationWidthPx").toFloat() < 8f * TEX_PT_TO_PX,
            "${oracle.label} fixture must exercise cancel.sty's 8pt wide floor",
        )
        assertNear(
            10f * TEX_PT_TO_PX,
            wideDecision.details.getValue("lineHorizontalExtentPx").toFloat(),
            "${oracle.label} 8pt wide floor plus 2pt extension",
        )
        val wideClean = wideFloorBare.box.texCleanBoxMetrics
        val wideClassificationWidth = max(wideFloorBare.box.width, 2f * TEX_PT_TO_PX)
        val wideClassificationHeight = max(wideClean.height, 6f * TEX_PT_TO_PX)
        assertTrue(wideClassificationWidth < 8f * TEX_PT_TO_PX, oracle.label)
        assertTrue(wideClassificationHeight < wideClassificationWidth, oracle.label)
        val wideHorizontalExtent = 10f * TEX_PT_TO_PX
        val wideSlopeCase = kotlin.math.floor(
            wideClassificationHeight * 5f / (8f * TEX_PT_TO_PX),
        ).toInt().coerceIn(0, 4)
        val wideSlope = listOf(6 to 1, 4 to 1, 2 to 1, 4 to 3, 1 to 1)[wideSlopeCase]
        val wideVerticalExtent = wideHorizontalExtent * wideSlope.second / wideSlope.first
        val wideCenterX = wideFloorBare.box.width / 2f
        val wideCenterY = (wideClean.descent - wideClean.ascent) / 2f
        val wideLine = wideFloor.strokeLine()
        assertNear(wideCenterX - wideHorizontalExtent / 2f, wideLine.startX, "${oracle.label} wide start x")
        assertNear(wideCenterY + wideVerticalExtent / 2f, wideLine.startY, "${oracle.label} wide start y")
        assertNear(wideCenterX + wideHorizontalExtent / 2f, wideLine.endX, "${oracle.label} wide end x")
        assertNear(wideCenterY - wideVerticalExtent / 2f, wideLine.endY, "${oracle.label} wide end y")
        assertNear(wideFloorBare.box.width, wideFloor.box.width, "${oracle.label} wide logical width")
        assertNear(
            max(wideFloorBare.box.ascent, -wideLine.endY + wideLine.thickness / 2f),
            wideFloor.box.ascent,
            "${oracle.label} wide completed ascent",
        )
        assertNear(
            max(wideFloorBare.box.descent, wideLine.startY + wideLine.thickness / 2f),
            wideFloor.box.descent,
            "${oracle.label} wide completed descent",
        )
    }

    @Test
    fun textbfRequestsHostBoldWhileLegacyBfUsesTheMathVersionAxis() {
        SkiaMathTextRunProvider.fromBytes(
            MathFaceId("remaining-host-bold"),
            LeteSansMath.loadBoldBytes(),
            MathFontWeight.Bold,
        ).use { text ->
            listOf(
                "Lete" to { SkiaMathFontFace(LeteSansMath.load()) },
                "STIX" to { SkiaMathFontFace(StixTwoMath.load()) },
            ).forEach { (label, factory) ->
                factory().use { face ->
                    val result = MathLayoutEngine(face, textRunProvider = text).layout("\\textbf{1}", options())
                    assertTrue(result.diagnostics.isEmpty(), "$label ${result.diagnostics}")
                    assertTrue(result.box.glyphs.all { it.requestedWeight == MathFontWeight.Bold }, label)
                    assertTrue(result.box.glyphs.all { it.resolvedWeight == MathFontWeight.Bold }, label)
                    assertTrue(result.box.glyphs.all { it.faceId == MathFaceId("remaining-host-bold") }, label)
                }
            }
        }

        SkiaMathFontFamily.loadBundledLete().use { family ->
            val result = MathLayoutEngine(family).layout("\\bf{0}", options())
            assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
            val normal = MathLayoutEngine(family).layout("0", options())
            assertNotNull(result.box.glyphs.singleOrNull(), result.debugDump)
            assertTrue(
                result.box.glyphs.single().glyphId != normal.box.glyphs.single().glyphId,
                result.debugDump,
            )
            assertTrue(
                result.decisions.single { it.name == "TeXMathSymbolResolution" }
                    .details["resolvedAlphabet"] == "Bold",
                result.debugDump,
            )
            assertTrue(result.decisions.any { it.name == "TeXMathVersionDeclaration" })

            val grouped = MathLayoutEngine(family).layout("{\\bf 0}+1", options())
            val normalDigits = MathLayoutEngine(family).layout("01", options()).box.glyphs
            assertTrue(grouped.diagnostics.isEmpty(), grouped.debugDump)
            assertTrue(grouped.box.glyphs.first().glyphId != normalDigits.first().glyphId, grouped.debugDump)
            assertEquals(normalDigits.last().glyphId, grouped.box.glyphs.last().glyphId, grouped.debugDump)
        }
    }

    private fun options() = MathLayoutOptions(
        mode = MathMode.Inline,
        initialStyle = MathStyle.Text,
        fontSizePx = 32f,
        nullDelimiterSpacePx = 1.2f * TEX_PT_TO_PX,
        scriptSpacePx = 0.5f * TEX_PT_TO_PX,
        delimiterShortfallPx = 5f * TEX_PT_TO_PX,
        arrayColumnSeparationPx = 5f * TEX_PT_TO_PX,
        arrayRuleThicknessPx = DEFAULT_RULE_PX,
        cancelPicturePointPx = TEX_PT_TO_PX,
        cancelLineThicknessPx = DEFAULT_RULE_PX,
    )

    private fun withFaces(block: (Oracle, MathLayoutEngine) -> Unit) {
        oracles.forEach { oracle -> oracle.faceFactory().use { block(oracle, MathLayoutEngine(it)) } }
    }

    private fun assertBox(expected: Box, actual: MathLayoutResult, label: String) {
        assertNear(expected.widthPt * TEX_PT_TO_PX, actual.box.width, "$label width")
        assertNear(expected.ascentPt * TEX_PT_TO_PX, actual.box.ascent, "$label ascent")
        assertNear(expected.descentPt * TEX_PT_TO_PX, actual.box.descent, "$label descent")
    }

    private fun assertNear(expected: Float, actual: Float, label: String, tolerance: Float = 0.08f) {
        assertTrue(abs(expected - actual) <= tolerance, "$label expected=$expected actual=$actual")
    }

    private data class Box(val widthPt: Float, val ascentPt: Float, val descentPt: Float)

    private class MissingNotAccentFace(private val delegate: SkiaMathFontFace) : MathFontFace by delegate {
        override val mathFont = delegate.mathFont.copy(
            characterGlyphs = delegate.mathFont.characterGlyphs - 0x0338,
        )

        override fun mathFontFor(faceId: MathFaceId) = if (faceId == delegate.faceId) {
            mathFont
        } else {
            error("unexpected face $faceId")
        }

        override fun mathFontForOrNull(faceId: MathFaceId) =
            if (faceId == delegate.faceId) mathFont else null

        override fun resolveSymbolWithRequiredGlyph(
            request: MathSymbolGlyphRequest,
            requiredScalar: Int,
            fontSizePx: Float,
        ): ResolvedMathSymbolWithRequiredGlyph {
            val symbol = delegate.resolveSymbol(request, fontSizePx)
            val owningFaceId = symbol.run.glyphs.map { it.faceId }.distinct().singleOrNull()
            return ResolvedMathSymbolWithRequiredGlyph(
                symbol = symbol,
                requiredScalar = requiredScalar,
                requiredGlyphId = owningFaceId?.let { mathFontForOrNull(it) }
                    ?.glyphForScalar(requiredScalar),
                owningFaceId = owningFaceId,
            )
        }
    }

    private data class Oracle(
        val label: String,
        val faceFactory: () -> SkiaMathFontFace,
        val atop: Box,
        val choose: Box,
        val chooseDisplay: Box,
        val atopWidthPx: Float,
        val atopGlyphIds: List<UShort>?,
        val chooseEngineWidthPx: Float,
        val chooseDelimiterGlyphIds: List<UShort>,
        val not: Box,
        val notGlyphId: UShort,
        val notOverlay: Box,
        val notOverlayGlyphIds: List<UShort>,
        val notOverlayXPt: Float,
        val notRelationControlWidthPt: Float,
        val notOverlayRelationWidthPt: Float,
        val notOverlayRelationGlyphIds: List<UShort>,
        val notOverlayRelationTargetXPt: Float,
        val notOverlayRelationSlashXPt: Float,
        val notOverlayRelationRightXPt: Float,
        val hline: Box,
    )

    private companion object {
        const val TEX_PT_TO_PX = 96f / 72.27f
        const val DEFAULT_RULE_PX = 0.4f * TEX_PT_TO_PX
        val oracles = listOf(
            Oracle(
                "Lete",
                { SkiaMathFontFace(LeteSansMath.load()) },
                Box(12.01186f, 19.5092f, 11.68246f),
                Box(28.88387f, 26.58403f, 13.09221f),
                Box(33.0033f, 26.58403f, 17.03342f),
                12.01186f * TEX_PT_TO_PX,
                null,
                28.88387f * TEX_PT_TO_PX,
                listOf(1836u, 1851u),
                Box(58.89072f, 16.598f, 4.14348f),
                629u,
                Box(13.53859f, 18.81429f, 4.14348f),
                listOf(3642u, 157u),
                7.03429f,
                56.50580f,
                43.12110f,
                listOf(3628u, 560u, 157u, 3629u),
                13.41814f,
                21.39193f,
                29.38980f,
                Box(23.7313f, 31.46956f, 17.97772f),
            ),
            Oracle(
                "STIX",
                { SkiaMathFontFace(StixTwoMath.load()) },
                Box(11.75891f, 19.40083f, 14.29651f),
                Box(28.82364f, 23.39204f, 14.29651f),
                Box(32.83467f, 26.95834f, 15.70831f),
                16.829649f,
                listOf(4421u, 4422u),
                39.497604f,
                listOf(1302u, 1314u),
                Box(59.05934f, 16.33302f, 5.22752f),
                1808u,
                Box(14.40582f, 20.18742f, 5.2998f),
                listOf(3343u, 844u),
                15.41757f,
                56.69853f,
                43.31383f,
                listOf(3326u, 1677u, 844u, 3327u),
                13.36995f,
                27.58302f,
                30.71475f,
                Box(23.36995f, 30.56613f, 18.13435f),
            ),
        )
    }
}
