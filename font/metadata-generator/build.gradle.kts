plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":font:opentype"))
}

application {
    mainClass.set("org.tiqian.math.font.generator.MainKt")
}

tasks.named<JavaExec>("run") {
    args(rootProject.projectDir.absolutePath)
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
