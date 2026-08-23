# ADR 0002: engine 四合一与按平台的仓库重组

Status: Accepted (2026-08-23)

## Context

tiqian-math 原有 13 个 Gradle 模块，其中 `core` / `parser` / `font/opentype` / `layout`
四者 target 集完全一致（jvm + android + js），各自产出一整套 publication。这与主仓库
tiqian（ADR 0055 / 0056）遇到的问题同源：org.tiqian 组织的 Maven Central 文件配额（每轮
全套件约 4,500 文件，上限每月 1,000）是结构性瓶颈，Kotlin Multiplatform 的
模块 × target × 每 publication 的 jar/pom/module/sources/javadoc × 签名与校验和是文件数乘法的
主要来源。三仓（tiqian / tiqian-math / tiqian-markdown）配额按组织合并计。

主仓库已按「engine 单模块合并 + 按宿主平台重组」落地。tiqian-math 对齐同一取舍，
同时把发布坐标改为平台优先命名。

## Decision

1. **`core` / `parser` / `font/opentype` / `layout` 真源码合并为单一发布模块 `:engine`**
   （物理路径 `engine/`，artifactId `math-engine`，Android namespace `org.tiqian.math.engine`）。
   四者源码按 `org.tiqian.math.{core,parser,layout,font.opentype}` 包分簇进一个 Gradle 模块，
   包名、source range 与公共 API 全部保留；对应源集（commonMain/commonTest/jvmMain/jvmTest/
   androidMain 及 jvmMain 资源）逐一合并，原四模块间的 project 依赖消失。KMP 无法「源码分开、
   发布合一」（klib / metadata 合不了），故必须真源码合并。代价：失去四个概念层之间依赖方向的
   编译期强制，改由包结构纪律与 review 保证。

2. **顶层按宿主平台重组**：`font/android` → `platforms/android/font`（artifactId
   `math-font-android` → `math-android-font`）；`font/skia` → `platforms/jvm/skia`
   （`math-font-skia` → `math-jvm-skia`）；`frontend/compose` → `platforms/compose/compose`
   （artifactId `math-compose` 不变）。清理了 `settings.gradle.kts` 里
   `project(":frontend:math-compose").projectDir = file("frontend/compose")` 的路径映射，
   新平台模块直接落在与逻辑 Gradle 路径一致的物理目录。`font/{stix,tooling,gradle-plugin,
   metadata-generator}`、`scanner`、`preview`、`demo`、`readme-sample` 位置不动，只更新其中
   对旧路径 / 坐标的 `project()` 与制品引用。

3. **平台优先的发布坐标**：`math-android-font`、`math-jvm-skia`，与主仓库 tiqian 的
   `tiqian-android-*` / `tiqian-jvm-*` 命名对齐。artifactId 由根 `build.gradle.kts` 的
   `publishedModules` 显式钉死、不绑物理路径或 project.name。

4. **上游 tiqian 坐标更新**：`readme-sample` 对 `org.tiqian:tiqian-layout` /
   `tiqian-shaping-skia` 的引用改指 `tiqian-engine` / `tiqian-jvm-skia`（对应 tiqian ADR 0056）。

5. **新增 Central Portal SNAPSHOT 通道**：根构建加 `centralSnapshots` 发布仓库，端点
   `central.sonatype.com/repository/maven-snapshots/`，凭证与 release staging 同一套 Portal
   token；聚合任务 `publishMathToCentralSnapshots` 与 `publishMathToCentral` 同构。正式版走
   staging，alpha / 开发版发 snapshot。tiqian-math 没有 Kotlin/Native target，无需
   ADR 0055 的 native publication 远端过滤。`publishMathComposeToMavenLocal` 名称保持不变
   （`enable-local-suite.sh` / `verify-maven-local.sh` 依赖它）；原 `publishMathComposeToCentral`
   更名为 `publishMathToCentral`。

## Consequences

- Central release 的 publication 任务数（`publishMathToCentral` 图内
  `publish*PublicationToCentralRepository`）从 **28 降到 16**：engine 合并把 core/parser/
  opentype/layout 的 16 份（各 4：KotlinMultiplatform + jvm + android + js）压成单模块 4 份，
  其余 12 个模块 publication 不变。文件数随 publication 数同比例下降。
- 坐标变化：`math-core` / `math-parser` / `math-font-opentype` / `math-layout` 四个 artifact →
  `math-engine` 一个；`math-font-android` → `math-android-font`，`math-font-skia` →
  `math-jvm-skia`；`math-compose` / `math-font-stix` 等不变。tiqian-markdown 只消费
  `math-compose`（不变），不受坐标改名影响。pre-release，无兼容转发。
- CI（`publish.yml` / `android-device.yml`）、oracle 与文档中的模块任务路径 / 目录路径随之更新；
  ADR 属历史记录，保留当时路径不改。
- 纯移动，行为不变：`./gradlew build` 全绿，engine 跨 jvm / js / android 编译与测试、
  Compose / Android demo / scanner / 字体工具构建、`readme-sample` 对 mavenLocal 新 tiqian 坐标
  的解析均已验证；golden / oracle 测试零 diff。
- SNAPSHOT 自动 90 天清理、不进搜索索引：需要长期可复现的引用不得指向 snapshot。是否计入配额
  官方未写死，启用后先发一次、回 Usage Center 确认数字未动再全面切换 alpha 节奏。
