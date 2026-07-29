plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
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
