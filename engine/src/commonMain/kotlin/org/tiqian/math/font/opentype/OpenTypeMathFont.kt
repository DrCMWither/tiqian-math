package org.tiqian.math.font.opentype

import org.tiqian.math.core.DiagnosticCode
import kotlin.math.ceil

data class OpenTypeMathFont(
    val bytes: ByteArray,
    val unitsPerEm: Int,
    val lineMetrics: OpenTypeLineMetrics,
    /** OS/2.sxHeight in design units when the font provides the version-2 metric. */
    val xHeight: Int? = null,
    val constants: OpenTypeMathConstants,
    val italicCorrections: Map<UShort, Int>,
    val italicCorrectionDeviceAdjustments: Map<UShort, MathDeviceAdjustment> = emptyMap(),
    val unsupportedItalicCorrectionVariationAdjustments: Set<UShort> = emptySet(),
    val extendedShapeGlyphs: Set<UShort>,
    val mathKernInfo: Map<UShort, MathGlyphKernInfo>,
    val verticalConstructions: Map<UShort, MathGlyphConstruction>,
    val topAccentAttachments: Map<UShort, Int> = emptyMap(),
    val topAccentAttachmentDeviceAdjustments: Map<UShort, MathDeviceAdjustment> = emptyMap(),
    val unsupportedTopAccentAttachmentVariationAdjustments: Set<UShort> = emptySet(),
    val horizontalConstructions: Map<UShort, MathGlyphConstruction> = emptyMap(),
    /** Unicode scalar to the font's base cmap glyph. */
    val characterGlyphs: Map<Int, UShort> = emptyMap(),
    /** Base glyph to ordered OpenType `ssty` alternates (feature values 1, 2, ...). */
    val scriptStyleAlternates: Map<UShort, List<UShort>> = emptyMap(),
) {
    /** Compatibility view: only truly unsupported VariationIndex records remain here. */
    val unsupportedItalicCorrectionAdjustments: Set<UShort>
        get() = unsupportedItalicCorrectionVariationAdjustments

    /** Compatibility view: only truly unsupported VariationIndex records remain here. */
    val unsupportedTopAccentAttachmentAdjustments: Set<UShort>
        get() = unsupportedTopAccentAttachmentVariationAdjustments

    val verticalVariants: Map<UShort, List<MathGlyphVariant>>
        get() = verticalConstructions.mapValues { it.value.variants }

    val horizontalVariants: Map<UShort, List<MathGlyphVariant>>
        get() = horizontalConstructions.mapValues { it.value.variants }

    fun scaleDesignUnits(value: Int, fontSizePx: Float): Float = value * fontSizePx / unitsPerEm

    fun scaleDesignUnits(value: Float, fontSizePx: Float): Float = value * fontSizePx / unitsPerEm

    fun glyphForScalar(scalar: Int, scriptStyleLevel: Int = 0): UShort? {
        val base = characterGlyphs[scalar] ?: return null
        if (scriptStyleLevel <= 0) return base
        val alternates = scriptStyleAlternates[base].orEmpty()
        return alternates.getOrNull(scriptStyleLevel - 1) ?: alternates.lastOrNull() ?: base
    }

    fun italicCorrection(glyphId: UShort, fontSizePx: Float): Float {
        if (glyphId in unsupportedItalicCorrectionVariationAdjustments) {
            throw OpenTypeMathException(
                DiagnosticCode.UnsupportedMathDeviceAdjustment,
                "MathItalicsCorrectionInfo[$glyphId] requires an unsupported VariationIndex adjustment",
            )
        }
        return scaleDesignUnits(italicCorrections[glyphId] ?: 0, fontSizePx)
    }

    fun mathKern(
        glyphId: UShort,
        corner: MathKernCorner,
        correctionHeightPx: Float,
        fontSizePx: Float,
    ): Float {
        val table = mathKernInfo[glyphId]?.table(corner) ?: return 0f
        val heightDesignUnits = correctionHeightPx * unitsPerEm / fontSizePx
        return scaleDesignUnits(table.valueAt(heightDesignUnits), fontSizePx)
    }

    fun topAccentAttachment(
        glyphId: UShort,
        fontSizePx: Float,
        fallbackAdvancePx: Float,
    ): Float {
        if (glyphId in unsupportedTopAccentAttachmentVariationAdjustments) {
            throw OpenTypeMathException(
                DiagnosticCode.UnsupportedMathDeviceAdjustment,
                "MathTopAccentAttachment[$glyphId] requires an unsupported VariationIndex adjustment",
            )
        }
        return topAccentAttachments[glyphId]?.let { scaleDesignUnits(it, fontSizePx) }
            ?: fallbackAdvancePx / 2f
    }

    fun horizontalConstruction(
        request: MathHorizontalConstructionRequest,
        glyphGrowthExtentPx: (UShort) -> Float,
        glyphOrthogonalExtentPx: (UShort) -> Float,
    ): MathVerticalConstruction? {
        val construction = horizontalConstructions[request.baseGlyphId] ?: return null
        val target = request.targetSizePx * unitsPerEm / request.fontSizePx
        val firstAtOrAbove = construction.variants.indexOfFirst { it.advanceMeasurement >= target }
        if (firstAtOrAbove >= 0) {
            // XeTeX make_math_accent keeps the largest variant whose advance does not exceed
            // the clean nucleus width. The first wider variant terminates the search without
            // being selected.
            val variant = construction.variants.take(firstAtOrAbove + 1)
                .lastOrNull { it.advanceMeasurement <= target }
            if (variant == null || variant.glyphId == request.baseGlyphId) {
                return MathVerticalConstruction(
                    kind = MathConstructionKind.BaseGlyph,
                    components = listOf(MathGlyphComponent(request.baseGlyphId, 0f)),
                    advanceMeasurement = request.normalGlyphWidthPx * unitsPerEm / request.fontSizePx,
                    reachesTarget = request.normalGlyphWidthPx >= request.targetSizePx,
                    constructionPolicy = "XeTeXMathAccentNormalGlyphBeforeFirstWiderVariant",
                    orthogonalAdvancePx = request.normalGlyphOrthogonalExtentPx,
                )
            }
            return MathVerticalConstruction(
                kind = MathConstructionKind.Variant,
                components = listOf(MathGlyphComponent(variant.glyphId, 0f)),
                advanceMeasurement = variant.advanceMeasurement.toFloat(),
                reachesTarget = variant.advanceMeasurement >= target,
                constructionPolicy = "XeTeXMathAccentLargestVariantNotWiderThanNucleus",
                orthogonalAdvancePx = glyphOrthogonalExtentPx(variant.glyphId),
            )
        }
        val assembly = construction.assembly
        val validation = assembly?.let(::validateAssembly)
        if (assembly != null && validation?.valid == true) {
            return assembleXeTeX(
                assembly = assembly,
                validation = validation,
                target = target,
                fontSizePx = request.fontSizePx,
                glyphVerticalExtentPx = glyphGrowthExtentPx,
                glyphAdvanceWidthPx = glyphOrthogonalExtentPx,
            ).copy(
                constructionPolicy = "Tectonic0.17.0XeTeXHorizontalAccentAssemblyStretchGlue",
            )
        }
        val last = construction.variants.lastOrNull() ?: return null
        return MathVerticalConstruction(
            kind = MathConstructionKind.Variant,
            components = listOf(MathGlyphComponent(last.glyphId, 0f)),
            advanceMeasurement = last.advanceMeasurement.toFloat(),
            reachesTarget = last.advanceMeasurement >= target,
            assemblyValidation = validation,
            constructionPolicy = if (validation?.valid == false) {
                "XeTeXMathAccentLastVariantAfterInvalidAssembly"
            } else {
                "XeTeXMathAccentLastVariantWithoutAssembly"
            },
            orthogonalAdvancePx = glyphOrthogonalExtentPx(last.glyphId),
        )
    }

    fun horizontalAssemblyValidation(baseGlyphId: UShort): MathGlyphAssemblyValidation? =
        horizontalConstructions[baseGlyphId]?.assembly?.let(::validateAssembly)

    fun verticalVariant(baseGlyphId: UShort, minimumAdvancePx: Float, fontSizePx: Float): MathGlyphVariant? {
        val variants = verticalVariants[baseGlyphId].orEmpty()
        if (variants.isEmpty()) return null
        val minimumDesignUnits = minimumAdvancePx * unitsPerEm / fontSizePx
        return variants.firstOrNull { it.advanceMeasurement >= minimumDesignUnits } ?: variants.last()
    }

    fun verticalConstruction(
        request: MathVerticalConstructionRequest,
        glyphVerticalExtentPx: ((UShort) -> Float)? = null,
        glyphAdvanceWidthPx: (UShort) -> Float,
    ): MathVerticalConstruction? {
        if (request.normalGlyphHeightPx >= request.targetSizePx) {
            return MathVerticalConstruction(
                kind = MathConstructionKind.BaseGlyph,
                components = listOf(MathGlyphComponent(request.baseGlyphId, 0f)),
                advanceMeasurement = request.normalGlyphHeightPx * unitsPerEm / request.fontSizePx,
                reachesTarget = true,
                constructionPolicy = "MathMLCore5.3.2NormalGlyph",
                orthogonalAdvancePx = request.normalGlyphAdvanceWidthPx,
            )
        }
        val construction = verticalConstructions[request.baseGlyphId] ?: return null
        val target = request.targetSizePx * unitsPerEm / request.fontSizePx
        construction.variants.firstOrNull { it.advanceMeasurement >= target }?.let { variant ->
            return MathVerticalConstruction(
                MathConstructionKind.Variant,
                listOf(MathGlyphComponent(variant.glyphId, 0f)),
                variant.advanceMeasurement.toFloat(),
                reachesTarget = true,
                constructionPolicy = "MathMLCore5.3.2Variant",
                orthogonalAdvancePx = glyphAdvanceWidthPx(variant.glyphId),
            )
        }
        val assembly = construction.assembly
        val assemblyValidation = assembly?.let(::validateAssembly)
        if (assembly != null && assemblyValidation?.valid == true) {
            return when (request.assemblyPolicy) {
                MathVerticalAssemblyPolicy.MathMLCoreUniformOverlap ->
                    assemble(assembly, assemblyValidation, target, glyphAdvanceWidthPx)
                MathVerticalAssemblyPolicy.TectonicXeTeXStretchGlue ->
                    assembleXeTeX(
                        assembly = assembly,
                        validation = assemblyValidation,
                        target = target,
                        fontSizePx = request.fontSizePx,
                        glyphVerticalExtentPx = checkNotNull(glyphVerticalExtentPx) {
                            "XeTeX assembly requires exact glyph vertical extents"
                        },
                        glyphAdvanceWidthPx = glyphAdvanceWidthPx,
                    )
            }
        }
        val last = construction.variants.lastOrNull() ?: return null
        return MathVerticalConstruction(
            MathConstructionKind.Variant,
            listOf(MathGlyphComponent(last.glyphId, 0f)),
            last.advanceMeasurement.toFloat(),
            reachesTarget = last.advanceMeasurement >= target,
            assemblyValidation = assemblyValidation,
            constructionPolicy = if (assemblyValidation?.valid == false) {
                "MathMLCore5.3.2LastVariantAfterInvalidAssembly"
            } else {
                "MathMLCore5.3.2LastVariant"
            },
            orthogonalAdvancePx = glyphAdvanceWidthPx(last.glyphId),
        )
    }

    fun verticalAssemblyValidation(baseGlyphId: UShort): MathGlyphAssemblyValidation? =
        verticalConstructions[baseGlyphId]?.assembly?.let(::validateAssembly)

    private fun validateAssembly(assembly: MathGlyphAssembly): MathGlyphAssemblyValidation {
        val extenders = assembly.parts.filter { it.extender }
        val extenderAdvance = extenders.sumOf { it.fullAdvance.toLong() }
        val nonOverlappingAdvance = extenderAdvance -
            assembly.minimumConnectorOverlap.toLong() * extenders.size
        // Only connector ends that can meet another part are constrained. The bottom
        // non-extender's outer start and top non-extender's outer end are not connections;
        // real Lete/STIX constructions intentionally store zero there.
        val adjacentConnections = assembly.parts.zipWithNext()
        val shortAdjacentConnector = adjacentConnections.any { (lower, upper) ->
            lower.endConnectorLength < assembly.minimumConnectorOverlap ||
                upper.startConnectorLength < assembly.minimumConnectorOverlap
        }
        // An extender can be repeated next to itself even when it appears only once in the
        // source part list, so both of its connector ends participate in a possible join.
        val shortExtenderSelfConnector = extenders.any {
            it.startConnectorLength < assembly.minimumConnectorOverlap ||
                it.endConnectorLength < assembly.minimumConnectorOverlap
        }
        val reasons = buildSet {
            if (extenders.isEmpty()) add(MathGlyphAssemblyInvalidReason.NoExtender)
            if (nonOverlappingAdvance <= 0L) {
                add(MathGlyphAssemblyInvalidReason.NonPositiveExtenderGrowth)
            }
            if (shortAdjacentConnector || shortExtenderSelfConnector) {
                add(MathGlyphAssemblyInvalidReason.ConnectorShorterThanMinimumOverlap)
            }
        }
        return MathGlyphAssemblyValidation(
            valid = reasons.isEmpty(),
            invalidReasons = reasons,
            extenderCount = extenders.size,
            nonExtenderCount = assembly.parts.size - extenders.size,
            extenderNonOverlappingAdvance = nonOverlappingAdvance,
            checkedConnectionCount = adjacentConnections.size + extenders.size,
            validationPolicy = "TiqianOpenTypeTerminalConnectorCompatibility",
            specificationDivergence =
                "MathMLCore5.3.1RequiresEveryTerminalConnectorAtLeastMinimum",
        )
    }

    private fun assemble(
        assembly: MathGlyphAssembly,
        validation: MathGlyphAssemblyValidation,
        target: Float,
        glyphAdvanceWidthPx: (UShort) -> Float,
    ): MathVerticalConstruction {
        check(validation.valid)
        val extenders = assembly.parts.filter { it.extender }
        val nonExtenders = assembly.parts.filterNot { it.extender }
        val extenderAdvance = extenders.sumOf { it.fullAdvance }.toFloat()
        val nonExtenderAdvance = nonExtenders.sumOf { it.fullAdvance }.toFloat()
        val minimumOverlap = assembly.minimumConnectorOverlap.toFloat()
        val extenderGrowth = validation.extenderNonOverlappingAdvance.toFloat()
        // MathML Core 5.3.1 r_min: the smallest whole extender repetition count whose
        // assembly reaches T when every connection uses the minimum overlap.
        val repetitionsNumerator = target - nonExtenderAdvance +
            minimumOverlap * (validation.nonExtenderCount - 1)
        val rMin = ceil(repetitionsNumerator / extenderGrowth)
            .toInt()
            .coerceAtLeast(if (validation.nonExtenderCount == 0) 1 else 0)
        val sequence = buildList {
            assembly.parts.forEach { part ->
                if (!part.extender) add(part) else repeat(rMin) { add(part) }
            }
        }
        check(sequence.isNotEmpty())

        val connectionMaxima = sequence.zipWithNext { lower, upper ->
            minOf(lower.endConnectorLength, upper.startConnectorLength).toFloat()
        }
        val zeroOverlapSize = sequence.sumOf { it.fullAdvance }.toFloat()
        // MathML Core 5.3.1 o_max: one overlap for all connections, bounded by both
        // the target-derived theoretical value and every participating connector pair.
        val oMax = if (sequence.size <= 1) {
            0f
        } else {
            val oMaxTheoretical = (zeroOverlapSize - target) / (sequence.size - 1)
            minOf(oMaxTheoretical, connectionMaxima.min()).coerceAtLeast(minimumOverlap)
        }
        val overlaps = List(connectionMaxima.size) { oMax }

        var offset = 0f
        val components = sequence.mapIndexed { index, part ->
            MathGlyphComponent(part.glyphId, offset).also {
                if (index < sequence.lastIndex) {
                    offset += part.fullAdvance - overlaps[index]
                }
            }
        }
        val actualAdvance = offset + sequence.last().fullAdvance
        return MathVerticalConstruction(
            MathConstructionKind.Assembly,
            components,
            actualAdvance,
            reachesTarget = actualAdvance + ASSEMBLY_REACH_EPSILON_DESIGN_UNITS >= target,
            connectorOverlaps = overlaps,
            extenderRepetitions = rMin,
            assemblyItalicCorrection = assembly.italicCorrection,
            assemblyValidation = validation,
            constructionPolicy = "MathMLCore5.3.1UniformOverlap",
            uniformConnectorOverlap = oMax,
            // MathML Core 5.3.1 defines the vertical assembly's orthogonal width from
            // every part record, including an extender skipped when rMin is zero.
            orthogonalAdvancePx = assembly.parts.maxOf { glyphAdvanceWidthPx(it.glyphId) },
        )
    }

    /**
     * Replays Tectonic 0.17.0/XeTeX `build_opentype_assembly`: extender repetition is selected
     * from OpenType full advances at minimum overlap, then TeX glue starts at each connection's
     * maximum overlap and stretches toward the font minimum. The completed vbox therefore uses
     * exact native-glyph height/depth rather than treating `fullAdvance` as a glyph bbox.
     */
    private fun assembleXeTeX(
        assembly: MathGlyphAssembly,
        validation: MathGlyphAssemblyValidation,
        target: Float,
        fontSizePx: Float,
        glyphVerticalExtentPx: (UShort) -> Float,
        glyphAdvanceWidthPx: (UShort) -> Float,
    ): MathVerticalConstruction {
        check(validation.valid)
        var repetitions = -1
        var maximumSize: Float
        do {
            repetitions += 1
            maximumSize = 0f
            var previousEndConnector = 0f
            assembly.parts.forEach { part ->
                val count = if (part.extender) repetitions else 1
                repeat(count) {
                    val overlap = minOf(
                        part.startConnectorLength.toFloat(),
                        assembly.minimumConnectorOverlap.toFloat(),
                        previousEndConnector,
                    )
                    maximumSize += part.fullAdvance - overlap
                    previousEndConnector = part.endConnectorLength.toFloat()
                }
            }
        } while (maximumSize < target)

        val sequence = buildList {
            assembly.parts.forEach { part ->
                if (part.extender) repeat(repetitions) { add(part) } else add(part)
            }
        }
        check(sequence.isNotEmpty())
        val glyphExtents = sequence.map { part ->
            glyphVerticalExtentPx(part.glyphId) * unitsPerEm / fontSizePx
        }
        val maximumOverlaps = mutableListOf<Float>()
        val minimumOverlaps = mutableListOf<Float>()
        var previousEndConnector = 0f
        sequence.forEachIndexed { index, part ->
            val maximumOverlap = minOf(part.startConnectorLength.toFloat(), previousEndConnector)
            if (index > 0) {
                maximumOverlaps += maximumOverlap
                minimumOverlaps += minOf(maximumOverlap, assembly.minimumConnectorOverlap.toFloat())
            }
            previousEndConnector = part.endConnectorLength.toFloat()
        }
        val naturalAdvance = glyphExtents.sum() - maximumOverlaps.sum()
        val stretchCapacity = maximumOverlaps.zip(minimumOverlaps).sumOf { (maximum, minimum) ->
            (maximum - minimum).toDouble()
        }.toFloat()
        val appliedStretch = (target - naturalAdvance).coerceIn(0f, stretchCapacity)
        val glueRatio = if (stretchCapacity > 0f) appliedStretch / stretchCapacity else 0f
        val actualOverlaps = maximumOverlaps.zip(minimumOverlaps).map { (maximum, minimum) ->
            maximum - glueRatio * (maximum - minimum)
        }

        var offset = 0f
        val components = sequence.mapIndexed { index, part ->
            MathGlyphComponent(part.glyphId, offset).also {
                if (index < sequence.lastIndex) {
                    offset += glyphExtents[index] - actualOverlaps[index]
                }
            }
        }
        val actualAdvance = naturalAdvance + appliedStretch
        return MathVerticalConstruction(
            kind = MathConstructionKind.Assembly,
            components = components,
            advanceMeasurement = actualAdvance,
            reachesTarget = actualAdvance + ASSEMBLY_REACH_EPSILON_DESIGN_UNITS >= target,
            connectorOverlaps = actualOverlaps,
            extenderRepetitions = repetitions,
            assemblyItalicCorrection = assembly.italicCorrection,
            assemblyValidation = validation,
            constructionPolicy = "Tectonic0.17.0XeTeXBuildOpenTypeAssemblyStretchGlue",
            uniformConnectorOverlap = null,
            orthogonalAdvancePx = assembly.parts.maxOf { glyphAdvanceWidthPx(it.glyphId) },
            assemblyNaturalAdvance = naturalAdvance,
            assemblyStretchCapacity = stretchCapacity,
            assemblyAppliedStretch = appliedStretch,
            assemblyGlyphExtents = glyphExtents,
            assemblyMaximumConnectorOverlaps = maximumOverlaps,
            assemblyMinimumConnectorOverlaps = minimumOverlaps,
        )
    }

    private companion object {
        const val ASSEMBLY_REACH_EPSILON_DESIGN_UNITS = 0.01f
    }
}

class OpenTypeMathException(
    val diagnosticCode: DiagnosticCode,
    message: String,
) : IllegalArgumentException(message)
