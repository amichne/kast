plugins {
    base
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

tasks.register("buildIdeaPlugin") {
    group = "distribution"
    description = "Builds the IDEA plugin zip under backend-idea/build/distributions."
    dependsOn(":backend-idea:buildPlugin")
}

tasks.register("stageHeadlessDist") {
    group = "distribution"
    description =
        "Builds a clean staged backend-headless tree under backend-headless/build/portable-dist/backend-headless."
    dependsOn(":backend-headless:syncPortableDist")
}

tasks.register("buildHeadlessPortableZip") {
    group = "distribution"
    description = "Builds the versioned portable backend-headless zip under backend-headless/build/distributions."
    dependsOn(":backend-headless:portableDistZip")
}

tasks.register<Copy>("stageOpenApiSpec") {
    group = "distribution"
    description = "Copies the generated OpenAPI spec to dist/openapi.yaml."
    dependsOn(":analysis-api:generateOpenApiSpec")
    from(layout.projectDirectory.file("cli-rs/protocol/openapi.yaml"))
    into(layout.projectDirectory.dir("dist"))
}

val kastHomeDirectory: File = providers.environmentVariable("HOME")
    .orNull
    ?.let(::file)
    ?: file(".")

val cargoHomeDirectory: File = providers.environmentVariable("CARGO_HOME")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(::file)
    ?: kastHomeDirectory.resolve(".cargo")

fun resolveCargoExecutable(): String {
    providers.environmentVariable("CARGO")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { return it }
    val cargoHomeExecutable = cargoHomeDirectory.resolve("bin/cargo")
    if (cargoHomeExecutable.isFile) {
        return cargoHomeExecutable.absolutePath
    }
    val homeExecutable = kastHomeDirectory.resolve(".cargo/bin/cargo")
    if (homeExecutable.isFile) {
        return homeExecutable.absolutePath
    }
    return "cargo"
}

val cliCompiledBinary: RegularFile = layout.projectDirectory.file("cli-rs/target/debug/kast")
val cliDevelopmentBinary: RegularFile = layout.projectDirectory.file("cli-rs/target/debug/kastctl")
val resolvedCargoExecutable = resolveCargoExecutable()
val developmentIdeaPluginArchive: RegularFile = layout.projectDirectory.file(
    "backend-idea/build/distributions/backend-idea-${version}.zip",
)
val cleanDevelopmentMachine: Provider<Boolean> = providers.gradleProperty("kastDevelopmentClean")
    .map { value -> value.toBooleanStrict() }
    .orElse(false)

val buildDevelopmentCli: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the repo-local Rust Kast multicall CLI in debug mode."
    environment("KAST_VERSION", project.version.toString())
    commandLine(
        resolvedCargoExecutable,
        "build",
        "--manifest-path",
        layout.projectDirectory.file("cli-rs/Cargo.toml").asFile.absolutePath,
        "--locked",
    )
}

val stageDevelopmentControlCli: TaskProvider<Copy> by tasks.registering(Copy::class) {
    dependsOn(buildDevelopmentCli)
    from(cliCompiledBinary)
    into(cliDevelopmentBinary.asFile.parentFile)
    rename { "kastctl" }
    filePermissions {
        unix("755")
    }
}

tasks.register<Exec>("refreshDevelopmentMachine") {
    group = "distribution"
    description = "Replaces the active installation through the sole setup transaction."
    dependsOn(stageDevelopmentControlCli, ":backend-idea:buildPlugin")
    commandLine(
        cliDevelopmentBinary.asFile.absolutePath,
        "--output",
        "json",
        "setup",
        "--idea-plugin",
        developmentIdeaPluginArchive.asFile.absolutePath,
    )
    if (cleanDevelopmentMachine.get()) {
        args("--force")
    }
}
