pluginManagement {
    includeBuild("../gradle/build-logic")
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
        create("mihonx") {
            from(files("../gradle/mihon.versions.toml"))
        }
    }
}

rootProject.name = "JasmineShared"
include(":category")
project(":category").projectDir = file("../domain/category")
