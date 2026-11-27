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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StreApp"

include(":app")
include(":core-model")
include(":core-video")
include(":core-audio")
include(":core-thermal")
include(":source-uvc")
include(":source-camera")
include(":source-screen")
include(":source-remote")
include(":stream")
include(":chat")
include(":debug-overlay")
