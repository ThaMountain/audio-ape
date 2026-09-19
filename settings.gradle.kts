pluginManagement {
    repositories {
        google()
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

rootProject.name = "AudioApe"
include(":app")
include(":core:database")
include(":core:model")
include(":core:storage")
include(":plugin:protocol")
include(":plugin:fixtures")
