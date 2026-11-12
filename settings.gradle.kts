pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepositories(
                ivy {
                    name = "mupdfLocal"
                    url = settingsDir.resolve("mupdf/dist").toURI()
                    patternLayout {
                        artifact("[artifact]-[revision].aar")
                    }
                    metadataSources {
                        artifact()
                    }
                },
                ivy {
                    name = "mupdfRelease"
                    url = uri("https://github.com/reelee81/ReePDF/releases/download/mupdf")
                    patternLayout {
                        artifact("[artifact]-[revision].aar")
                    }
                    metadataSources {
                        artifact()
                    }
                },
            )
            filter {
                includeModule("hu.reelee81.reepdf", "mupdf-fitz")
            }
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ReePDF"
include(":app")
include(":core")
include(":pdf")
include(":viewer")
