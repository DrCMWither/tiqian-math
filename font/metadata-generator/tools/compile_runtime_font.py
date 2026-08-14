#!/usr/bin/env python3
"""Compile one OpenType MATH source into layout and replay-only runtime faces."""

from __future__ import annotations

import argparse
from pathlib import Path

from fontTools import subset
from fontTools.pens.boundsPen import BoundsPen
from fontTools.ttLib import TTFont


def glyph_bounds(font: TTFont, glyph_name: str):
    glyph_set = font.getGlyphSet()
    pen = BoundsPen(glyph_set)
    glyph_set[glyph_name].draw(pen)
    return pen.bounds


def reachable_glyphs(font: TTFont) -> set[str]:
    reachable = {
        glyph
        for table in font["cmap"].tables
        if table.isUnicode()
        for glyph in table.cmap.values()
    }

    if "GSUB" in font:
        for feature in font["GSUB"].table.FeatureList.FeatureRecord:
            if feature.FeatureTag != "ssty":
                continue
            for lookup_index in feature.Feature.LookupListIndex:
                lookup = font["GSUB"].table.LookupList.Lookup[lookup_index]
                for subtable in lookup.SubTable:
                    alternates = getattr(subtable, "alternates", None)
                    if alternates is None:
                        continue
                    for glyph in tuple(reachable):
                        reachable.update(alternates.get(glyph, ()))

    variants = font["MATH"].table.MathVariants
    pairs = (
        (variants.VertGlyphCoverage, variants.VertGlyphConstruction),
        (variants.HorizGlyphCoverage, variants.HorizGlyphConstruction),
    )
    for coverage, constructions in pairs:
        for base, construction in zip(
            coverage.glyphs if coverage is not None else (),
            constructions or (),
        ):
            if base not in reachable:
                continue
            for variant in construction.MathGlyphVariantRecord or ():
                reachable.add(variant.VariantGlyph)
            if construction.GlyphAssembly is not None:
                for part in construction.GlyphAssembly.PartRecords:
                    reachable.add(part.glyph)
    return reachable


def compile_runtime_font(source: Path, layout_output: Path, runtime_output: Path) -> None:
    layout_output.parent.mkdir(parents=True, exist_ok=True)
    runtime_output.parent.mkdir(parents=True, exist_ok=True)
    options = subset.Options()
    options.retain_gids = True
    options.notdef_glyph = True
    options.notdef_outline = True
    options.recommended_glyphs = True
    options.layout_features = ["ssty"]

    font = subset.load_font(str(source), options, lazy=False)
    font.recalcTimestamp = False
    source_order = font.getGlyphOrder()
    source_reachable = reachable_glyphs(font)
    source_bounds = {glyph: glyph_bounds(font, glyph) for glyph in source_reachable}

    subsetter = subset.Subsetter(options=options)
    subsetter.populate(
        unicodes={
            scalar
            for table in font["cmap"].tables
            if table.isUnicode()
            for scalar in table.cmap
        },
    )
    subsetter.subset(font)
    subset.save_font(font, str(layout_output), options)

    layout = TTFont(layout_output, lazy=False, recalcTimestamp=False)
    if layout.getGlyphOrder() != source_order:
        raise RuntimeError("font compilation changed glyph ids")
    mismatches = [
        glyph
        for glyph, expected in source_bounds.items()
        if glyph_bounds(layout, glyph) != expected
    ]
    if mismatches:
        preview = ", ".join(mismatches[:8])
        raise RuntimeError(f"font compilation changed reachable outlines: {preview}")
    if layout_output.stat().st_size >= source.stat().st_size:
        raise RuntimeError("compiled layout font was not reduced")

    # The snapshot owns these tables after baking. Runtime replay keeps the exact
    # cmap, glyph ids, advances, outlines, and hinting programs.
    runtime = TTFont(layout_output, lazy=False, recalcTimestamp=False)
    for tag in ("MATH", "GSUB", "GPOS", "GDEF", "DSIG"):
        if tag in runtime:
            del runtime[tag]
    runtime.save(runtime_output, reorderTables=False)

    replay = TTFont(runtime_output, lazy=False, recalcTimestamp=False)
    if replay.getGlyphOrder() != layout.getGlyphOrder():
        raise RuntimeError("stripping layout tables changed glyph ids")
    replay_cmap = {
        scalar: glyph
        for table in replay["cmap"].tables
        if table.isUnicode()
        for scalar, glyph in table.cmap.items()
    }
    layout_cmap = {
        scalar: glyph
        for table in layout["cmap"].tables
        if table.isUnicode()
        for scalar, glyph in table.cmap.items()
    }
    if replay_cmap != layout_cmap:
        raise RuntimeError("stripping layout tables changed Unicode mapping")
    replay_metrics = replay["hmtx"].metrics
    layout_metrics = layout["hmtx"].metrics
    for glyph in source_reachable:
        if glyph_bounds(replay, glyph) != glyph_bounds(layout, glyph):
            raise RuntimeError(f"stripping layout tables changed {glyph} outline")
        if replay_metrics[glyph] != layout_metrics[glyph]:
            raise RuntimeError(f"stripping layout tables changed {glyph} metrics")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("layout_output", type=Path)
    parser.add_argument("runtime_output", type=Path)
    args = parser.parse_args()
    compile_runtime_font(args.source, args.layout_output, args.runtime_output)


if __name__ == "__main__":
    main()
