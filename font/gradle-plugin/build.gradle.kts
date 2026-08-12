plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

val pluginTestAgp = configurations.create("pluginTestAgp")

dependencies {
    implementation(project(":font:tooling"))
    compileOnly("com.android.tools.build:gradle-api:9.3.1")
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    pluginTestAgp("com.android.tools.build:gradle-api:9.3.1")
}

gradlePlugin {
    plugins {
        create("tiqianMathFonts") {
            id = "org.tiqian.math.fonts"
            implementationClass = "org.tiqian.math.gradle.TiqianMathFontsPlugin"
            displayName = "Tiqian Math Fonts"
            description = "Prebakes host-selected OpenType MATH fonts into application resources."
        }
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("tiqianMathRepositoryRoot", rootProject.projectDir.absolutePath)
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(pluginTestAgp)
}
