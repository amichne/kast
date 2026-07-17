import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.security.MessageDigest
import javax.inject.Inject
import groovy.json.JsonOutput

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

@DisableCachingByDefault(because = "Installs into a mutable local JetBrains profile")
abstract class InstallDevelopmentIdeaPluginTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    private data class JetBrainsProfileCandidate(
        val profileDirectory: File,
        val year: Int,
        val minor: Int,
        val patch: Int,
    )

    init {
        outputs.upToDateWhen { false }
    }

    @get:InputFile
    abstract val pluginArchive: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val configuredPluginsDirectory: Property<String>

    @get:Input
    @get:Optional
    abstract val configuredProfile: Property<String>

    @get:Input
    abstract val jetBrainsConfigRootPath: Property<String>

    @get:Input
    abstract val projectDirectoryPath: Property<String>

    @get:Input
    abstract val replacedPluginDirectoryNames: ListProperty<String>

    @TaskAction
    fun install() {
        val archive = pluginArchive.get().asFile
        if (!archive.isFile) {
            throw GradleException("Development IDEA plugin archive was not built: ${archive.absolutePath}")
        }

        val targetRoot = resolvePluginsDirectory()
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

    private fun resolvePluginsDirectory(): File {
        configuredPluginsDirectory.orNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { return resolveProjectPath(it) }

        val configRoot = File(jetBrainsConfigRootPath.get()).absoluteFile.normalize()
        configuredProfile.orNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { raw ->
                val profile = resolveProfile(raw, configRoot).absoluteFile.normalize()
                return if (profile.name == "plugins") profile else profile.resolve("plugins")
            }

        runningProfile(configRoot)
            ?.let { return it.resolve("plugins").absoluteFile.normalize() }

        val profile = newestProfile(configRoot)
            ?: throw GradleException(
                "No IntelliJIdea profile was found under ${configRoot.absolutePath}. " +
                    "Pass -PkastDevJetBrainsProfile=<profile> or " +
                    "-PkastDevJetBrainsPluginsDir=<plugins-dir>."
            )
        return profile.resolve("plugins").absoluteFile.normalize()
    }

    private fun resolveProjectPath(raw: String): File {
        val candidate = File(raw)
        return (if (candidate.isAbsolute) candidate else File(projectDirectoryPath.get()).resolve(raw))
            .absoluteFile
            .normalize()
    }

    private fun resolveProfile(raw: String, configRoot: File): File =
        if (File(raw).isAbsolute || raw.contains('/') || raw.contains('\\')) {
            resolveProjectPath(raw)
        } else {
            configRoot.resolve(raw)
        }

    private fun runningProfile(configRoot: File): File? {
        val processArgs = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("ps", "-axo", "args")
            isIgnoreExitValue = true
            standardOutput = processArgs
        }
        return Regex("""/JetBrains/(IntelliJIdea\d{4}\.\d+(?:\.\d+)?)""")
            .findAll(processArgs.toString(Charsets.UTF_8))
            .map { match -> configRoot.resolve(match.groupValues[1]).absoluteFile.normalize() }
            .firstOrNull(File::isDirectory)
    }

    private fun newestProfile(configRoot: File): File? =
        configRoot
            .listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.mapNotNull(::parseProfile)
            ?.maxWithOrNull(
                compareBy<JetBrainsProfileCandidate> { it.year }
                    .thenBy { it.minor }
                    .thenBy { it.patch }
                    .thenBy { it.profileDirectory.name }
            )
            ?.profileDirectory

    private fun parseProfile(profileDirectory: File): JetBrainsProfileCandidate? {
        val version = profileDirectory.name.removePrefix("IntelliJIdea")
        if (version == profileDirectory.name || version.isBlank()) return null
        val parts = version.split(".")
        if (parts.size !in 2..3) return null
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return JetBrainsProfileCandidate(profileDirectory, year, minor, patch)
    }
}

@DisableCachingByDefault(because = "Cargo owns incremental compilation and the target directory")
abstract class BuildSourceBoundCliTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceSnapshotFile: RegularFileProperty

    @get:Input
    abstract val cargoExecutable: Property<String>

    @get:Input
    abstract val implementationVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cargoManifest: RegularFileProperty

    @get:OutputDirectory
    abstract val targetDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val snapshot = sourceSnapshotFile.get().asFile.readText()
        val sourceSha256 = Regex(
            """"sourceTreeSha256"\s*:\s*"([0-9a-f]{64})"""",
        ).find(snapshot)?.groupValues?.get(1)
            ?: throw GradleException(
                "Local-development source snapshot has no valid sourceTreeSha256",
            )
        execOperations.exec {
            environment("KAST_VERSION", implementationVersion.get())
            environment("KAST_LOCAL_SOURCE_SHA256", sourceSha256)
            commandLine(
                cargoExecutable.get(),
                "build",
                "--manifest-path",
                cargoManifest.get().asFile.absolutePath,
                "--locked",
                "--release",
                "--target-dir",
                targetDirectory.get().asFile.absolutePath,
            )
        }.assertNormalExitValue()
    }
}

@DisableCachingByDefault(because = "Publishes and revalidates a local immutable generation")
abstract class PrepareLocalDevelopmentGenerationTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val sourceRootPath: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceSnapshotFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val cliBinary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val cliProvenance: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val backendDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val backendProvenance: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val skillSource: RegularFileProperty

    @get:OutputDirectory
    abstract val preparedGenerationsDirectory: DirectoryProperty

    @get:OutputFile
    abstract val preparedGenerationPointer: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun prepare() {
        val snapshot = sourceSnapshotFile.get().asFile.readText()
        val commit = Regex(""""gitCommit"\s*:\s*"([0-9a-f]{40,64})"""")
            .find(snapshot)
            ?.groupValues
            ?.get(1)
            ?: throw GradleException("Local-development source snapshot has no valid gitCommit")
        val sourceSha256 = Regex(""""sourceTreeSha256"\s*:\s*"([0-9a-f]{64})"""")
            .find(snapshot)
            ?.groupValues
            ?.get(1)
            ?: throw GradleException(
                "Local-development source snapshot has no valid sourceTreeSha256",
            )
        val generationId = "${commit.take(12)}-$sourceSha256"
        val prepared = preparedGenerationsDirectory.get().dir(generationId).asFile
        execOperations.exec {
            commandLine(
                cliBinary.get().asFile.absolutePath,
                "--output",
                "json",
                "developer",
                "local",
                "prepare",
                "--source-root",
                sourceRootPath.get(),
                "--expected-source-snapshot",
                sourceSnapshotFile.get().asFile.absolutePath,
                "--cli-binary",
                cliBinary.get().asFile.absolutePath,
                "--cli-provenance",
                cliProvenance.get().asFile.absolutePath,
                "--backend-directory",
                backendDirectory.get().asFile.absolutePath,
                "--backend-provenance",
                backendProvenance.get().asFile.absolutePath,
                "--output-directory",
                prepared.absolutePath,
            )
        }.assertNormalExitValue()
        preparedGenerationPointer.get().asFile.apply {
            parentFile.mkdirs()
            writeText("${prepared.absoluteFile.normalize().absolutePath}\n")
        }
    }
}

@DisableCachingByDefault(because = "Mutates receipt-owned local development state")
abstract class ActivateLocalDevelopmentGenerationTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val sourceRootPath: Property<String>

    @get:Input
    abstract val workspaceRootPath: Property<String>

    @get:Input
    abstract val prefixPath: Property<String>

    @get:Input
    @get:Optional
    abstract val preparedGenerationPath: Property<String>

    @get:Internal
    abstract val preparedGenerationPointer: RegularFileProperty

    @TaskAction
    fun activate() {
        val prepared = preparedGenerationPath.orNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::File)
            ?: preparedGenerationPointer.orNull
                ?.asFile
                ?.takeIf(File::isFile)
                ?.readText()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(::File)
            ?: throw GradleException(
                "No prepared local generation is selected. Run prepareDevelopmentLocalGeneration or pass " +
                    "-PkastLocalPreparedGeneration=<directory>.",
            )
        val canonicalPrepared = prepared.absoluteFile.normalize()
        val preparedCli = canonicalPrepared.resolve("bin/kast")
        if (!preparedCli.isFile) {
            throw GradleException(
                "Prepared local generation has no executable CLI: ${preparedCli.absolutePath}",
            )
        }
        execOperations.exec {
            commandLine(
                preparedCli.absolutePath,
                "--output",
                "json",
                "developer",
                "local",
                "activate",
                "--source-root",
                sourceRootPath.get(),
                "--workspace-root",
                workspaceRootPath.get(),
                "--prefix",
                prefixPath.get(),
                "--prepared-generation",
                canonicalPrepared.absolutePath,
            )
        }.assertNormalExitValue()
    }
}

@DisableCachingByDefault(because = "Packages one already-verified local generation")
abstract class PackageLocalDevelopmentGenerationTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val sourceRootPath: Property<String>

    @get:Internal
    abstract val preparedGenerationPointer: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packagerScript: RegularFileProperty

    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    @get:OutputFile
    abstract val checksumFile: RegularFileProperty

    @TaskAction
    fun packageGeneration() {
        val prepared = preparedGenerationPointer.get().asFile
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::File)
            ?: throw GradleException(
                "No prepared local generation is selected. Run prepareDevelopmentLocalGeneration.",
            )
        execOperations.exec {
            commandLine(
                packagerScript.get().asFile.absolutePath,
                "--source-root",
                sourceRootPath.get(),
                "--prepared-generation",
                prepared.absoluteFile.normalize().absolutePath,
                "--output",
                archiveFile.get().asFile.absolutePath,
            )
        }.assertNormalExitValue()
    }
}

@DisableCachingByDefault(because = "Mutates receipt-owned local development state")
abstract class RemoveDevelopmentLocalTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val prefixPath: Property<String>

    @get:Input
    abstract val workspaceRootPath: Property<String>

    @get:Input
    abstract val installedControllerPath: Property<String>

    @get:Input
    @get:Optional
    abstract val recoveryControllerPath: Property<String>

    @get:Input
    abstract val checkoutControllerPath: Property<String>

    @get:Input
    abstract val bootstrapControllerPath: Property<String>

    @TaskAction
    fun remove() {
        val installedController = java.io.File(installedControllerPath.get())
        val explicitRecoveryController = recoveryControllerPath.orNull
            ?.takeIf(String::isNotBlank)
            ?.let { path -> java.io.File(path) }
        val checkoutController = java.io.File(checkoutControllerPath.get())
        val bootstrapController = java.io.File(bootstrapControllerPath.get())
        val controller = when {
            installedController.isFile -> installedController
            explicitRecoveryController != null -> explicitRecoveryController
            checkoutController.isFile -> checkoutController
            bootstrapController.isFile -> bootstrapController
            else -> throw GradleException(
                "removeDevelopmentLocal cannot find the installed controller or a checkout recovery controller; " +
                    "expected ${installedController.absolutePath}, ${checkoutController.absolutePath}, " +
                    "or ${bootstrapController.absolutePath}"
            )
        }
        if (!controller.isFile) {
            throw GradleException(
                "kastLocalRecoveryController is not an executable file: ${controller.absolutePath}"
            )
        }
        execOperations.exec {
            commandLine(
                controller.absolutePath,
                "--output",
                "json",
                "developer",
                "local",
                "remove",
                "--prefix",
                prefixPath.get(),
                "--workspace-root",
                workspaceRootPath.get(),
            )
        }.assertNormalExitValue()
    }
}

@CacheableTask
abstract class WriteLocalBackendComponentManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceSnapshotFile: RegularFileProperty

    @get:Internal
    abstract val backendDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeManifest() {
        val sourceSnapshot = sourceSnapshotFile.get().asFile.readText()
        val sourceSha256 = Regex(
            """"sourceTreeSha256"\s*:\s*"([0-9a-f]{64})"""",
        ).find(sourceSnapshot)?.groupValues?.get(1)
            ?: throw GradleException(
                "Local-development source snapshot has no valid sourceTreeSha256",
            )
        val root = backendDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val components = componentFiles.files
            .map { file ->
                val path = file.toPath().toAbsolutePath().normalize()
                val relative = root.relativize(path).joinToString("/")
                mapOf(
                    "kind" to localBackendComponentKind(file.name),
                    "path" to relative,
                    "sha256" to sha256(file.readBytes()),
                )
            }
            .sortedBy { component -> component.getValue("kind") }
        val expectedKinds = setOf(
            "analysis-api",
            "analysis-server",
            "backend-headless-launcher",
            "backend-headless-plugin-descriptor",
            "backend-idea",
            "backend-shared",
            "index-store",
        )
        val actualKinds = components.map { component -> component.getValue("kind") }
        if (actualKinds.toSet() != expectedKinds || actualKinds.size != expectedKinds.size) {
            throw GradleException(
                "Local backend component manifest found $actualKinds; expected ${expectedKinds.sorted()}",
            )
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                JsonOutput.prettyPrint(
                    JsonOutput.toJson(
                        mapOf(
                            "schemaVersion" to 1,
                            "sourceTreeSha256" to sourceSha256,
                            "components" to components,
                        ),
                    ),
                ) + "\n",
            )
        }
    }

    private fun localBackendComponentKind(name: String): String = when {
        name.startsWith("analysis-api-") && name.endsWith(".jar") -> "analysis-api"
        name.startsWith("analysis-server-") && name.endsWith(".jar") -> "analysis-server"
        name.startsWith("backend-headless-") && name.endsWith("-launcher.jar") ->
            "backend-headless-launcher"
        name.startsWith("backend-headless-") && name.endsWith("-plugin-descriptor.jar") ->
            "backend-headless-plugin-descriptor"
        name.startsWith("backend-idea-") && name.endsWith(".jar") -> "backend-idea"
        name.startsWith("backend-shared-") && name.endsWith(".jar") -> "backend-shared"
        name.startsWith("index-store-") && name.endsWith(".jar") -> "index-store"
        else -> throw GradleException("Unexpected producer-owned backend component: $name")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
val cliLocalDevelopmentBootstrapBinary: RegularFile =
    layout.projectDirectory.file("cli-rs/target/local-bootstrap/release/kast")
val cliLocalDevelopmentTargetDirectory: Directory = layout.projectDirectory.dir("cli-rs/target")
val cliLocalDevelopmentBinary: RegularFile =
    cliLocalDevelopmentTargetDirectory.file("release/kast")
val resolvedCargoExecutable = resolveCargoExecutable()
val kastDevBinary = kastBinDirectory.absoluteFile.normalize().resolve("kast-dev")

val buildDevelopmentCli: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the repo-local Rust kast CLI in debug mode."
    environment("KAST_VERSION", project.version.toString())
    commandLine(
        resolvedCargoExecutable,
        "build",
        "--manifest-path",
        layout.projectDirectory.file("cli-rs/Cargo.toml").asFile.absolutePath,
        "--locked",
    )
}

val buildLocalDevelopmentCli: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the optimized CLI used by revision-coherent local authority."
    environment("KAST_VERSION", project.version.toString())
    commandLine(
        resolvedCargoExecutable,
        "build",
        "--manifest-path",
        layout.projectDirectory.file("cli-rs/Cargo.toml").asFile.absolutePath,
        "--locked",
        "--release",
        "--target-dir",
        layout.projectDirectory.dir("cli-rs/target/local-bootstrap").asFile.absolutePath,
    )
}

val capturedDevelopmentSourceSnapshotFile = layout.buildDirectory.file(
    "local-development/source-snapshot.json"
)
val configuredDevelopmentSourceSnapshotFile = providers
    .gradleProperty("kastLocalSourceSnapshot")
    .map { path -> layout.projectDirectory.file(path) }
val developmentSourceSnapshotFile = configuredDevelopmentSourceSnapshotFile
    .orElse(capturedDevelopmentSourceSnapshotFile)
val developmentCliProvenanceFile = layout.buildDirectory.file(
    "local-development/cli-provenance.json"
)
val developmentBackendProvenanceFile = layout.buildDirectory.file(
    "local-development/backend-provenance.json"
)
val developmentPreparedGenerationsDirectory = layout.buildDirectory.dir(
    "local-development/prepared-generations"
)
val developmentPreparedGenerationPointer = layout.buildDirectory.file(
    "local-development/prepared-generation-path.txt"
)

val captureDevelopmentSourceSnapshot: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Captures the exact checkout source identity before local artifact production."
    dependsOn(buildLocalDevelopmentCli)
    commandLine(
        cliLocalDevelopmentBootstrapBinary.asFile.absolutePath,
        "--output",
        "json",
        "developer",
        "local",
        "snapshot",
        "--source-root",
        rootDir.absolutePath,
        "--output-file",
        capturedDevelopmentSourceSnapshotFile.get().asFile.absolutePath,
    )
}

val rebuildLocalDevelopmentCli: TaskProvider<BuildSourceBoundCliTask> by tasks.registering(
    BuildSourceBoundCliTask::class,
) {
    group = "build"
    description = "Rebuilds the local CLI after the source snapshot is fixed."
    if (!configuredDevelopmentSourceSnapshotFile.isPresent) {
        dependsOn(captureDevelopmentSourceSnapshot)
    }
    sourceSnapshotFile.set(developmentSourceSnapshotFile)
    cargoExecutable.set(resolvedCargoExecutable)
    implementationVersion.set(project.version.toString())
    cargoManifest.set(layout.projectDirectory.file("cli-rs/Cargo.toml"))
    targetDirectory.set(cliLocalDevelopmentTargetDirectory)
}

subprojects {
    tasks.configureEach {
        mustRunAfter(captureDevelopmentSourceSnapshot)
    }
}

val writeLocalBackendComponentManifest: TaskProvider<WriteLocalBackendComponentManifestTask> by tasks.registering(
    WriteLocalBackendComponentManifestTask::class,
) {
    group = "build"
    description = "Binds every repo-produced headless backend JAR to the captured source build."
    dependsOn(":backend-headless:syncPortableDist")
    if (!configuredDevelopmentSourceSnapshotFile.isPresent) {
        dependsOn(captureDevelopmentSourceSnapshot)
    }
    sourceSnapshotFile.set(developmentSourceSnapshotFile)
    val portableBackend = layout.projectDirectory.dir(
        "backend-headless/build/portable-dist/backend-headless",
    )
    backendDirectory.set(portableBackend)
    componentFiles.from(
        fileTree(portableBackend) {
            include("runtime-libs/backend-headless-*-launcher.jar")
            include("idea-home/plugins/kast-headless/lib/analysis-api-*.jar")
            include("idea-home/plugins/kast-headless/lib/analysis-server-*.jar")
            include("idea-home/plugins/kast-headless/lib/backend-headless-*-plugin-descriptor.jar")
            include("idea-home/plugins/kast-headless/lib/backend-idea-*.jar")
            include("idea-home/plugins/kast-headless/lib/backend-shared-*.jar")
            include("idea-home/plugins/kast-headless/lib/index-store-*.jar")
        },
    )
    outputFile.set(
        layout.buildDirectory.file("local-development/backend-component-manifest.json"),
    )
}

val stageDevelopmentBackend: TaskProvider<Sync> by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages the headless backend with producer-emitted local source identity."
    dependsOn(
        writeLocalBackendComponentManifest,
        ":backend-headless:syncPortableDist",
        ":backend-headless:localHeadlessPluginImplementationJar",
    )
    if (!configuredDevelopmentSourceSnapshotFile.isPresent) {
        dependsOn(captureDevelopmentSourceSnapshot)
    }
    from(
        layout.projectDirectory.dir(
            "backend-headless/build/portable-dist/backend-headless"
        )
    ) {
        exclude("idea-home/plugins/kast-headless/lib/backend-headless-*-plugin.jar")
    }
    from(
        layout.projectDirectory.file(
            "backend-headless/build/local-development/backend-headless-local-plugin.jar"
        )
    ) {
        into("idea-home/plugins/kast-headless/lib")
    }
    into(layout.buildDirectory.dir("local-development/backend-headless"))
}

tasks.register<org.gradle.api.tasks.bundling.Zip>("packageSourceBoundDevelopmentBackend") {
    group = "distribution"
    description = "Packages the already-built source-bound local headless backend."
    dependsOn(stageDevelopmentBackend)
    archiveFileName.set("kast-local-source-bound-backend.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    into("backend-headless") {
        from(layout.buildDirectory.dir("local-development/backend-headless"))
    }
}

val attestDevelopmentCli: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Binds the exact rebuilt CLI bytes to the captured checkout source."
    dependsOn(rebuildLocalDevelopmentCli)
    commandLine(
        cliLocalDevelopmentBinary.asFile.absolutePath,
        "--output",
        "json",
        "developer",
        "local",
        "attest",
        "--source-root",
        rootDir.absolutePath,
        "--expected-source-snapshot",
        developmentSourceSnapshotFile.get().asFile.absolutePath,
        "--artifact-kind",
        "cli",
        "--artifact",
        cliLocalDevelopmentBinary.asFile.absolutePath,
        "--output-file",
        developmentCliProvenanceFile.get().asFile.absolutePath,
    )
}

val attestDevelopmentBackend: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "build"
    description = "Binds the exact portable headless backend bytes to the captured checkout source."
    dependsOn(rebuildLocalDevelopmentCli, stageDevelopmentBackend)
    commandLine(
        cliLocalDevelopmentBinary.asFile.absolutePath,
        "--output",
        "json",
        "developer",
        "local",
        "attest",
        "--source-root",
        rootDir.absolutePath,
        "--expected-source-snapshot",
        developmentSourceSnapshotFile.get().asFile.absolutePath,
        "--artifact-kind",
        "headless-backend",
        "--artifact",
        layout.buildDirectory
            .dir("local-development/backend-headless")
            .get()
            .asFile
            .absolutePath,
        "--output-file",
        developmentBackendProvenanceFile.get().asFile.absolutePath,
    )
}

val prepareDevelopmentLocalGeneration: TaskProvider<PrepareLocalDevelopmentGenerationTask> by tasks.registering(
    PrepareLocalDevelopmentGenerationTask::class,
) {
    group = "build"
    description = "Prepares one immutable source-attested local development generation."
    dependsOn(attestDevelopmentCli, attestDevelopmentBackend)
    sourceRootPath.set(rootDir.absolutePath)
    sourceSnapshotFile.set(developmentSourceSnapshotFile)
    cliBinary.set(cliLocalDevelopmentBinary)
    cliProvenance.set(developmentCliProvenanceFile)
    backendDirectory.set(layout.buildDirectory.dir("local-development/backend-headless"))
    backendProvenance.set(developmentBackendProvenanceFile)
    skillSource.set(layout.projectDirectory.file("cli-rs/resources/kast-skill/SKILL.md"))
    preparedGenerationsDirectory.set(developmentPreparedGenerationsDirectory)
    preparedGenerationPointer.set(developmentPreparedGenerationPointer)
}

tasks.register<Copy>("installDevelopmentCli") {
    group = "distribution"
    description = "Builds and installs the debug Rust CLI as kast-dev without replacing ordinary kast authority."
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
            "developer",
            "machine",
            "shell",
            "--shell",
            defaultDevelopmentShell(),
            "--command-name",
            "kast-dev",
        ) + developmentShellProfileArg()
    )
}

val configureDevelopmentMachineDefaults: TaskProvider<Exec> by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Configures developer-machine defaults to use the IDEA plugin backend."
    dependsOn("installDevelopmentCli")
    commandLine(
        kastDevBinary.absolutePath,
        "--output",
        "json",
        "developer",
        "machine",
        "defaults",
    )
}

val ideaPluginArchive = layout.projectDirectory
    .file("backend-idea/build/distributions/backend-idea-${version}.zip")
val developmentIdeaPluginDirectoryNames = listOf(
    "backend-idea",
    "io.github.amichne.kast",
    "Kast Analysis Backend",
)
val configuredDevelopmentJetBrainsPluginsDirectory =
    providers.gradleProperty("kastDevJetBrainsPluginsDir").orNull
val configuredDevelopmentJetBrainsProfile =
    providers.gradleProperty("kastDevJetBrainsProfile").orNull
val configuredDevelopmentJetBrainsConfigRoot =
    providers.gradleProperty("kastDevJetBrainsConfigRoot")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { file(it).absoluteFile.normalize().path }
    ?: kastHomeDirectory.resolve("Library/Application Support/JetBrains").path

val installDevelopmentIdeaPlugin: TaskProvider<InstallDevelopmentIdeaPluginTask> by tasks.registering(
    InstallDevelopmentIdeaPluginTask::class
) {
    group = "distribution"
    description = "Builds and installs the development IDEA plugin into a local JetBrains profile."
    dependsOn(":backend-idea:buildPlugin")
    pluginArchive.set(ideaPluginArchive)
    configuredDevelopmentJetBrainsPluginsDirectory?.let(configuredPluginsDirectory::set)
    configuredDevelopmentJetBrainsProfile?.let(configuredProfile::set)
    jetBrainsConfigRootPath.set(configuredDevelopmentJetBrainsConfigRoot)
    projectDirectoryPath.set(layout.projectDirectory.asFile.absolutePath)
    replacedPluginDirectoryNames.set(developmentIdeaPluginDirectoryNames)
}

fun configuredLocalDevelopmentPrefix(): File =
    providers.gradleProperty("kastLocalPrefix")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::file)
    ?: rootDir.resolve(".kast/local-development")

fun configuredLocalDevelopmentWorkspace(): File =
    providers.gradleProperty("kastLocalWorkspaceRoot")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::file)
    ?: rootDir

val activateDevelopmentLocal: TaskProvider<ActivateLocalDevelopmentGenerationTask> by tasks.registering(
    ActivateLocalDevelopmentGenerationTask::class,
) {
    group = "distribution"
    description = "Activates one prepared local development generation without rebuilding it."
    sourceRootPath.set(rootDir.absolutePath)
    workspaceRootPath.set(configuredLocalDevelopmentWorkspace().absoluteFile.normalize().absolutePath)
    prefixPath.set(configuredLocalDevelopmentPrefix().absoluteFile.normalize().absolutePath)
    providers.gradleProperty("kastLocalPreparedGeneration").orNull?.let(preparedGenerationPath::set)
    preparedGenerationPointer.set(developmentPreparedGenerationPointer)
}

val refreshDevelopmentLocal by tasks.registering {
    group = "distribution"
    description = "Refreshes one revision-coherent local Kast development authority."
    dependsOn(prepareDevelopmentLocalGeneration, activateDevelopmentLocal)
}

activateDevelopmentLocal.configure {
    mustRunAfter(prepareDevelopmentLocalGeneration)
}

tasks.register<PackageLocalDevelopmentGenerationTask>("packageDevelopmentLocalGeneration") {
    group = "distribution"
    description = "Packages the immutable prepared local generation without rebuilding its components."
    dependsOn(prepareDevelopmentLocalGeneration)
    sourceRootPath.set(rootDir.absolutePath)
    preparedGenerationPointer.set(developmentPreparedGenerationPointer)
    packagerScript.set(layout.projectDirectory.file("scripts/package-prepared-local-generation.sh"))
    archiveFile.set(layout.buildDirectory.file("distributions/kast-local-prepared-generation.tar.zst"))
    checksumFile.set(layout.buildDirectory.file("distributions/kast-local-prepared-generation.sha256"))
}

tasks.register<Exec>("rollbackDevelopmentLocal") {
    group = "distribution"
    description = "Idempotently reactivates the explicitly selected validated previous local generation."
    val localPrefix = configuredLocalDevelopmentPrefix().absoluteFile.normalize()
    val requestedGeneration = providers.gradleProperty("kastLocalGeneration")
        .map(String::trim)
        .map { generation ->
            generation.takeIf(String::isNotEmpty)
                ?: throw GradleException(
                    "rollbackDevelopmentLocal requires -PkastLocalGeneration=<generation-id>"
                )
        }
    commandLine(
        localPrefix.resolve("bin/kast-dev").absolutePath,
        "--output",
        "json",
        "developer",
        "local",
        "rollback",
        "--prefix",
        localPrefix.absolutePath,
    )
    doFirst {
        val generation = requestedGeneration.orNull
            ?: throw GradleException(
                "rollbackDevelopmentLocal requires -PkastLocalGeneration=<generation-id>"
            )
        args("--to-generation", generation)
    }
}

tasks.register<RemoveDevelopmentLocalTask>("removeDevelopmentLocal") {
    group = "distribution"
    description = "Removes only receipt-owned local Kast state and restores ordinary authority."
    val localPrefix = configuredLocalDevelopmentPrefix().absoluteFile.normalize()
    prefixPath.set(localPrefix.absolutePath)
    workspaceRootPath.set(
        configuredLocalDevelopmentWorkspace().absoluteFile.normalize().absolutePath,
    )
    installedControllerPath.set(localPrefix.resolve("bin/kast-dev").absolutePath)
    recoveryControllerPath.set(
        providers.gradleProperty("kastLocalRecoveryController").map(String::trim),
    )
    checkoutControllerPath.set(cliLocalDevelopmentBinary.asFile.absolutePath)
    bootstrapControllerPath.set(cliLocalDevelopmentBootstrapBinary.asFile.absolutePath)
}

tasks.register("installDevelopmentLocal") {
    group = "distribution"
    description = "Installs kast-dev shell integration and replaces the local IDEA plugin with the development build."
    dependsOn(installDevelopmentShell, installDevelopmentIdeaPlugin, configureDevelopmentMachineDefaults)
}
