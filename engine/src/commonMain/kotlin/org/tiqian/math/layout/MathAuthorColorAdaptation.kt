package org.tiqian.math.layout

import org.tiqian.math.core.MathBox
import org.tiqian.math.core.MathConstructionPaintGroup
import org.tiqian.math.core.MathGlyphPlacement
import org.tiqian.math.core.MathHostTextPlacement
import org.tiqian.math.core.MathLayoutResult
import org.tiqian.math.core.MathPaintColor
import org.tiqian.math.core.MathPaintLayer
import org.tiqian.math.core.MathRulePaintRole
import org.tiqian.math.core.MathRulePlacement

/**
 * AuthorColorAdaptation: hosts render author-declared TeX colors (`\color`, `\bbox`) that were
 * chosen for a white page into arbitrary themes. This transform lets the host adapt every piece
 * of author paint evidence in one pass over the immutable layout result — painters replay the
 * rewritten evidence verbatim, so measurement and paint stay same-source and no renderer owns a
 * second copy of color policy. Opt-in: without an adapter the result is untouched.
 *
 * Roles separate the two contrast directions an adapter must solve: an [MathAuthorColorRole.AuthorBackground]
 * fill is adapted against the page backdrop, then content covered by that fill is adapted as
 * [MathAuthorColorRole.ForegroundOnAuthorBackground] against the fill's *adapted* color, so local
 * contrast survives whatever the backdrop stage did. Theme-inherited content (null paint color)
 * covered by an author background is stamped with an explicit adapted color only when the adapter
 * actually changes it, preserving inherit semantics everywhere else.
 */
enum class MathAuthorColorRole {
    /** Author background fill, adapted against the page backdrop behind the formula. */
    AuthorBackground,

    /** Author-colored content over the page backdrop. */
    Foreground,

    /** Author-colored content over an author background; backdrop is that fill's adapted color. */
    ForegroundOnAuthorBackground,

    /**
     * Theme-inherited (uncolored) content over an author background. Unlike author colors it is
     * already theme-appropriate, so an adapter should at most restore local contrast against the
     * adapted fill — never re-map its hue or lightness wholesale.
     */
    InheritedOnAuthorBackground,

    /** Author-colored `\bbox`/`\fbox` border strokes. */
    Border,

    /** Author-colored `\cancel` strokes crossing the content they negate. */
    Cancellation,
}

/** Pure sRGB color policy; must be deterministic for equal inputs. */
fun interface MathAuthorColorAdapter {
    fun adapt(authorArgb: Int, role: MathAuthorColorRole, backdropArgb: Int): Int
}

/**
 * Rewrites every author paint color reachable from this result through [adapter].
 *
 * @param backdropArgb the effective page color behind the formula.
 * @param formulaArgb the theme content color the host will pass to the painter; used to give
 * theme-inherited content covered by an author background a chance to regain local contrast.
 */
fun MathLayoutResult.adaptAuthorColors(
    adapter: MathAuthorColorAdapter,
    backdropArgb: Int,
    formulaArgb: Int,
): MathLayoutResult {
    val adaptation = AuthorColorAdaptation(adapter, backdropArgb, formulaArgb)
    return copy(
        box = adaptation.adaptBox(box),
        fragments = fragments.map { it.copy(box = adaptation.adaptBox(it.box)) },
        taggedDisplayReplay = taggedDisplayReplay?.let { replay ->
            replay.copy(
                body = adaptation.adaptBox(replay.body),
                tags = replay.tags.map { it.copy(box = adaptation.adaptBox(it.box)) },
                pinnedClauses = replay.pinnedClauses.map { it.copy(box = adaptation.adaptBox(it.box)) },
            )
        },
    )
}

private class AuthorColorAdaptation(
    private val adapter: MathAuthorColorAdapter,
    private val backdropArgb: Int,
    private val formulaArgb: Int,
) {
    fun adaptBox(box: MathBox): MathBox {
        // Author background fills resolve first, in paint order, each against the adapted fill
        // beneath its own center (nested \bbox) or the page backdrop.
        val adaptedFills = mutableListOf<MathRulePlacement>()
        val rules = box.rules.map { rule ->
            if (rule.paintLayer != MathPaintLayer.Background) return@map rule
            val fill = topFillAt(adaptedFills, (rule.left + rule.right) / 2f, (rule.top + rule.bottom) / 2f)
            val adapted = rule.paintColor?.let { paint ->
                rule.copy(paintColor = adaptedPaint(paint, MathAuthorColorRole.AuthorBackground, beneathArgb(fill)))
            } ?: rule
            adaptedFills += adapted
            adapted
        }.map { rule ->
            if (rule.paintLayer == MathPaintLayer.Background) return@map rule
            // Construction rules (a radical overbar) also paint through their group.
            if (rule.constructionGroupId != null) return@map rule
            val paint = rule.paintColor ?: return@map rule
            val fill = topFillAt(adaptedFills, (rule.left + rule.right) / 2f, (rule.top + rule.bottom) / 2f)
            val role = when (rule.paintRole) {
                MathRulePaintRole.Border -> MathAuthorColorRole.Border
                MathRulePaintRole.Cancellation -> MathAuthorColorRole.Cancellation
                else -> if (fill != null) MathAuthorColorRole.ForegroundOnAuthorBackground else MathAuthorColorRole.Foreground
            }
            rule.copy(paintColor = adaptedPaint(paint, role, beneathArgb(fill)))
        }
        return box.copy(
            glyphs = box.glyphs.map { adaptGlyph(it, adaptedFills) },
            rules = rules,
            hostTextRuns = box.hostTextRuns.map { adaptHostText(it, adaptedFills) },
            constructionPaintGroups = box.constructionPaintGroups.map { adaptGroup(it, box, adaptedFills) },
        )
    }

    private fun adaptGlyph(glyph: MathGlyphPlacement, fills: List<MathRulePlacement>): MathGlyphPlacement {
        // Construction members are painted through their group's outline color, never their own.
        if (glyph.constructionGroupId != null) return glyph
        val x = glyph.x + glyph.advance / 2f
        val y = glyph.baselineY + (glyph.inkBounds.top + glyph.inkBounds.bottom) / 2f
        return glyph.copy(paintColor = adaptedForeground(glyph.paintColor, fills, x, y))
    }

    private fun adaptHostText(run: MathHostTextPlacement, fills: List<MathRulePlacement>): MathHostTextPlacement {
        val x = run.x + run.width / 2f
        val y = run.baselineY + (run.descent - run.ascent) / 2f
        return run.copy(paintColor = adaptedForeground(run.paintColor, fills, x, y))
    }

    private fun adaptGroup(
        group: MathConstructionPaintGroup,
        box: MathBox,
        fills: List<MathRulePlacement>,
    ): MathConstructionPaintGroup {
        // A construction (radical, stretched delimiter) paints as one outline whose glyph
        // baselines sit at the shape's bottom, so covering is judged at the ink-union center —
        // the same evidence the painter replays — not at an average baseline that can fall
        // outside the fill and split one visual unit across two backdrops.
        val glyphMembers = box.glyphs.filter { it.constructionGroupId == group.id }
        val ruleMembers = box.rules.filter { it.constructionGroupId == group.id }
        val lefts = glyphMembers.map { it.x + it.inkBounds.left } + ruleMembers.map { it.left }
        val rights = glyphMembers.map { it.x + it.inkBounds.right } + ruleMembers.map { it.right }
        val tops = glyphMembers.map { it.baselineY + it.inkBounds.top } + ruleMembers.map { it.top }
        val bottoms = glyphMembers.map { it.baselineY + it.inkBounds.bottom } + ruleMembers.map { it.bottom }
        if (lefts.isEmpty()) return group.copy(paintColor = adaptedForeground(group.paintColor, fills, Float.NaN, Float.NaN))
        val x = (lefts.min() + rights.max()) / 2f
        val y = (tops.min() + bottoms.max()) / 2f
        return group.copy(paintColor = adaptedForeground(group.paintColor, fills, x, y))
    }

    /**
     * Author-colored content always adapts; theme-inherited content adapts only when it sits on
     * an author background and the adapter changes it, so inherit stays inherit elsewhere.
     */
    private fun adaptedForeground(
        paint: MathPaintColor?,
        fills: List<MathRulePlacement>,
        x: Float,
        y: Float,
    ): MathPaintColor? {
        val fill = topFillAt(fills, x, y)
        if (paint != null) {
            val role = if (fill != null) MathAuthorColorRole.ForegroundOnAuthorBackground else MathAuthorColorRole.Foreground
            return adaptedPaint(paint, role, beneathArgb(fill))
        }
        if (fill == null) return null
        val adapted = adapter.adapt(formulaArgb, MathAuthorColorRole.InheritedOnAuthorBackground, beneathArgb(fill))
        return if (adapted == formulaArgb) null else adapted.toPaintColor()
    }

    private fun adaptedPaint(paint: MathPaintColor, role: MathAuthorColorRole, backdrop: Int): MathPaintColor =
        adapter.adapt(paint.argb, role, backdrop).toPaintColor()

    /** The topmost author fill covering the point, or null when only the page backdrop is beneath. */
    private fun topFillAt(fills: List<MathRulePlacement>, x: Float, y: Float): MathRulePlacement? =
        fills.lastOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }

    /** The paint the covering fill contributes as a backdrop, else the page backdrop. */
    private fun beneathArgb(fill: MathRulePlacement?): Int = fill?.paintColor?.argb ?: backdropArgb

    private fun Int.toPaintColor() = MathPaintColor(
        red = this shr 16 and 0xff,
        green = this shr 8 and 0xff,
        blue = this and 0xff,
        alpha = this ushr 24 and 0xff,
    )
}
