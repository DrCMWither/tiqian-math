plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":font:skia"))
    implementation(project(":layout"))
    implementation("org.tiqian:tiqian-layout:${rootProject.version}")
    implementation("org.tiqian:tiqian-shaping-skia:${rootProject.version}")
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
