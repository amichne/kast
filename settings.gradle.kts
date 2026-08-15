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

val migrationProjects = listOf(
    ":analysis-api",
    ":analysis-server",
    ":change:contract",
    ":change:apply:intellij",
    ":change:apply:service",
    ":change:apply:spi",
    ":change:journal:contract",
    ":change:journal:sqlite",
    ":change:plan:intellij",
    ":change:plan:service",
    ":change:plan:spi",
    ":change:recovery:contract",
    ":change:recovery:filesystem",
    ":change:recovery:service",
    ":change:recovery:spi",
    ":change:verify:intellij",
    ":change:verify:service",
    ":change:verify:spi",
    ":evidence:contract",
    ":evidence:spi",
    ":evidence:sqlite",
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

val cleanSlateProjects = listOf(
    ":kernel",
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
    ":cli",
    ":indexer",
)

val materializedCleanSlateProjects = cleanSlateProjects.filter { projectPath ->
    file(projectPath.removePrefix(":").replace(':', '/')).isDirectory
}

include(*(migrationProjects + materializedCleanSlateProjects).distinct().toTypedArray())
