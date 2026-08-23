val tiqianSampleRepository = providers.gradleProperty("tiqianSampleRepository").orNull

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        tiqianSampleRepository?.let { repositoryPath ->
            maven {
                name = "tiqianSample"
                url = uri(repositoryPath)
                content { includeGroup("org.tiqian") }
            }
        }
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist/") {
                    name = "Node Distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                    content { includeModule("org.nodejs", "node") }
                }
            }
            filter { includeGroup("org.nodejs") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download") {
                    name = "Yarn Distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                    content { includeModule("com.yarnpkg", "yarn") }
                }
            }
            filter { includeGroup("com.yarnpkg") }
        }
    }
}

rootProject.name = "tiqian-math"

include(
    ":engine",
    ":font:tooling",
    ":font:gradle-plugin",
    ":font:metadata-generator",
    ":font:stix",
    ":platforms:android:font",
    ":platforms:jvm:skia",
    ":platforms:compose:compose",
    ":scanner",
    ":preview",
    ":demo:android-app",
)

project(":demo:android-app").projectDir = file("demo/android")

// README artwork is an explicit cross-repository integration check. Keep it out of the normal
// build graph; readme-sample/generate.sh publishes one Tiqian checkout to an isolated repository.
if (tiqianSampleRepository != null) {
    include(":readme-sample")
}
