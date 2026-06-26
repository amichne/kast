import java.util.zip.ZipInputStream

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

@DisableCachingByDefault(because = "Installs into a mutable local JetBrains profile")
abstract class InstallDevelopmentIdeaPluginTask : DefaultTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @get:InputFile
    abstract val pluginArchive: RegularFileProperty

    @get:Internal
    abstract val pluginsDirectory: DirectoryProperty

    @get:Input
    abstract val replacedPluginDirectoryNames: ListProperty<String>

    @TaskAction
    fun install() {
        val archive = pluginArchive.get().asFile
        if (!archive.isFile) {
            throw GradleException("Development IDEA plugin archive was not built: ${archive.absolutePath}")
        }

        val targetRoot = pluginsDirectory.get().asFile
        targetRoot.mkdirs()
        replacedPluginDirectoryNames.get().forEach { name ->
            targetRoot.resolve(name).deleteRecursively()
        }

        val canonicalTargetRoot = targetRoot.canonicalFile.toPath()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val destination = targetRoot.resolve(entry.name).canonicalFile
                if (!destination.toPath().startsWith(canonicalTargetRoot)) {
                    throw GradleException("Refusing to extract plugin archive entry outside ${targetRoot.absolutePath}: ${entry.name}")
                }
                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile.mkdirs()
                    destination.outputStream().buffered().use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
            }
        }

        logger.lifecycle("Installed development IDEA plugin at {}", targetRoot.resolve("backend-idea"))
    }
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
    from(layout.projectDirectory.file("cli-rs/protocol/openapi.yaml"))
    into(layout.projectDirectory.dir("dist"))
}

data class JetBrainsProfileCandidate(
    val profileDirectory: File,
    val year: Int,
    val minor: Int,
    val patch: Int,
)

val kastHomeDirectory: File = providers.environmentVariable("HOME")
    .orNull
    ?.let(::file)
    ?: file(".")

val kastBinDirectory: File = providers.environmentVariable("KAST_BIN_DIR")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(::file)
    ?: kastHomeDirectory.resolve(".local/bin")

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

val cliDebugBinary: RegularFile = layout.projectDirectory.file("cli-rs/target/debug/kast")
val cargoExecutable = resolveCargoExecutable()
val kastDevBinary = kastBinDirectory.absoluteFile.normalize().resolve("kast-dev")

val buildDevelopmentCli: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the repo-local Rust kast CLI in debug mode."
    commandLine(
        cargoExecutable,
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

fun defaultDevelopmentShell(): String =
    providers.gradleProperty("kastDevShell")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    ?: providers.environmentVariable("SHELL")
        .orNull
        ?.substringAfterLast('/')
        ?.takeIf { it == "bash" || it == "zsh" }
    ?: "zsh"

fun developmentShellProfileArg(): List<String> =
    providers.gradleProperty("kastDevShellProfile")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { listOf("--profile", file(it).absoluteFile.normalize().absolutePath) }
    ?: emptyList()

val installDevelopmentShell: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Installs shell PATH and completion integration for kast-dev."
    dependsOn("installDevelopmentCli")
    commandLine(
        listOf(
            kastDevBinary.absolutePath,
            "--output",
            "json",
            "machine",
            "shell",
            "--shell",
            defaultDevelopmentShell(),
            "--command-name",
            "kast-dev",
        ) + developmentShellProfileArg()
    )
}

fun parseIntellijIdeaProfile(profileDirectory: File): JetBrainsProfileCandidate? {
    val version = profileDirectory.name.removePrefix("IntelliJIdea")
    if (version == profileDirectory.name || version.isBlank()) return null
    val parts = version.split(".")
    if (parts.size !in 2..3) return null
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return JetBrainsProfileCandidate(profileDirectory, year, minor, patch)
}

fun newestIntellijIdeaProfile(configRoot: File): File? =
    configRoot
        .listFiles()
        ?.asSequence()
        ?.filter(File::isDirectory)
        ?.mapNotNull(::parseIntellijIdeaProfile)
        ?.maxWithOrNull(
            compareBy<JetBrainsProfileCandidate> { it.year }
                .thenBy { it.minor }
                .thenBy { it.patch }
                .thenBy { it.profileDirectory.name }
        )
        ?.profileDirectory

fun jetBrainsConfigRoot(): File =
    providers.gradleProperty("kastDevJetBrainsConfigRoot")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::file)
    ?: kastHomeDirectory.resolve("Library/Application Support/JetBrains")

fun resolveJetBrainsProfile(
    raw: String,
    configRoot: File,
): File {
    val candidate = File(raw)
    return if (candidate.isAbsolute || raw.contains('/') || raw.contains('\\')) {
        file(raw)
    } else {
        configRoot.resolve(raw)
    }
}

fun resolveDevelopmentJetBrainsPluginsDir(): File {
    providers.gradleProperty("kastDevJetBrainsPluginsDir")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { return file(it).absoluteFile.normalize() }

    val configRoot = jetBrainsConfigRoot()
    providers.gradleProperty("kastDevJetBrainsProfile")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { raw ->
            val profile = resolveJetBrainsProfile(raw, configRoot).absoluteFile.normalize()
            return if (profile.name == "plugins") profile else profile.resolve("plugins")
        }

    val profile = newestIntellijIdeaProfile(configRoot)
                  ?: throw GradleException(
                      "No IntelliJIdea profile was found under ${configRoot.absolutePath}. " +
                      "Pass -PkastDevJetBrainsProfile=<profile> or -PkastDevJetBrainsPluginsDir=<plugins-dir>."
                  )
    return profile.resolve("plugins").absoluteFile.normalize()
}

val developmentJetBrainsPluginsDir: Provider<File> = providers.provider {
    resolveDevelopmentJetBrainsPluginsDir()
}
val ideaPluginArchive = layout.projectDirectory
    .file("backend-idea/build/distributions/backend-idea-${version}.zip")
val developmentIdeaPluginDirectoryNames = listOf(
    "backend-idea",
    "io.github.amichne.kast",
    "Kast Analysis Backend",
)

val installDevelopmentIdeaPlugin: TaskProvider<InstallDevelopmentIdeaPluginTask> by tasks.registering(
    InstallDevelopmentIdeaPluginTask::class
) {
    group = "distribution"
    description = "Builds and installs the development IDEA plugin into a local JetBrains profile."
    dependsOn(":backend-idea:buildPlugin")
    pluginArchive.set(ideaPluginArchive)
    pluginsDirectory.set(layout.dir(developmentJetBrainsPluginsDir))
    replacedPluginDirectoryNames.set(developmentIdeaPluginDirectoryNames)
}

tasks.register("installDevelopmentLocal") {
    group = "distribution"
    description = "Installs kast-dev shell integration and replaces the local IDEA plugin with the development build."
    dependsOn(installDevelopmentShell, installDevelopmentIdeaPlugin)
}
