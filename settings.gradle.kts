plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.13.1"
}

rootProject.name = "kast"

includeBuild("build-logic")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.gradle.org/gradle/libs-releases")
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        maven("https://www.jetbrains.com/intellij-repository/releases")
    }
}

include(
    ":analysis-api",
    ":analysis-common",
    ":analysis-server",
    ":backend-intellij",
    ":backend-standalone",
    ":shared-testing",
)
