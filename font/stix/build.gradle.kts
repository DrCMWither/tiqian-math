plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm()
    android {
        namespace = "org.tiqian.math.font.stix"
        compileSdk = 37
        minSdk = 23
    }

    sourceSets {
        jvmMain.dependencies {
            api(project(":font:opentype"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
