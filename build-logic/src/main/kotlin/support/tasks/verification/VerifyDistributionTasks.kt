package support.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private enum class NativeClassification { CONTROL, UNEXPECTED }

@Serializable
private enum class NativeInventoryOutcome { ACCEPTED, REJECTED }

@Serializable
private data class NativeCandidate(
    val path: String,
    val bytes: Long,
    val sha256: String,
    val headerHex: String,
    val classification: NativeClassification,
)

@Serializable
private data class NativeInventoryReport(
    val target: String,
    val outcome: NativeInventoryOutcome,
    val candidateCount: Int,
    val executableCount: Int,
    val omittedCandidates: Int,
    val candidates: List<NativeCandidate>,
    val schemaVersion: Int = 1,
    val stage: String = "native-inventory",
)

private val nativeInventoryJson = Json { encodeDefaults = true; prettyPrint = true }

@CacheableTask
abstract class VerifyControlDistributionTask : DefaultTask() {
    @get:OutputFile
    abstract val nativeInventoryReport: RegularFileProperty

    init {
        nativeInventoryReport.convention(project.layout.buildDirectory.file("reports/distribution/control-native-inventory.json"))
    }

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val controlDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val controlArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val qualifiedControlExecutable: RegularFileProperty

    @get:Input
    abstract val targetPlatform: Property<String>

    @get:Input
    abstract val maximumEntries: Property<Int>

    @get:Input
    abstract val maximumArchiveBytes: Property<Long>
    @get:Input
    abstract val maximumInstalledBytes: Property<Long>

    @TaskAction
    fun verify() {
        val root = controlDirectory.get().asFile.toPath()
        val archive = controlArchive.get().asFile
        val executable = root.resolve("share/kast/libexec/kast-management")
        check(Files.mismatch(executable, qualifiedControlExecutable.get().asFile.toPath()) == -1L) {
            "control payload differs from its Go producer"
        }
        val header = Files.newInputStream(executable).use { it.readNBytes(20) }
        check(nativeQueryHeaderMatches(header, targetPlatform.get())) {
            "control payload is not a native executable for ${targetPlatform.get()}"
        }
        check(archive.length() < maximumArchiveBytes.get()) {
            "control archive is ${archive.length()} bytes. It must be less than ${maximumArchiveBytes.get()} bytes."
        }
        val paths = Files.walk(root).use { stream -> stream.limit(maximumEntries.get().toLong() + 2).toList() }
        val entries = paths.map { path -> root.relativize(path).toString() }
        val payloadEntries = entries.filter(String::isNotEmpty)
        check(payloadEntries.size <= maximumEntries.get()) {
            "control product contains ${payloadEntries.size} entries; limit is ${maximumEntries.get()}"
        }
        // The Control executable runs one JVM entry from lib; no bytecode lives anywhere else.
        check(entries.none { entry -> entry.endsWith(".class") || (entry.endsWith(".jar") && !entry.startsWith("lib/")) }) {
            "control product contains JVM bytecode outside lib"
        }
        val retiredPayloadRoots = setOf("share/kast/container", "share/kast/task-vm", "share/kast/knowledge")
        check(entries.none { entry -> retiredPayloadRoots.any { entry == it || entry.startsWith("$it/") } }) {
            "control product contains a retired payload"
        }
        val nativeCandidates = paths.filter(Files::isRegularFile).mapNotNull { path ->
            Files.newInputStream(path).use { input ->
                val header = input.readNBytes(32)
                val native = java.util.HexFormat.of().formatHex(header.take(4).toByteArray()) in setOf(
                    "cffaedfe", "feedfacf", "cefaedfe", "feedface", "cafebabe", "bebafeca", "cafebabf", "bfbafeca", "7f454c46",
                )
                if (native) {
                    val classification =
                        if (path == executable) NativeClassification.CONTROL else NativeClassification.UNEXPECTED
                    Triple(path, header, classification)
                } else null
            }
        }
        val nativeExecutables = nativeCandidates.map { it.first }
        // Keep the evidence bounded while counting every admitted inventory entry.
        val recorded = nativeCandidates.sortedBy { root.relativize(it.first).toString() }.take(64).map { (path, header, classification) ->
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(Files.newInputStream(path), digest).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
            NativeCandidate(
                root.relativize(path).toString(), Files.size(path), java.util.HexFormat.of().formatHex(digest.digest()),
                java.util.HexFormat.of().formatHex(header), classification,
            )
        }
        val inventory = NativeInventoryReport(
            targetPlatform.get(),
            if (nativeExecutables == listOf(executable)) NativeInventoryOutcome.ACCEPTED else NativeInventoryOutcome.REJECTED,
            nativeCandidates.size, nativeExecutables.size, nativeCandidates.size - recorded.size, recorded,
        )
        val report = nativeInventoryReport.get().asFile.toPath()
        Files.createDirectories(report.parent)
        Files.writeString(report, nativeInventoryJson.encodeToString(inventory) + "\n")
        logger.lifecycle("Control native inventory: executables={}", nativeExecutables.size)
        logger.lifecycle("Control native inventory report: {}", report)
        check(nativeExecutables == listOf(executable)) {
            "control product must contain exactly one native executable"
        }
        logger.lifecycle("Control inventory: entries={}, remaining={}", payloadEntries.size, maximumEntries.get() - payloadEntries.size)
        val installedBytes = paths
            .filter(Files::isRegularFile)
            .sumOf(Files::size)
        check(installedBytes < maximumInstalledBytes.get()) {
            "installed control product is $installedBytes bytes. It must be less than ${maximumInstalledBytes.get()} bytes."
        }
        val required = listOf(
            "bin/kast",
            "bin/kast-query",
            "share/kast/libexec/kast-daemon",
            "share/kast/libexec/kast-service",
            "share/kast/libexec/kast-management",
            "share/kast/frontend.json",
            "share/kast/install.sh",
            "share/kast/jvm-control-v1",
            "share/kast/operation-registry.json",
            "share/kast/provider-catalog.json",
            "share/kast/configuration-schema.json",
            "share/kast/wire-schema.json",
            "share/kast/ide-host.json",
            "share/kast/licenses/LICENSE",
        )
        val libraries = entries.any { entry -> entry.startsWith("lib/") && entry.endsWith(".jar") }
        check(libraries && (required + guidanceDistributionEntries()).all(entries::contains)) {
            "control product is missing required entries"
        }
        check(Files.readString(root.resolve("share/kast/jvm-control-v1")) == "1\n") {
            "control JVM layout marker is unsupported"
        }
        val defaultPlugin = "share/kast/agent-tools/plugins/kast/"
        val defaultEffects = listOf(".mcp.json", "mcp.json")
        check(entries.none { entry -> defaultEffects.any { effect ->
            entry == defaultPlugin + effect || entry.startsWith(defaultPlugin + effect + "/")
        } }) { "default guidance contains automatic MCP transport" }
        val allowedHooks = setOf("hooks/hooks.json")
        check(entries.none { entry ->
            entry.startsWith(defaultPlugin + "hooks/") && entry.removePrefix(defaultPlugin) !in allowedHooks
        }) { "default guidance contains an undeclared hook" }
        check(paths.none { isPrivateDistributionArtifact(root.relativize(it)) }) {
            "control product contains a private environment or Python cache"
        }
        val publicExecutables = Files.list(root.resolve("bin")).use { paths ->
            paths.filter(Files::isRegularFile).map { path -> path.fileName.toString() }.toList()
        }
        check(publicExecutables.toSet() == setOf("kast", "kast-codex", "kast-mcp", "kast-query")) {
            "control product must expose the semantic CLI and integration host: $publicExecutables"
        }
        check(listOf("bin/kast", "bin/kast-codex", "bin/kast-mcp", "bin/kast-query", "share/kast/libexec/kast-daemon", "share/kast/libexec/kast-service", "share/kast/libexec/kast-management").all { Files.isExecutable(root.resolve(it)) }) {
            "control product kast launcher is not executable"
        }
        check(paths.none(Files::isSymbolicLink)) { "control product contains a symbolic link" }
        val forbidden = listOf("kast-indexer", "idea-home", "plugins/Kotlin", "plugins/gradle")
        check(entries.none { entry -> forbidden.any(entry::contains) }) {
            "control product contains semantic runtime payload"
        }
    }
}

private fun guidanceDistributionEntries(): List<String> {
    val shared = listOf(
        ".agents/plugins/marketplace.json", "plugins/kast/.codex-plugin/plugin.json",
        "plugins/kast/skills/kast/SKILL.md", "plugins/kast/skills/kast/public-cli.md",
        "plugins/kast/hooks/hooks.json",
        "plugins/kast/references/onboarding.md", "plugins/kast/references/query-examples.json",
    )
    val compositions = listOf("", "compositions/mcp/").flatMap { composition ->
        shared.map { "share/kast/agent-tools/$composition$it" }
    }
    return compositions + "share/kast/agent-tools/compositions/mcp/plugins/kast/.mcp.json"
}

internal fun nativeQueryHeaderMatches(header: ByteArray, platform: String): Boolean =
    header.size == 20 && when (platform) {
        "macos-aarch64" -> header.take(8).map { it.toInt() and 255 } ==
            listOf(207, 250, 237, 254, 12, 0, 0, 1) && header[12].toInt() == 2
        "macos-x86_64" -> header.take(8).map { it.toInt() and 255 } ==
            listOf(207, 250, 237, 254, 7, 0, 0, 1) && header[12].toInt() == 2
        "linux-aarch64" -> header.take(6).map { it.toInt() and 255 } ==
            listOf(127, 69, 76, 70, 2, 1) && (header[18].toInt() and 255) == 183 && header[19].toInt() == 0
        "linux-x86_64" -> header.take(6).map { it.toInt() and 255 } ==
            listOf(127, 69, 76, 70, 2, 1) && header[18].toInt() == 62 && header[19].toInt() == 0
        else -> false
    }

@CacheableTask
abstract class VerifySemanticRuntimeDistributionTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = runtimeDirectory.get().asFile.toPath()
        check(Files.isExecutable(root.resolve("kast-indexer")))
        check(Files.isDirectory(root.resolve("private-plugins/kast-indexer/lib")))
        check(Files.isDirectory(root.resolve("runtime-libs")))
        check(Files.notExists(root.resolve("idea-home"))) {
            "semantic runtime must not package an IDEA home"
        }
        val forbidden = Files.walk(root).use { paths ->
            paths.map { path -> root.relativize(path).toString() }
                .filter { entry ->
                    entry.contains("product-info.json") ||
                        entry.contains("plugins/Kotlin") ||
                        entry.contains("plugins/gradle")
                }
                .findFirst()
                .orElse(null)
        }
        check(forbidden == null) { "semantic runtime packages installed-IDE content: $forbidden" }
    }
}
