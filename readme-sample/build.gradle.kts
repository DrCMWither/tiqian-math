plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":platforms:jvm:skia"))
    implementation(project(":engine"))
    implementation("org.tiqian:tiqian-engine:${rootProject.version}")
    implementation("org.tiqian:tiqian-jvm-skia:${rootProject.version}")
}

val blackSvg = rootProject.layout.projectDirectory.file("docs/images/sample-formulas-black.svg")
val whiteSvg = rootProject.layout.projectDirectory.file("docs/images/sample-formulas-white.svg")

tasks.register<JavaExec>("generateReadmeSample") {
    group = "documentation"
    description = "Generates the README sample through Tiqian paragraph layout and Math fragments."
    dependsOn("classes")
    mainClass.set("org.tiqian.math.sample.ReadmeSampleMainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(blackSvg.asFile.absolutePath, whiteSvg.asFile.absolutePath)
    outputs.files(blackSvg, whiteSvg)
}
