package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.api.client.fields.ProjectOpenProfileKind
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

object KastProjectOpenProfileAutoInit {
    fun execute(
        workspaceRoot: Path,
        config: KastConfig,
        prepareWorkspace: (PluginWorkspaceBootstrapRequest) -> PluginWorkspaceBootstrapResult =
            PluginWorkspaceBootstrap::prepare,
    ): ProjectOpenProfileAutoInitResult {
        if (!config.projectOpen.profileAutoInit.value) {
            return ProjectOpenProfileAutoInitResult.Skipped("disabled")
        }
        if (config.projectOpen.profile.kind != ProjectOpenProfileKind.JETBRAINS_PLUGIN) {
            return ProjectOpenProfileAutoInitResult.Skipped("unsupported profile")
        }
        if (!workspaceRoot.hasGradleMarker()) {
            return ProjectOpenProfileAutoInitResult.Skipped("not a Gradle project")
        }

        val pluginVersion = kastPluginVersion()
            ?: return ProjectOpenProfileAutoInitResult.Failed(
                "Kast plugin version resource is missing or invalid; refusing workspace setup.",
            )
        val request = PluginWorkspaceBootstrapRequest(
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize(),
            cliBinary = Path.of(config.cli.binaryPath.value).toAbsolutePath().normalize(),
            pluginVersion = pluginVersion,
        )
        return when (val result = prepareWorkspace(request)) {
            is PluginWorkspaceBootstrapResult.Prepared ->
                ProjectOpenProfileAutoInitResult.Installed(
                    metadataPath = result.metadataPath,
                    backups = result.backups,
                )
            is PluginWorkspaceBootstrapResult.Rejected ->
                ProjectOpenProfileAutoInitResult.Failed(result.message)
        }
    }

    private fun Path.hasGradleMarker(): Boolean =
        listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")
            .any { marker -> Files.isRegularFile(resolve(marker)) }

    private val LOG = Logger.getInstance(KastProjectOpenProfileAutoInit::class.java)

    fun log(result: ProjectOpenProfileAutoInitResult) {
        when (result) {
            is ProjectOpenProfileAutoInitResult.Installed ->
                LOG.info(
                    "Kast project-open workspace setup prepared ${result.metadataPath}" +
                        if (result.backups.isEmpty()) "" else " with ${result.backups.size} backup(s)",
                )
            is ProjectOpenProfileAutoInitResult.Skipped ->
                LOG.info("Kast project-open workspace setup skipped: ${result.reason}")
            is ProjectOpenProfileAutoInitResult.Failed ->
                LOG.warn("Kast project-open workspace setup failed: ${result.message}")
        }
    }
}

data class PluginWorkspaceBootstrapRequest(
    val workspaceRoot: Path,
    val cliBinary: Path,
    val pluginVersion: PluginVersion,
)

@JvmInline
value class PluginVersion(val value: String) {
    init {
        require(value.isNotBlank()) { "Kast plugin version must not be blank" }
        require(value != "unknown") { "Kast plugin version must be explicit" }
    }
}

sealed class PluginWorkspaceBootstrapResult {
    data class Prepared(
        val metadataPath: Path,
        val backups: List<Path>,
    ) : PluginWorkspaceBootstrapResult()

    data class Rejected(val message: String) : PluginWorkspaceBootstrapResult()
}

sealed class ProjectOpenProfileAutoInitResult {
    data class Skipped(val reason: String) : ProjectOpenProfileAutoInitResult()
    data class Installed(
        val metadataPath: Path,
        val backups: List<Path>,
    ) : ProjectOpenProfileAutoInitResult()

    data class Failed(val message: String) : ProjectOpenProfileAutoInitResult()
}

object PluginWorkspaceBootstrap {
    private const val SCHEMA_VERSION = 1
    private const val REQUIRED_SKILL_RELATIVE = ".agents/skills/kast/SKILL.md"
    private const val METADATA_RELATIVE = ".kast/setup/workspace.json"
    private const val KAST_MANAGED_FENCE_START = "<kast>"
    private const val KAST_MANAGED_FENCE_END = "</kast>"

    private val knownActiveArtifactRelatives = listOf(
        ".agents/skills/kast",
        ".agents/instructions/kast",
        ".codex/skills/kast",
        ".codex/instructions/kast",
        ".github/skills/kast",
        ".github/instructions/kast",
        ".claude/skills/kast",
        ".claude/instructions/kast",
        ".github/lsp.json",
        ".github/extensions/kast",
        ".opencode/kast-context.plugin.json",
    )

    fun prepare(request: PluginWorkspaceBootstrapRequest): PluginWorkspaceBootstrapResult {
        if (!Files.isRegularFile(request.cliBinary)) {
            return PluginWorkspaceBootstrapResult.Rejected(
                "Kast CLI binary is missing at ${request.cliBinary}",
            )
        }
        val workspaceRoot = request.workspaceRoot.toAbsolutePath().normalize()
        val contextPath = defaultContextTarget(workspaceRoot)
        val requiredArtifacts = listOf(
            REQUIRED_SKILL_RELATIVE,
            workspaceRoot.relativize(contextPath).toString(),
            METADATA_RELATIVE,
        ).toSet()
        val requiredActiveRoots = setOf(".agents/skills/kast")
        val backupRoot = workspaceRoot.resolve(".kast/backups/plugin-setup-${backupTimestamp()}")
        val backups = mutableListOf<Path>()

        for (relative in knownActiveArtifactRelatives) {
            if (relative !in requiredActiveRoots) {
                backupAndRemove(workspaceRoot, relative, backupRoot, backups)
            }
        }

        val skillPath = workspaceRoot.resolve(REQUIRED_SKILL_RELATIVE)
        replaceSkillDirectoryWithBackup(
            workspaceRoot = workspaceRoot,
            skillPath = skillPath,
            contents = renderSkill(request),
            backupRoot = backupRoot,
            backups = backups,
        )
        replaceContextWithBackup(
            workspaceRoot = workspaceRoot,
            target = contextPath,
            skillPath = skillPath,
            backupRoot = backupRoot,
            backups = backups,
        )
        val metadataPath = workspaceRoot.resolve(METADATA_RELATIVE)
        replaceFileWithBackup(
            workspaceRoot = workspaceRoot,
            target = metadataPath,
            contents = renderMetadata(request, requiredArtifacts.sorted()),
            backupRoot = backupRoot,
            backups = backups,
        )

        return PluginWorkspaceBootstrapResult.Prepared(
            metadataPath = metadataPath,
            backups = backups.toList(),
        )
    }

    private fun defaultContextTarget(workspaceRoot: Path): Path {
        for (candidate in listOf(
            "AGENTS.md",
            "CODEX.md",
            "CLAUDE.md",
            ".github/copilot-instructions.md",
            "AGENTS.local.md",
        )) {
            val path = workspaceRoot.resolve(candidate)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return path.toAbsolutePath().normalize()
            }
        }
        return workspaceRoot.resolve("AGENTS.local.md").toAbsolutePath().normalize()
    }

    private fun backupAndRemove(
        workspaceRoot: Path,
        relative: String,
        backupRoot: Path,
        backups: MutableList<Path>,
    ) {
        val target = workspaceRoot.resolve(relative)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        val backup = backupPath(backupRoot, relative)
        Files.createDirectories(backup.parent)
        Files.move(target, backup)
        backups.add(backup)
    }

    private fun replaceFileWithBackup(
        workspaceRoot: Path,
        target: Path,
        contents: String,
        backupRoot: Path,
        backups: MutableList<Path>,
    ) {
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isRegularFile(normalizedTarget) && Files.readString(normalizedTarget) == contents) {
                return
            }
            val relative = workspaceRoot.relativize(normalizedTarget).toString()
            val backup = backupPath(backupRoot, relative)
            Files.createDirectories(backup.parent)
            Files.move(normalizedTarget, backup)
            backups.add(backup)
        }
        Files.createDirectories(normalizedTarget.parent)
        Files.writeString(normalizedTarget, contents)
    }

    private fun replaceSkillDirectoryWithBackup(
        workspaceRoot: Path,
        skillPath: Path,
        contents: String,
        backupRoot: Path,
        backups: MutableList<Path>,
    ) {
        val skillDirectory = skillPath.parent.toAbsolutePath().normalize()
        val currentSkillMatches = Files.isRegularFile(skillPath) && Files.readString(skillPath) == contents
        val directoryOnlyContainsSkill = if (Files.isDirectory(skillDirectory)) {
            Files.list(skillDirectory).use { entries ->
                entries.allMatch { entry -> entry.fileName.toString() == "SKILL.md" }
            }
        } else {
            false
        }
        if (currentSkillMatches && directoryOnlyContainsSkill) {
            return
        }
        if (Files.exists(skillDirectory, LinkOption.NOFOLLOW_LINKS)) {
            val relative = workspaceRoot.relativize(skillDirectory).toString()
            val backup = backupPath(backupRoot, relative)
            Files.createDirectories(backup.parent)
            Files.move(skillDirectory, backup)
            backups.add(backup)
        }
        Files.createDirectories(skillDirectory)
        Files.writeString(skillPath, contents)
    }

    private fun replaceContextWithBackup(
        workspaceRoot: Path,
        target: Path,
        skillPath: Path,
        backupRoot: Path,
        backups: MutableList<Path>,
    ) {
        val normalizedTarget = target.toAbsolutePath().normalize()
        val original = if (Files.isRegularFile(normalizedTarget)) Files.readString(normalizedTarget) else ""
        val expectedRegion = renderGuidance(skillPath)
        val updated = replaceOrAppendManagedRegion(original, expectedRegion)
        if (updated == original && Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            val relative = workspaceRoot.relativize(normalizedTarget).toString()
            val backup = backupPath(backupRoot, relative)
            Files.createDirectories(backup.parent)
            Files.move(normalizedTarget, backup)
            backups.add(backup)
        }
        Files.createDirectories(normalizedTarget.parent)
        Files.writeString(normalizedTarget, updated)
    }

    private fun replaceOrAppendManagedRegion(original: String, expectedRegion: String): String {
        val start = original.indexOf(KAST_MANAGED_FENCE_START)
        val end = original.indexOf(KAST_MANAGED_FENCE_END)
        if (start >= 0 && end >= start) {
            val endExclusive = end + KAST_MANAGED_FENCE_END.length
            return original.replaceRange(start, endExclusive, expectedRegion)
        }
        val prefix = original.trimEnd()
        return if (prefix.isEmpty()) {
            "$expectedRegion\n"
        } else {
            "$prefix\n\n$expectedRegion\n"
        }
    }

    private fun backupPath(backupRoot: Path, relative: String): Path =
        backupRoot.resolve(relative.replace('\\', '/')).toAbsolutePath().normalize()

    private fun renderSkill(request: PluginWorkspaceBootstrapRequest): String =
        """
        |---
        |name: kast
        |description: Kotlin semantic work in Gradle repositories prepared by the Kast IntelliJ plugin.
        |---
        |
        |# Kast
        |
        |This workspace was prepared by the Kast IntelliJ plugin from the Homebrew-distributed Kast package.
        |
        |Use `kast agent verify --workspace-root "${'$'}PWD"` before Kotlin semantic work when state is uncertain.
        |Use typed commands such as `kast agent symbol`, `kast agent diagnostics`, `kast agent impact`, and `kast agent rename`.
        |Do not run `kast setup` or install Kast runtime/resources separately on macOS; reopen the workspace in IntelliJ IDEA or Android Studio with the Kast plugin enabled.
        |
        |Prepared plugin version: ${request.pluginVersion.value}
        |CLI invocation: `${request.cliBinary}`
        |""".trimMargin() + "\n"

    private fun renderGuidance(skillPath: Path): String =
        listOf(
            KAST_MANAGED_FENCE_START,
            "## Kast routing",
            "Use `$skillPath` before Kotlin or Gradle semantic work.",
            "Use `kast agent verify --workspace-root \"\$PWD\"` to verify the plugin-prepared workspace.",
            "Use typed commands such as `kast agent symbol --query <name>`, `kast agent diagnostics --file-path <path>`, and `kast agent rename --symbol <fq-name> --new-name <name> --apply`.",
            "Do not run `kast setup` on macOS; the IntelliJ plugin owns workspace bootstrap.",
            KAST_MANAGED_FENCE_END,
        ).joinToString("\n")

    private fun renderMetadata(
        request: PluginWorkspaceBootstrapRequest,
        requiredArtifacts: List<String>,
    ): String {
        val artifacts = requiredArtifacts.joinToString(",\n") { artifact ->
            "    ${jsonString(artifact)}"
        }
        return """
        |{
        |  "schemaVersion": $SCHEMA_VERSION,
        |  "preparedBy": "kast-intellij-plugin",
        |  "pluginVersion": ${jsonString(request.pluginVersion.value)},
        |  "cliVersion": ${jsonString(request.pluginVersion.value)},
        |  "workspaceRoot": ${jsonString(request.workspaceRoot.toString())},
        |  "cliBinary": ${jsonString(request.cliBinary.toString())},
        |  "backend": "idea",
        |  "socketPath": ${jsonString(defaultSocketPath(request.workspaceRoot).toString())},
        |  "requiredArtifacts": [
        |$artifacts
        |  ]
        |}
        |""".trimMargin()
    }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            for (char in value) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }

    private fun backupTimestamp(): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            .replace(':', '-')
            .replace('.', '-')
}

private fun kastPluginVersion(): PluginVersion? =
    KastPluginBackend::class.java
        .getResource("/kast-backend-version.txt")
        ?.readText()
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeIf { version -> version != "unknown" }
        ?.let(::PluginVersion)
