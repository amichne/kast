plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version "4.6.1"
}

val semanticFixturesEnabled = providers.environmentVariable("KAST_SEMANTIC_FIXTURES").map { value ->
    when (value) {
        "true" -> true
        "false" -> false
        else -> error("KAST_SEMANTIC_FIXTURES must be true or false")
    }
}.getOrElse(false)
if (!semanticFixturesEnabled) {
    val excludedFixtures = file("config/ci/semantic-fixture-tasks.txt").readLines()
    require(excludedFixtures.isNotEmpty() && excludedFixtures.all { it.matches(Regex(":[A-Za-z][A-Za-z0-9:]*")) } &&
        excludedFixtures.distinct().size == excludedFixtures.size) { "Invalid semantic fixture task inventory" }
    gradle.startParameter.setExcludedTaskNames(gradle.startParameter.excludedTaskNames + excludedFixtures)
    logger.lifecycle("semantic-fixtures: SKIPPED; set KAST_SEMANTIC_FIXTURES=true to enable")
} else {
    logger.lifecycle("semantic-fixtures: ENABLED")
}

develocity {
    buildScan {
        publishing.onlyIf { false }
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set(providers.gradleProperty("kastBuildScanTerms").orElse("no"))
        uploadInBackground.set(false)
        obfuscation {
            username { "redacted" }
            hostname { "redacted" }
            ipAddresses { emptyList() }
        }
        if (gradle.startParameter.isBuildScan) {
            value("sourceRevision", providers.gradleProperty("kastSourceRevision").orElse(providers.exec {
                commandLine("git", "rev-parse", "HEAD")
                workingDir(settingsDir)
            }.standardOutput.asText.map(String::trim)).get())
            listOf("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB", "GITHUB_SHA").forEach { key ->
                providers.environmentVariable(key).orNull?.let { value(key, it) }
            }
        }
    }
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
    ":distribution:cli",
    ":distribution:managed",
    ":protocol:contract",
    ":protocol:registry",
    ":protocol:wire",
    ":workspace:contract",
    ":workspace:intellij-read",
    ":symbol:contract",
    ":symbol:service",
    ":symbol:intellij",
    ":source:contract",
    ":source:service",
    ":source:intellij",
    ":relation:contract",
    ":relation:service",
    ":relation:intellij",
    ":traversal:contract",
    ":traversal:service",
    ":query:contract",
    ":query:service",
    ":query:protocol",
    ":topology:contract",
    ":topology:build",
    ":topology:service",
    ":topology:intellij",
    ":diagnostic:contract",
    ":diagnostic:service",
    ":diagnostic:intellij",
    ":change:contract",
    ":change:plan",
    ":change:protocol",
    ":change:apply",
    ":change:verify",
    ":change:recovery",
    ":change:intellij",
    ":evidence:contract",
    ":evidence:sqlite",
    ":evidence:topology-sqlite",
    ":runtime:hosted",
    ":app-server",
    ":cli",
)

include(*cleanSlateProjects.toTypedArray())

run {
    val sourceGroup = providers.gradleProperty("GROUP").get()
    val gitDescribeVersion: Provider<String> = providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v*", "--long", "--always")
        workingDir(settingsDir)
        isIgnoreExitValue = true
    }.standardOutput.asText.map { raw ->
        val trimmed = raw.trim()
        val regex = Regex("""^v?(\d+\.\d+\.\d+)-(\d+)-g([0-9a-f]+)$""")
        regex.matchEntire(trimmed)?.let { m ->
            val base = m.groupValues[1]
            val distance = m.groupValues[2].toInt()
            val sha = m.groupValues[3]
            if (distance == 0) base else "$base-${m.groupValues[2]}-g$sha"
        } ?: trimmed.removePrefix("v").ifEmpty { "0.0.0-unknown" }
    }
    val sourceVersion = providers.gradleProperty("version")
        .orElse(providers.gradleProperty("VERSION"))
        .orElse(gitDescribeVersion)
        .get()


    val controlIdentity = providers.gradleProperty("controlVersion").getOrElse(sourceVersion)
    val hostIdentity = providers.gradleProperty("hostedPluginVersion").getOrElse(sourceVersion)
    gradle.lifecycle.beforeProject {
        group = sourceGroup
        version = when (path) {
            ":cli", ":app-server", ":distribution:cli" -> controlIdentity
            ":runtime:hosted" -> hostIdentity
            else -> sourceVersion
        }
    }
}
