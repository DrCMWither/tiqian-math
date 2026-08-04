plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm()
    android {
        namespace = "org.tiqian.math.layout"
        compileSdk = 37
        minSdk = 23
        withHostTest {}
    }
    js(IR) {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":parser"))
            api(project(":font:opentype"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
