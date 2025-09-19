pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        flatDir {
            dirs("libs")
        }
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}
include(":app")
