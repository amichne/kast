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
    description = "Builds a clean staged backend-headless tree under backend-headless/build/portable-dist/backend-headless."
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
    from(layout.projectDirectory.file("docs/openapi.yaml"))
    into(layout.projectDirectory.dir("dist"))
}

fun readKastConfigValue(
    configFile: File,
    sectionName: String,
    keyName: String,
): String? {
    if (!configFile.isFile) return null
    var section = ""
    configFile.readLines().forEach { rawLine ->
        val line = rawLine.withoutTomlComment().trim()
        if (line.isBlank()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.removePrefix("[").removeSuffix("]").normalizeKastConfigPath()
            return@forEach
        }
        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach
        val key = listOf(section, line.substring(0, separator).trim())
            .filter(String::isNotBlank)
            .joinToString(".")
            .normalizeKastConfigPath()
        if (key == "$sectionName.$keyName".normalizeKastConfigPath()) {
            return line.substring(separator + 1).trim().parseTomlScalar()
        }
    }
    return null
}

fun String.withoutTomlComment(): String {
    var quoted = false
    var quote = '\u0000'
    var escaped = false
    forEachIndexed { index, char ->
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            quoted && char == quote -> quoted = false
            !quoted && (char == '"' || char == '\'') -> {
                quoted = true
                quote = char
            }
            !quoted && char == '#' -> return substring(0, index)
        }
    }
    return this
}

fun String.parseTomlScalar(): String {
    val trimmed = trim().removeSuffix(",").trim()
    if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        return trimmed.substring(1, trimmed.lastIndex)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    }
    if (trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'') {
        return trimmed.substring(1, trimmed.lastIndex)
    }
    return trimmed
}

fun String.normalizeKastConfigPath(): String =
    split('.').joinToString(".") { segment -> segment.filterNot { it == '-' || it == '_' }.lowercase() }

val kastHomeDirectory: File = providers.environmentVariable("HOME")
    .orNull
    ?.let(::file)
    ?: file(".")

val kastGlobalConfigFile: File = run {
    val configHome = providers.environmentVariable("KAST_CONFIG_HOME")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::file)
        ?: kastHomeDirectory.resolve(".config/kast")
    configHome.resolve("config.toml").absoluteFile.normalize()
}

val kastInstallRoot: File = readKastConfigValue(kastGlobalConfigFile, "paths", "installRoot")
    ?.let(::file)
    ?: kastHomeDirectory.resolve(".kast")

val kastBinDirectory: File = readKastConfigValue(kastGlobalConfigFile, "paths", "binDir")
    ?.let(::file)
    ?: kastInstallRoot.resolve("bin")

val cliDebugBinary: RegularFile = layout.projectDirectory.file("cli-rs/target/debug/kast")

val buildDevelopmentCli: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the repo-local Rust kast CLI in debug mode."
    commandLine(
        "cargo",
        "build",
        "--manifest-path",
        layout.projectDirectory.file("cli-rs/Cargo.toml").asFile.absolutePath,
        "--locked",
    )
}

tasks.register<Copy>("installDevelopmentCli") {
    group = "distribution"
    description = "Builds and installs the debug Rust CLI as kast-dev in the configured local Kast bin directory."
    dependsOn(buildDevelopmentCli)
    from(cliDebugBinary) {
        rename("kast", "kast-dev")
    }
    into(kastBinDirectory.absoluteFile.normalize())
    filePermissions {
        unix("755")
    }
}
