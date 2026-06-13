pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "transora"

include(
    ":backend:app",
    ":backend:shared",
    ":backend:iam",
    ":backend:scheduling",
    ":backend:inventory",
    ":backend:sales",
    ":backend:documents",
    ":backend:notifications",
    ":backend:boarding",
    ":backend:admin",
    ":hardware-agent",
)
