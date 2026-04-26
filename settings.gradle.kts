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
    ":kast-cli",
    ":analysis-server",
    ":backend-standalone",
    ":backend-shared",
    ":backend-intellij",
    ":shared-testing",
    ":parity-tests",
)
