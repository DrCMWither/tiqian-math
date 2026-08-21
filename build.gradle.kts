import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("jvm") version "2.3.20" apply false
    id("com.android.library") version "9.3.1" apply false
    id("com.android.application") version "9.3.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

group = "org.tiqian"
version = providers.gradleProperty("tiqianVersion")
    .orElse(providers.environmentVariable("TIQIAN_VERSION"))
    .getOrElse("0.1.0-SNAPSHOT")

data class PublishedModule(
    val artifactId: String,
    val displayName: String,
    val description: String,
)

val publishedModules = mapOf(
    ":core" to PublishedModule("math-core", "Tiqian Math Core", "Core expression and style data types for the Tiqian math engine."),
    ":parser" to PublishedModule("math-parser", "Tiqian Math Parser", "TeX math parser for the Tiqian math engine."),
    ":font:opentype" to PublishedModule("math-font-opentype", "Tiqian Math OpenType", "OpenType MATH table model and reader for the Tiqian math engine."),
    ":font:stix" to PublishedModule("math-font-stix", "Tiqian Math STIX", "Optional prebaked STIX Two Math font family."),
    ":font:tooling" to PublishedModule("math-font-tooling", "Tiqian Math Font Tooling", "Build-time OpenType MATH metadata tooling."),
    ":font:gradle-plugin" to PublishedModule("math-gradle-plugin", "Tiqian Math Gradle Plugin", "Prebakes host-selected OpenType MATH fonts during application builds."),
    ":font:android" to PublishedModule("math-font-android", "Tiqian Math Android Font", "Native Android OpenType MATH font backend."),
    ":font:skia" to PublishedModule("math-font-skia", "Tiqian Math Skia Font", "Skia OpenType MATH font backend."),
    ":layout" to PublishedModule("math-layout", "Tiqian Math Layout", "OpenType MATH layout engine."),
    ":frontend:math-compose" to PublishedModule("math-compose", "Tiqian Math Compose", "Compose frontend for the Tiqian math engine."),
)

fun Project.configureMavenPublishing(module: PublishedModule) {
    pluginManager.apply("maven-publish")
    pluginManager.apply("signing")

    extensions.configure<PublishingExtension>("publishing") {
        repositories {
            maven {
                name = "central"
                url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                credentials {
                    username = providers.gradleProperty("mavenCentralUsername")
                        .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                        .orNull
                    password = providers.gradleProperty("mavenCentralPassword")
                        .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                        .orNull
                }
            }
        }
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension>("android") {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }
        afterEvaluate {
            extensions.configure<PublishingExtension>("publishing") {
                if (publications.findByName("release") == null) {
                    publications.create<MavenPublication>("release") {
                        from(components["release"])
                    }
                }
            }
        }
    }

    afterEvaluate {
        extensions.configure<PublishingExtension>("publishing") {
            publications.withType(MavenPublication::class.java).configureEach {
                val publicationName = name
                val isPluginMarker = publicationName.endsWith("PluginMarkerMaven")
                if (!isPluginMarker) {
                    val targetSuffix = artifactId.removePrefix(project.name)
                    artifactId = module.artifactId + targetSuffix
                    artifact(
                        tasks.register<Jar>("${publicationName}PublicationJavadocJar") {
                            archiveBaseName.set("${project.name}-$publicationName")
                            archiveClassifier.set("javadoc")
                            from(rootProject.file("LICENSE")) {
                                into("META-INF")
                            }
                        },
                    )
                }
                pom {
                    name.set(module.displayName)
                    description.set(module.description)
                    url.set("https://github.com/tiqian-cjk/tiqian-math")
                    licenses {
                        license {
                            name.set("Mozilla Public License 2.0")
                            url.set("https://www.mozilla.org/MPL/2.0/")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("123Duo3")
                            name.set("123Duo3")
                            email.set("123duo3@gmail.com")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/tiqian-cjk/tiqian-math.git")
                        developerConnection.set("scm:git:ssh://git@github.com/tiqian-cjk/tiqian-math.git")
                        url.set("https://github.com/tiqian-cjk/tiqian-math")
                    }
                }
            }
        }

        val signingKey = providers.gradleProperty("signingKey")
            .orElse(providers.environmentVariable("SIGNING_KEY"))
            .orNull
        if (!signingKey.isNullOrBlank()) {
            extensions.configure<SigningExtension>("signing") {
                useInMemoryPgpKeys(
                    providers.gradleProperty("signingKeyId")
                        .orElse(providers.environmentVariable("SIGNING_KEY_ID"))
                        .orNull,
                    signingKey,
                    providers.gradleProperty("signingPassword")
                        .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
                        .orNull,
                )
                sign(extensions.getByType(PublishingExtension::class.java).publications)
            }
        }
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension>("kotlin") {
            jvmToolchain(25)
            jvm {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(25)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension>("java") {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            withSourcesJar()
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        if (path in publishedModules) {
            pluginManager.apply("maven-publish")
            afterEvaluate {
                if (!pluginManager.hasPlugin("java-gradle-plugin")) {
                    extensions.configure<PublishingExtension>("publishing") {
                        if (publications.findByName("maven") == null) {
                            publications.create<MavenPublication>("maven") {
                                from(components["java"])
                            }
                        }
                    }
                }
            }
        }
    }

    val publishedModule = publishedModules[path]
    if (publishedModule != null) {
        configureMavenPublishing(publishedModule)
    }
}

tasks.register("publishMathComposeToMavenLocal") {
    group = "publishing"
    description = "Publishes every public Tiqian Math module to Maven Local with one lockstep version."
    dependsOn(publishedModules.keys.map { "$it:publishToMavenLocal" })
}

tasks.register("publishMathComposeToCentral") {
    group = "publishing"
    description = "Uploads every public Tiqian Math module to the Central Portal staging API."
    dependsOn(publishedModules.keys.map { "$it:publishAllPublicationsToCentralRepository" })
}
