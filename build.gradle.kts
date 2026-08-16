plugins {
    base
    id("kast.architecture")
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = providers.gradleProperty("GROUP").get()
val gitDescribeVersion: Provider<String> = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v*", "--long", "--always")
    workingDir(rootDir)
    isIgnoreExitValue = true
}.standardOutput.asText.map { raw ->
    // raw: v0.6.3-7-gb8c186d (tag-distance-sha) or a bare sha when no tags exist
    val trimmed = raw.trim()
    val regex = Regex("""^v?(\d+\.\d+\.\d+)-(\d+)-g([0-9a-f]+)$""")
    regex.matchEntire(trimmed)?.let { m ->
        val base = m.groupValues[1]
        val distance = m.groupValues[2].toInt()
        val sha = m.groupValues[3]
        if (distance == 0) base else "$base-${m.groupValues[2]}-g$sha"
    } ?: trimmed.removePrefix("v").ifEmpty { "0.0.0-unknown" }
}
version = providers.gradleProperty("version")
    .orElse(providers.gradleProperty("VERSION"))
    .orElse(gitDescribeVersion)
    .get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("stageIndexerDist") {
    group = "distribution"
    description =
        "Builds a clean staged indexer tree under indexer/build/portable-dist/indexer."
    dependsOn(":indexer:syncPortableDist")
}

tasks.register("buildIndexerPortableZip") {
    group = "distribution"
    description = "Builds the versioned portable indexer zip under indexer/build/distributions."
    dependsOn(":indexer:portableDistZip")
}

val installedProductDirectory = layout.buildDirectory.dir("installed-product")

val stageInstalledProduct by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the one installed Kotlin product without development classpaths."
    dependsOn(":cli:installDist", ":indexer:syncPortableDist")
    into(installedProductDirectory)
    from(project(":cli").layout.buildDirectory.dir("install/kast"))
    from(project(":indexer").layout.buildDirectory.dir("portable-dist/indexer")) {
        into("libexec/kast-indexer")
    }
}

tasks.register<Exec>("installedProductTest") {
    group = "verification"
    description = "Executes the public target surface through only the staged installed product."
    dependsOn(stageInstalledProduct)
    inputs.dir(installedProductDirectory)
    inputs.file(layout.projectDirectory.file("packaging/test-installed-product.sh"))
    outputs.upToDateWhen { false }
    environment(
        "KAST_INSTALLED_PRODUCT",
        installedProductDirectory.get().asFile.absolutePath,
    )
    commandLine("bash", layout.projectDirectory.file("packaging/test-installed-product.sh"))
}
