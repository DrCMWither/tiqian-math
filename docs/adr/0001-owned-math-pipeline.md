# ADR 0001: Tiqian-owned Markdown math pipeline

Status: Accepted (2026-07-28)

## Context

Tiqian needs baseline-faithful, breakable inline formulas whose measurement and
drawing can be replayed by Compose and later other KMP frontends. Patching a
document-oriented LaTeX renderer would keep parser, AST, geometry, and line-break
truth outside Tiqian's control.

## Decision

The engine owns its complete formula pipeline and targets the Markdown math
subset. Inline and display use the same layout engine with different initial
styles. The eight TeX math styles are explicit state. Font sizes, script
placement, fraction placement, rules, italics correction, and delimiter variants
use data read from the selected OpenType MATH font.

The first font is Lete Sans Math v0.61. The Desktop renderer shapes/measures and
draws the same glyph ids with the same Skia typeface and size. Top-level binary
and relation operators expose trailing break opportunities; taking one discards
the following glue and keeps the operator at the previous line end.

## Consequences

The first slice supports only atoms, paired scripts, fractions, binomials, style
scopes, and bounded host macros. Unsupported syntax is diagnostic rather than a
claim of compatibility. Later radicals and general delimiters must extend the
same AST, MATH evidence, layout, dump, and replay contracts.

## References

- [OpenType 1.9 MATH table](https://learn.microsoft.com/en-us/typography/opentype/otspec190/math)
- [KaTeX source](https://github.com/KaTeX/KaTeX) as a behavioral comparison
