# 提椠 Math

[![Maven Central](https://img.shields.io/maven-central/v/org.tiqian/math-compose?label=maven)](https://central.sonatype.com/artifact/org.tiqian/math-compose)

提椠 Math 是面向 Compose 的 TeX 数学排版库。

它使用 Compose 原生测量与绘制，公式可以继承正文的字号、字重和颜色，并根据数学字体提供的度量排布符号、上下标、分式、根式和大型运算符。

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/sample-formulas-white.svg">
  <img src="docs/images/sample-formulas-black.svg" alt="提椠 Math 正文公式样张，包含参与正文断行的行内公式与居中的展示公式">
</picture>

提椠 Math 更侧重于行内公式与正文排版之间的协作。公式可以向正文提供准确的基线、上下边界和断行位置，
较长的公式能够在运算符后换行，并参与[提椠](https://github.com/tiqian-cjk/tiqian)的两端对齐。`\text{...}` 和中日韩文字沿用 Compose 或提椠的字体选择与文字处理。

默认数学字体为 [Lete Sans Math](https://github.com/abccsss/LeteSansMath)，也可以使用其他数学字体并配置字重与 fallback。我们也预设了衬线数学字体
[STIX Two Math](https://github.com/stipub/stixfonts)，可以作为独立模块按需引入。

当前仍处于早期开发阶段，支持 Compose Desktop 和 Android 23 及以上版本。

## 使用

```kotlin
implementation("org.tiqian:math-compose:<version>")

// 可选：预设的 STIX Two Math 衬线字体
implementation("org.tiqian:math-font-stix:<version>")
```

```kotlin
TiqianMath(
    source = "\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}",
    mode = MathMode.Display,
    style = MaterialTheme.typography.bodyLarge,
)
```

选择 STIX Two Math：

```kotlin
val stix = rememberPackagedMathFontFamily("stix")

TiqianMath(
    source = source,
    fontFace = stix,
)
```

需要随应用内置其他数学字体时，可以在宿主构建中[预烘焙字体度量](docs/host-font-baking.md)。

## 体验与构建

```shell
./gradlew build
./gradlew :preview:run
```

公式内文字的接入方式见[接入说明](docs/host-text-run-provider.md)。

## 参考资料

- [OpenType MATH table](https://learn.microsoft.com/en-us/typography/opentype/spec/math)
- [TeX by Topic](https://texdoc.org/serve/texbytopic/0)
- [XeTeX](https://tug.org/xetex/)

## 许可证

Tiqian Math 以 [Mozilla Public License 2.0](LICENSE) 发布。第三方组件与字体见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
