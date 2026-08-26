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

val cleanSlateProjects = listOf(
    ":kernel",
    ":distribution:contract",
    ":distribution:managed",
    ":protocol:contract",
    ":protocol:registry",
    ":protocol:wire",
    ":workspace:contract",
    ":workspace:service",
    ":workspace:intellij",
    ":symbol:contract",
    ":symbol:service",
    ":symbol:intellij",
    ":relation:contract",
    ":relation:service",
    ":relation:intellij",
    ":traversal:contract",
    ":traversal:service",
    ":topology:contract",
    ":topology:build",
    ":topology:service",
    ":topology:intellij",
    ":diagnostic:contract",
    ":diagnostic:service",
    ":diagnostic:intellij",
    ":change:contract",
    ":change:plan",
    ":change:apply",
    ":change:verify",
    ":change:recovery",
    ":change:intellij",
    ":evidence:contract",
    ":evidence:sqlite",
    ":runtime:server",
    ":runtime:composition",
    ":ide-plugin",
    ":cli",
    ":indexer",
)

include(*cleanSlateProjects.toTypedArray())
