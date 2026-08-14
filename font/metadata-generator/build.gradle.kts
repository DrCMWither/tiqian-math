plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":font:tooling"))
}

application {
    mainClass.set("org.tiqian.math.font.generator.MainKt")
}

tasks.named<JavaExec>("run") {
    args(rootProject.projectDir.absolutePath)
}

val stixLayoutFont = providers.gradleProperty("tiqianStixLayoutFont")
    .orElse(providers.environmentVariable("TIQIAN_STIX_LAYOUT_FONT"))
val stixRuntimeFont = providers.gradleProperty("tiqianStixRuntimeFont")
    .orElse(providers.environmentVariable("TIQIAN_STIX_RUNTIME_FONT"))
val stixRuntimeMetadataDirectory = rootProject.layout.buildDirectory.dir("generated/stixRuntimeMetadata")

val leteRegularLayoutFont = providers.gradleProperty("tiqianLeteRegularLayoutFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_REGULAR_LAYOUT_FONT"))
val leteRegularRuntimeFont = providers.gradleProperty("tiqianLeteRegularRuntimeFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_REGULAR_RUNTIME_FONT"))
val leteBoldLayoutFont = providers.gradleProperty("tiqianLeteBoldLayoutFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_BOLD_LAYOUT_FONT"))
val leteBoldRuntimeFont = providers.gradleProperty("tiqianLeteBoldRuntimeFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_BOLD_RUNTIME_FONT"))
val leteRuntimeMetadataDirectory = rootProject.layout.buildDirectory.dir("generated/leteRuntimeMetadata")

tasks.register<JavaExec>("bakeStixRuntimeMetadata") {
    group = "build"
    description = "Bakes MATH metadata bound to the release STIX runtime font."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.tiqian.math.font.generator.StixRuntimeMetadataKt")
    inputs.file(stixLayoutFont)
    inputs.file(stixRuntimeFont)
    outputs.dir(stixRuntimeMetadataDirectory)
    doFirst {
        require(stixLayoutFont.isPresent) {
            "Set tiqianStixLayoutFont or TIQIAN_STIX_LAYOUT_FONT to the compiled layout OTF."
        }
        require(stixRuntimeFont.isPresent) {
            "Set tiqianStixRuntimeFont or TIQIAN_STIX_RUNTIME_FONT to the subset runtime OTF."
        }
        args(
            stixLayoutFont.get(),
            stixRuntimeFont.get(),
            stixRuntimeMetadataDirectory.get().asFile.absolutePath,
        )
    }
}

tasks.register<JavaExec>("bakeLeteRuntimeMetadata") {
    group = "build"
    description = "Bakes MATH metadata bound to the release Lete runtime fonts."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.tiqian.math.font.generator.LeteRuntimeMetadataKt")
    listOf(
        leteRegularLayoutFont,
        leteRegularRuntimeFont,
        leteBoldLayoutFont,
        leteBoldRuntimeFont,
    ).forEach(inputs::file)
    outputs.dir(leteRuntimeMetadataDirectory)
    doFirst {
        val missing = listOf(
            "TIQIAN_LETE_REGULAR_LAYOUT_FONT" to leteRegularLayoutFont,
            "TIQIAN_LETE_REGULAR_RUNTIME_FONT" to leteRegularRuntimeFont,
            "TIQIAN_LETE_BOLD_LAYOUT_FONT" to leteBoldLayoutFont,
            "TIQIAN_LETE_BOLD_RUNTIME_FONT" to leteBoldRuntimeFont,
        ).filterNot { it.second.isPresent }.map { it.first }
        require(missing.isEmpty()) { "Missing compiled Lete font inputs: ${missing.joinToString()}" }
        args(
            leteRegularLayoutFont.get(),
            leteRegularRuntimeFont.get(),
            leteBoldLayoutFont.get(),
            leteBoldRuntimeFont.get(),
            leteRuntimeMetadataDirectory.get().asFile.absolutePath,
        )
    }
}

val verifyBundledMathMetadata = tasks.register<JavaExec>("verifyBundledMathMetadata") {
    group = "verification"
    description = "Verifies checked-in Lete MATH snapshots against the exact bundled OTF files."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args(rootProject.projectDir.absolutePath, "--verify")
}

tasks.named("check") {
    dependsOn(verifyBundledMathMetadata)
}
