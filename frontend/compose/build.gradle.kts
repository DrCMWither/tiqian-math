import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm()
    android {
        namespace = "org.tiqian.math.compose"
        compileSdk = 37
        minSdk = 23
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":layout"))
            api("org.jetbrains.compose.runtime:runtime:1.11.1")
            api("org.jetbrains.compose.foundation:foundation:1.11.1")
            api("org.jetbrains.compose.material3:material3:1.9.0")
            api("org.jetbrains.compose.ui:ui:1.11.1")
        }
        jvmMain.dependencies {
            api(project(":font:skia"))
            implementation(compose.desktop.currentOs)
        }
        androidMain.dependencies {
            api(project(":font:android"))
            implementation("androidx.compose.runtime:runtime:1.11.2")
            implementation("androidx.compose.foundation:foundation:1.11.2")
            implementation("androidx.compose.ui:ui:1.11.2")
            implementation("androidx.compose.ui:ui-text:1.11.2")
            implementation("androidx.compose.ui:ui-graphics:1.11.2")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
            implementation("androidx.test.espresso:espresso-core:3.7.0")
            implementation("androidx.compose.ui:ui-test-junit4:1.11.2")
            implementation("androidx.compose.ui:ui-test-manifest:1.11.2")
        }
    }
}

// Compose 1.11.1 creates this Android-KMP device-test resource bridge without an output directory
// when the module has no Compose resources. Device tests use only Android assets from font:android.
tasks.matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }.configureEach {
    enabled = false
}

tasks.named<Test>("jvmTest") {
    systemProperty(
        "tiqianLeteSourceRegularFont",
        rootProject.layout.projectDirectory.file(
            "font/opentype/src/jvmMain/resources/org/tiqian/math/fonts/LeteSansMath-Regular.otf",
        ).asFile.absolutePath,
    )
}
