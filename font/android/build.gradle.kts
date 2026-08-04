plugins {
    id("com.android.library")
}

android {
    namespace = "org.tiqian.math.font.android"
    compileSdk = 37
    ndkVersion = "29.0.13599879"

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":layout"))
    implementation("com.viliussutkus89.ndk.thirdparty:harfbuzz-ndk26-static:8.3.0-beta-4")
    implementation("com.viliussutkus89.ndk.thirdparty:freetype-ndk26-static:2.13.2-beta-8")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(kotlin("test"))
}
