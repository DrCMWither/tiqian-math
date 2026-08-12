# 宿主字体

应用可以在构建时选择随包发布的 OpenType MATH 字体。提椠 Math 的 Gradle 插件会读取这些字体，
生成度量数据，并把字体、度量和清单一起接入 Android assets 或 JVM/Android 类路径资源。

在应用模块或专门存放字体的资源模块启用插件：

```kotlin
plugins {
    id("org.tiqian.math.fonts") version "<version>"
}
```

声明应用需要的字体：

```kotlin
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontWeight

tiqianMathFonts {
    family("stix") {
        fontClass.set(MathFontClass.Serif)
        face("regular") {
            source.set(layout.projectDirectory.file("src/main/math-fonts/STIXTwoMath-Regular.otf"))
            weight.set(MathFontWeight.Regular)
        }
    }
}
```

在 Compose 中按声明时的名称装载：

```kotlin
val mathFont = rememberPackagedMathFontFamily("stix")

TiqianMath(
    source = source,
    fontFace = mathFont,
)
```

同一个 family 可以声明 Regular 和 Bold 两个 face。应用需要在运行时切换多个字体时，应在构建脚本中
同时声明这些 family；运行时切换只会选择已经随应用打包的字体。

插件支持 Android application、Android library 和 Kotlin Multiplatform Android library。若声明放在
独立资源模块中，应用需要像依赖普通资源库一样依赖该模块。

STIX Two Math 已另行发布为预烘焙模块；不需要自带字体文件时可以直接依赖：

```kotlin
implementation("org.tiqian:math-font-stix:<version>")
```

运行时仍使用 `rememberPackagedMathFontFamily("stix")`。该模块是可选依赖，不会随
`math-compose` 默认下载。
