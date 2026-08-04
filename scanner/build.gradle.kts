import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":font:skia"))
    implementation(project(":font:stix"))
    testImplementation(kotlin("test-junit5"))
}

application {
    mainClass.set("org.tiqian.math.scanner.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
