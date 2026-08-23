import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val leteRegularLayoutFont = providers.gradleProperty("tiqianLeteRegularLayoutFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_REGULAR_LAYOUT_FONT"))
val leteRegularRuntimeFont = providers.gradleProperty("tiqianLeteRegularRuntimeFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_REGULAR_RUNTIME_FONT"))
val leteBoldLayoutFont = providers.gradleProperty("tiqianLeteBoldLayoutFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_BOLD_LAYOUT_FONT"))
val leteBoldRuntimeFont = providers.gradleProperty("tiqianLeteBoldRuntimeFont")
    .orElse(providers.environmentVariable("TIQIAN_LETE_BOLD_RUNTIME_FONT"))
val compiledMathResourcesEnabled = providers.gradleProperty("tiqianCompiledMathResources")
    .map(String::toBoolean)
    .getOrElse(false)
val compiledLeteInputs = listOf(
    leteRegularLayoutFont,
    leteRegularRuntimeFont,
    leteBoldLayoutFont,
    leteBoldRuntimeFont,
)
val compiledLeteEnabled = compiledMathResourcesEnabled && compiledLeteInputs.all { it.isPresent }
require(!compiledMathResourcesEnabled || compiledLeteInputs.all { it.isPresent }) {
    "Compiled Lete packaging requires both layout and runtime faces."
}
val preparedLeteResources = if (compiledLeteEnabled) {
    val metadataDirectory = rootProject.layout.buildDirectory.dir("generated/leteRuntimeMetadata")
    tasks.register<Sync>("prepareLeteRuntimeResources") {
        inputs.dir(metadataDirectory)
        from(layout.projectDirectory.dir("src/jvmMain/resources")) {
            exclude("org/tiqian/math/fonts/*.otf")
            exclude("org/tiqian/math/fonts/*.tqmath")
        }
        from(leteRegularRuntimeFont) {
            into("org/tiqian/math/fonts")
            rename { "LeteSansMath-Regular.otf" }
        }
        from(leteBoldRuntimeFont) {
            into("org/tiqian/math/fonts")
            rename { "LeteSansMath-Bold.otf" }
        }
        from(metadataDirectory) {
            into("org/tiqian/math/fonts")
        }
        into(layout.buildDirectory.dir("generated/leteRuntimeResources"))
        doFirst {
            listOf("LeteSansMath-Regular.tqmath", "LeteSansMath-Bold.tqmath").forEach { name ->
                require(metadataDirectory.get().file(name).asFile.isFile) {
                    "Missing compiled Lete metadata $name; run :font:metadata-generator:bakeLeteRuntimeMetadata first."
                }
            }
        }
    }
} else {
    null
}

kotlin {
    jvm()
    android {
        namespace = "org.tiqian.math.engine"
        compileSdk = 37
        minSdk = 23
        withHostTest {}
    }
    js(IR) {
        nodejs()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        preparedLeteResources?.let { prepared ->
            getByName("jvmMain").resources.setSrcDirs(listOf(prepared.map { it.destinationDir }))
        }
    }
}

tasks.named<Test>("jvmTest") {
    systemProperty(
        "tiqianLeteSourceRegularFont",
        layout.projectDirectory.file(
            "src/jvmMain/resources/org/tiqian/math/fonts/LeteSansMath-Regular.otf",
        ).asFile.absolutePath,
    )
}
