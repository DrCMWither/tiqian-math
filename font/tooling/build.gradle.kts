plugins {
    kotlin("jvm")
}
dependencies {
    api(project(":font:opentype"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("tiqianMathRepositoryRoot", rootProject.projectDir.absolutePath)
}
