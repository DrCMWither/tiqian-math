# Third-party notices

## Lete Sans Math

`engine/src/jvmMain/resources/org/tiqian/math/fonts/LeteSansMath-Regular.otf` and
`engine/src/jvmMain/resources/org/tiqian/math/fonts/LeteSansMath-Bold.otf`
are Lete Sans Math v0.61, copyright its upstream contributors and distributed
under the SIL Open Font License 1.1.

- Project: <https://github.com/abccsss/LeteSansMath>
- Upstream revision: `9b4e62d8ad0a8cb9fe4c84f97b0589bf972620c4` (`v0.61`)
- Regular SHA-256: `ead643895be03f42f6fa201fb1176323f60dd330d4109387bac90bdf980fcf3e`
- Bold SHA-256: `a521f128db0821a9943e4f703103204d8982aeb39c933e12344c43e9d3b0907a`
- Complete license: `engine/src/jvmMain/resources/org/tiqian/math/fonts/OFL.txt`

No parser, AST, layout, or renderer source code is copied from the migration
backends used for comparison.

## KaTeX comparison data

The TeX math-style transitions and normal/tight atom-spacing matrices are
cross-checked against KaTeX's `src/Style.ts` and `src/spacingData.ts`. The
engine does not embed KaTeX's parser, AST, DOM tree, or renderer.

- Project: <https://github.com/KaTeX/KaTeX>
- Comparison revision: `69edd93f98dea2e98b831880a9b9dd5a13727165`
- License: MIT, preserved at `docs/licenses/KATEX-MIT.txt`

## STIX Two Math

`font/stix/src/jvmMain/resources/org/tiqian/math/fonts/STIXTwoMath-Regular.otf`
is STIX Two Math v2.12 b168, copyright the STIX Fonts Project Authors and
distributed under the SIL Open Font License 1.1.

- Project: <https://github.com/stipub/stixfonts>
- Upstream revision: `02b4b9b6093e2c5d6379b935ea340ea40f7e863b` (`v2.12`)
- SHA-256: `95bc2729e41faf93b0bcae9e96c4dc4da45855067fd0581e621e30734fe8d90b`
- Complete license: `font/stix/src/jvmMain/resources/org/tiqian/math/fonts/STIX-OFL.txt`

STIX is an optional comparison and CI resource. The default Compose artifact
does not depend on `font:stix`.
