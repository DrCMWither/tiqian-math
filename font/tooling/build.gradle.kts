plugins {
    kotlin("jvm")
}
dependencies {
    api(project(":engine"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("tiqianMathRepositoryRoot", rootProject.projectDir.absolutePath)
}
