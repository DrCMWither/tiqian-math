# Host text atoms in math layout

`MathText` and raw CJK text atoms are TeX/math semantics owned by math-compose, but their font
fallback and shaping are host responsibilities. `MathTextRunProvider` is the boundary between
those two decisions.

The math engine supplies one already-classified atom with its exact source range, resolved math
style size in pixels, requested weight, locale, and origin. A provider returns either a replayable
`MeasuredMathRun` or a structured `MathHostTextCapabilityIssue`. Runs carry clusters mapped to the
original source, positioned glyph ids, advance, ascent/descent, ink bounds, and stable face ids.
Each host glyph also carries `MathHostTextFaceDecision`: role, font key, requested/resolved weight,
selection reason, substitution reason, and source/cluster range. These facts are independent from
MATH-family fallback reasons and survive into `MathLayoutResult` and its dump.

Every returned face id must have exactly one replay owner. If both the MATH catalog and host text
catalog claim the same id, preflight returns `ReplayFaceOwnershipConflict`; it never resolves the
collision by catalog order.

`SkiaMathTextRunProvider.fromBytes` and `AndroidMathTextRunProvider.fromBytes` are explicit,
restricted single-face adapters for deterministic previews and simple LTR text. They do not run a
Unicode bidi paragraph algorithm or select fallback faces. RTL/bidi and scripts the adapter cannot
express produce `UnsupportedHostTextShaping`, rather than an incorrectly ordered run. With no
provider, a text atom produces `MissingTextRunProvider` and formula capability is
`MissingTextProvider`; it never silently falls back to the selected MATH face.

## Tiqian adapter contract

A future Tiqian adapter only needs to expose:

- host font selection and grapheme/face-run fallback for the supplied text, locale, size, and
  requested weight;
- shaping results with source clusters, glyph ids, placements, advances, logical metrics, and
  ink bounds from Tiqian's existing font/shaping pipeline;
- stable replay face ownership for those glyph ids on each platform.

The current common contract accepts platform output only when every positioned glyph has a stable
face id and can be replayed independently. A Tiqian shaping result whose `renderFontKey` is null and
which requires a platform whole-string draw must return `PlatformMultiFaceStringDraw`; math-compose
maps it to `NonReplayableHostTextRun` and formula-level fallback. Adapters must not invent a face id
or substitute glyph zero. A future platform-neutral string replay token is an explicit extension
option, but is not part of this alpha contract.

Multiple physical faces are supported when the host can expose stable per-glyph face ownership.
Their visual order and source clusters are accepted as supplied, so a future Tiqian adapter may
return fully bidi-resolved placements without math-compose reordering them.

It must not call Tiqian paragraph layout from inside a formula, and it must not decide TeX script
style, baseline shifts, collision constraints, or formula line breaking. This repository keeps no
source-tree dependency or absolute path to Tiqian.
