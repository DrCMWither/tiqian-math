import org.gradle.api.tasks.Sync

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val stixRuntimeFont = providers.gradleProperty("tiqianStixRuntimeFont")
    .orElse(providers.environmentVariable("TIQIAN_STIX_RUNTIME_FONT"))
val stixLayoutFont = providers.gradleProperty("tiqianStixLayoutFont")
    .orElse(providers.environmentVariable("TIQIAN_STIX_LAYOUT_FONT"))
val compiledMathResourcesEnabled = providers.gradleProperty("tiqianCompiledMathResources")
    .map(String::toBoolean)
    .getOrElse(false)
val compiledStixEnabled = compiledMathResourcesEnabled && stixLayoutFont.isPresent && stixRuntimeFont.isPresent
require(!compiledMathResourcesEnabled || stixLayoutFont.isPresent && stixRuntimeFont.isPresent) {
    "Compiled STIX packaging requires both layout and runtime faces."
}

val preparedStixResources = if (compiledStixEnabled) {
    val metadataDirectory = rootProject.layout.buildDirectory.dir("generated/stixRuntimeMetadata")
    tasks.register<Sync>("prepareStixRuntimeResources") {
        inputs.dir(metadataDirectory)
        from(layout.projectDirectory.dir("src/commonMain/resources")) {
            exclude("org/tiqian/math/host-fonts/stix/regular.otf")
            exclude("org/tiqian/math/host-fonts/stix/regular.tqmath")
            exclude("org/tiqian/math/host-fonts/stix/manifest.tqfont")
        }
        from(stixRuntimeFont) {
            into("org/tiqian/math/host-fonts/stix")
            rename { "regular.otf" }
        }
        from(metadataDirectory) {
            into("org/tiqian/math/host-fonts/stix")
        }
        into(layout.buildDirectory.dir("generated/stixRuntimeResources"))
        doFirst {
            listOf("regular.tqmath", "manifest.tqfont").forEach { name ->
                require(metadataDirectory.get().file(name).asFile.isFile) {
                    "Missing compiled STIX metadata $name; run :font:metadata-generator:bakeStixRuntimeMetadata first."
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
        namespace = "org.tiqian.math.font.stix"
        compileSdk = 37
        minSdk = 23
    }

    sourceSets {
        preparedStixResources?.let { prepared ->
            getByName("commonMain").resources.setSrcDirs(listOf(prepared.map { it.destinationDir }))
        }
        jvmMain.dependencies {
            api(project(":font:opentype"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
