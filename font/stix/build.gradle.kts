plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(project(":font:opentype"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
