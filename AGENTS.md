# AGENTS.md

tiqian-math 是提椠（Tiqian）套件的数学排版层：TeX 子集解析（`parser`）、
OpenType MATH 布局（`layout`、`font/*`）与 Compose 呈现（`frontend/compose`）。
套件级的事实来源与完整约束见主仓库 [tiqian/AGENTS.md](../tiqian/AGENTS.md)。
布局结果必须可解释、可回放；golden/oracle 测试是行为基准，重构不得引起 diff。

## Build 与验证

```shell
./gradlew build
```

所有 Android Gradle 任务需要 `ANDROID_HOME`。本地联调经
`../tiqian-markdown/scripts/enable-local-suite.sh` 发布 SNAPSHOT 到 mavenLocal；
单独发布用 `./gradlew publishMathComposeToMavenLocal -PtiqianVersion=<ver>-SNAPSHOT`。

## 代码组织

与套件约定一致（约定而非 lint 强制，不要引入 ktlint 之类的工具）：

- 单个源文件尽量保持在 1000 行以下；新代码按功能簇分文件（layout 引擎按
  noad/构件类型如 `MathLayoutRadicals.kt`、`MathLayoutTables.kt`），超标文件按
  主仓库文档记录的机械等价手段拆分，并以 `./gradlew build` 全绿作为行为不变证据。
  内聚的数据词汇表（如 `MathAst.kt`）不为凑行数而拆。
- 主入口只做入口与接线（如 `preview` 的 `Main.kt`），实现放在按功能簇命名的文件里。

## 工作区与提交

工作区可能同时存在其他任务的改动。不要还原、格式化或提交无关文件；同一文件已有
并行改动时，先理解并在其上继续。提交沿用 `type(scope): subject` 单行标题，不加 body
与 trailer。
