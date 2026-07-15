package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.defaultSocketPath
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.compatibility.PluginImplementationVersion
import io.github.amichne.kast.api.contract.compatibility.ProtocolRevision
import io.github.amichne.kast.api.contract.compatibility.RuntimeBackendKind
import io.github.amichne.kast.api.contract.compatibility.RuntimeCompatibilityFacts
import io.github.amichne.kast.api.contract.compatibility.RuntimeIdentity
import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import io.github.amichne.kast.api.contract.compatibility.WorkspaceMetadataRevision
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

object PluginWorkspaceBootstrap {
    private val SCHEMA_VERSION = WorkspaceMetadataRevision.CURRENT.value
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
        val backupRoot = workspaceRoot.resolve(".kast/backups/plugin-setup-${backupTimestamp()}")
        val backups = mutableListOf<Path>()

        knownActiveArtifactRelatives
            .filterNot { relative -> relative == ".agents/skills/kast" }
            .forEach { relative -> backupAndRemove(workspaceRoot, relative, backupRoot, backups) }

        val skillPath = workspaceRoot.resolve(REQUIRED_SKILL_RELATIVE)
        replaceSkillDirectoryWithBackup(
            workspaceRoot,
            skillPath,
            renderSkill(request),
            backupRoot,
            backups,
        )
        replaceContextWithBackup(
            workspaceRoot,
            contextPath,
            skillPath,
            backupRoot,
            backups,
        )
        val metadataPath = workspaceRoot.resolve(METADATA_RELATIVE)
        replaceFileWithBackup(
            workspaceRoot,
            metadataPath,
            renderMetadata(request, requiredArtifacts.sorted()),
            backupRoot,
            backups,
        )
        return PluginWorkspaceBootstrapResult.Prepared(metadataPath, backups.toList())
    }

    private fun defaultContextTarget(workspaceRoot: Path): Path =
        listOf("AGENTS.md", "CODEX.md", "CLAUDE.md", "AGENTS.local.md")
            .map(workspaceRoot::resolve)
            .firstOrNull { candidate -> Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) }
            ?.toAbsolutePath()
            ?.normalize()
            ?: workspaceRoot.resolve("AGENTS.local.md").toAbsolutePath().normalize()

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
        if (Files.isRegularFile(normalizedTarget) && Files.readString(normalizedTarget) == contents) return
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            val backup = backupPath(backupRoot, workspaceRoot.relativize(normalizedTarget).toString())
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
        val onlyExpectedSkill = Files.isDirectory(skillDirectory) &&
            Files.list(skillDirectory).use { entries ->
                entries.allMatch { entry -> entry.fileName.toString() == "SKILL.md" }
            }
        if (onlyExpectedSkill && Files.isRegularFile(skillPath) && Files.readString(skillPath) == contents) return
        if (Files.exists(skillDirectory, LinkOption.NOFOLLOW_LINKS)) {
            val backup = backupPath(backupRoot, workspaceRoot.relativize(skillDirectory).toString())
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
        val original = if (Files.isRegularFile(target)) Files.readString(target) else ""
        val updated = replaceOrAppendManagedRegion(original, renderGuidance(skillPath))
        if (updated == original && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        replaceFileWithBackup(workspaceRoot, target, updated, backupRoot, backups)
    }

    private fun replaceOrAppendManagedRegion(original: String, expectedRegion: String): String {
        val start = original.indexOf(KAST_MANAGED_FENCE_START)
        val end = original.indexOf(KAST_MANAGED_FENCE_END)
        if (start >= 0 && end >= start) {
            return original.replaceRange(start, end + KAST_MANAGED_FENCE_END.length, expectedRegion)
        }
        val prefix = original.trimEnd()
        return if (prefix.isEmpty()) "$expectedRegion\n" else "$prefix\n\n$expectedRegion\n"
    }

    private fun backupPath(backupRoot: Path, relative: String): Path =
        backupRoot.resolve(relative.replace('\\', '/')).toAbsolutePath().normalize()

    private fun renderSkill(request: PluginWorkspaceBootstrapRequest): String =
        """
        |---
        |name: kast
        |description: Kotlin semantic work and linked-worktree lifecycle in Gradle repositories prepared by the Kast IntelliJ plugin.
        |---
        |
        |# Kast
        |
        |This workspace was prepared by the Kast IntelliJ plugin. JetBrains owns plugin installation and updates; Homebrew owns only the CLI.
        |
        |Use `kast agent verify --workspace-root "${'$'}PWD"` before Kotlin semantic work when state is uncertain.
        |Use typed commands such as `kast agent symbol`, `kast agent diagnostics`, `kast agent impact`, and `kast agent rename`.
        |Do not run `kast setup` or install runtime resources separately on macOS; update the CLI and plugin, reopen this exact project, and refresh metadata when compatibility fails.
        |
        |## Linked Worktrees
        |
        |For every delegated worker using a linked Git worktree:
        |
        |1. Before the worker starts, open the exact worktree root as its own IntelliJ IDEA or Android Studio project with the Kast plugin enabled.
        |2. Wait for `.kast/setup/workspace.json`, then run `kast agent verify --workspace-root "${'$'}PWD"` from that worktree.
        |3. Never reuse another worktree's Kast runtime, metadata, or semantic evidence.
        |4. Keep that IDE project open while the worker and worktree are active.
        |5. Before retiring or deleting the worktree, close that exact IDE project or window before removing the worktree.
        |
        |Prepared plugin version: ${request.pluginVersion.value}
        |CLI version: ${request.cliVersion.value}
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
            "Before each linked worker starts, open the exact worktree root as its own IDE project and run `kast agent verify --workspace-root \"\$PWD\"` from that worktree.",
            "Never reuse another worktree's Kast runtime, metadata, or semantic evidence.",
            "Keep the IDE project open while active; close its exact IDE project or window before removing the worktree.",
            KAST_MANAGED_FENCE_END,
        ).joinToString("\n")

    private fun renderMetadata(
        request: PluginWorkspaceBootstrapRequest,
        requiredArtifacts: List<String>,
    ): String {
        val artifacts = requiredArtifacts.joinToString(",\n") { artifact -> "    ${jsonString(artifact)}" }
        val compatibility = Json.encodeToString(
            RuntimeCompatibilityFacts(
                pluginVersion = PluginImplementationVersion(request.pluginVersion.value),
                cliVersion = request.cliVersion,
                protocolRevision = ProtocolRevision.CURRENT,
                workspaceMetadataRevision = WorkspaceMetadataRevision.CURRENT,
                readCapabilities = ReadCapability.entries.toSet(),
                mutationCapabilities = MutationCapability.entries.toSet(),
                runtimeIdentity = RuntimeIdentity(
                    implementationVersion = RuntimeImplementationVersion(request.pluginVersion.value),
                    backendKind = RuntimeBackendKind.IDEA,
                ),
            ),
        )
        return """
        |{
        |  "schemaVersion": $SCHEMA_VERSION,
        |  "preparedBy": "kast-intellij-plugin",
        |  "workspaceRoot": ${jsonString(request.workspaceRoot.toString())},
        |  "cliBinary": ${jsonString(request.cliBinary.toString())},
        |  "backend": "idea",
        |  "socketPath": ${jsonString(defaultSocketPath(request.workspaceRoot).toString())},
        |  "compatibility": $compatibility,
        |  "requiredArtifacts": [
        |$artifacts
        |  ]
        |}
        |""".trimMargin()
    }

    private fun jsonString(value: String): String = Json.encodeToString(value)

    private fun backupTimestamp(): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-').replace('.', '-')
}
