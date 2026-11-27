// Google's Maven only for Google's groups, so Gradle doesn't ask it for everything else first.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
        mavenCentral()
        // Google's mirror of Maven Central: when Central answers "not found" for a moment (it has, failing PR checks), Gradle asks here next.
        maven {
            name = "MavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
        mavenCentral()
        // Google's mirror of Maven Central: when Central answers "not found" for a moment (it has, failing PR checks), Gradle asks here next.
        maven {
            name = "MavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }
}

rootProject.name = "ONIT"
include(":app")
