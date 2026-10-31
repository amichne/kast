package support.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.util.zip.ZipFile

@CacheableTask
abstract class VerifyControlDistributionTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val controlDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val controlArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val qualifiedQueryExecutable: RegularFileProperty

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
        val query = root.resolve("bin/kast-query")
        check(Files.mismatch(query, qualifiedQueryExecutable.get().asFile.toPath()) == -1L) {
            "control query payload differs from its native producer"
        }
        val header = Files.newInputStream(query).use { it.readNBytes(20) }
        check(nativeQueryHeaderMatches(header, targetPlatform.get())) {
            "control query payload is not a native executable for ${targetPlatform.get()}"
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
        logger.lifecycle("Control inventory: entries={}, remaining={}", payloadEntries.size, maximumEntries.get() - payloadEntries.size)
        val installedBytes = paths
            .filter(Files::isRegularFile)
            .sumOf(Files::size)
        check(installedBytes < maximumInstalledBytes.get()) {
            "installed control product is $installedBytes bytes. It must be less than ${maximumInstalledBytes.get()} bytes."
        }
        val required = listOf(
            "bin/kast",
            "bin/kast-tool-rpc",
            "bin/kast-query",
            "share/kast/adapters/copilot/extension.mjs",
            "share/kast/adapters/pi/extension.ts",
            "share/kast/libexec/kast-daemon",
            "share/kast/libexec/kast-service",
            "share/kast/libexec/kast-management",
            "share/kast/install.sh",
            "share/kast/operation-registry.json",
            "share/kast/provider-catalog.json",
            "share/kast/configuration-schema.json",
            "share/kast/wire-schema.json",
            "share/kast/ide-host.json",
            "share/kast/knowledge/manifest.json",
            "share/kast/licenses/LICENSE",
        )
        check((required + guidanceDistributionEntries()).all(entries::contains)) { "control product is missing required entries" }
        val defaultPlugin = "share/kast/agent-tools/plugins/kast/"
        val defaultEffects = listOf(".mcp.json", "mcp.json", "hooks", "skills/kast/scripts")
        check(entries.none { entry -> defaultEffects.any { effect ->
            entry == defaultPlugin + effect || entry.startsWith(defaultPlugin + effect + "/")
        } }) { "default guidance contains automatic MCP, hooks, or Python transport" }
        check(paths.none { isPrivateDistributionArtifact(root.relativize(it)) }) {
            "control product contains a private environment or Python cache"
        }
        val publicExecutables = Files.list(root.resolve("bin")).use { paths ->
            paths.filter(Files::isRegularFile).map { path -> path.fileName.toString() }.toList()
        }
        check(publicExecutables.toSet() == setOf("kast", "kast-codex", "kast-mcp", "kast-tool-rpc", "kast-query")) {
            "control product must expose the semantic CLI and integration host: $publicExecutables"
        }
        check(listOf("bin/kast", "bin/kast-codex", "bin/kast-mcp", "bin/kast-tool-rpc", "bin/kast-query", "share/kast/libexec/kast-daemon", "share/kast/libexec/kast-service", "share/kast/libexec/kast-management").all { Files.isExecutable(root.resolve(it)) }) {
            "control product kast launcher is not executable"
        }
        check(paths.none(Files::isSymbolicLink)) { "control product contains a symbolic link" }
        val forbidden = listOf("kast-indexer", "idea-home", "plugins/Kotlin", "plugins/gradle")
        check(entries.none { entry -> forbidden.any(entry::contains) }) {
            "control product contains semantic runtime payload"
        }
        val forbiddenJarEntries = listOf(
            "com/intellij/",
            "io/github/amichne/kast/indexer/",
            "org/jetbrains/kotlin/idea/",
        )
        val embeddedRuntimeEntry = paths
            .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar") }
            .firstNotNullOfOrNull { jar ->
                ZipFile(jar.toFile()).use { archiveFile ->
                    archiveFile.entries().asSequence()
                        .map { entry -> entry.name }
                        .firstOrNull { entry -> forbiddenJarEntries.any(entry::startsWith) }
                        ?.let { entry -> "${root.relativize(jar)}!/$entry" }
                }
            }
        check(embeddedRuntimeEntry == null) {
            "control product embeds semantic runtime entry $embeddedRuntimeEntry"
        }
    }
}

private fun guidanceDistributionEntries(): List<String> {
    val shared = listOf(
        ".agents/plugins/marketplace.json", ".github/plugin/marketplace.json",
        "plugins/kast/.codex-plugin/plugin.json", "plugins/kast/.github/plugin/plugin.json",
        "plugins/kast/skills/kast/SKILL.md", "plugins/kast/skills/kast/public-cli.md",
        "plugins/kast/references/onboarding.md", "plugins/kast/references/query-examples.json",
    )
    val compositions = listOf("", "compositions/mcp/").flatMap { composition ->
        shared.map { "share/kast/agent-tools/$composition$it" }
    }
    val transport = listOf(".mcp.json", "skills/kast/scripts/server.py", "skills/kast/scripts/server.py.lock",
        "skills/kast/scripts/service.py", "skills/kast/scripts/tasks.py", "skills/kast/scripts/transport.py",
        "skills/kast/scripts/access.py", "skills/kast/scripts/access.py.lock")
    return compositions + transport.map { "share/kast/agent-tools/compositions/mcp/plugins/kast/$it" }
}

internal fun nativeQueryHeaderMatches(header: ByteArray, platform: String): Boolean =
    header.size == 20 && when (platform) {
        "macos-aarch64" -> header.take(8).map { it.toInt() and 255 } ==
            listOf(207, 250, 237, 254, 12, 0, 0, 1) && header[12].toInt() == 2
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
