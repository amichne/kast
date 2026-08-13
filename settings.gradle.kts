plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
    ":analysis-server",
    ":change:contract",
    ":change:journal:contract",
    ":change:journal:sqlite",
    ":change:plan:intellij",
    ":change:plan:service",
    ":change:plan:spi",
    ":change:recovery:contract",
    ":change:recovery:filesystem",
    ":change:recovery:service",
    ":change:recovery:spi",
    ":evidence:contract",
    ":evidence:spi",
    ":index-store",
    ":indexer",
    ":kernel",
    ":protocol:continuation",
    ":protocol:registry",
    ":symbol:contract",
    ":symbol:intellij",
    ":workspace:contract",
    ":workspace:intellij",
    ":workspace:service",
    ":workspace:spi",
)
