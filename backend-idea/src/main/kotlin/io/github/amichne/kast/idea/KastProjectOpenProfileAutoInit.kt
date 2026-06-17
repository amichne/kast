package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.ProjectOpenProfile
import java.nio.file.Files
import java.nio.file.Path

internal object KastProjectOpenProfileAutoInit {
    fun execute(
        workspaceRoot: Path,
        config: KastConfig,
        runCommand: (List<String>) -> CommandRunResult = ::runCommand,
    ): ProjectOpenProfileAutoInitResult {
        if (!config.projectOpen.profileAutoInit.value) {
            return ProjectOpenProfileAutoInitResult.Skipped("disabled")
        }
        if (config.projectOpen.profile.value != ProjectOpenProfile.COPILOT_LSP) {
            return ProjectOpenProfileAutoInitResult.Skipped("unsupported profile")
        }
        if (!workspaceRoot.hasGradleMarker()) {
            return ProjectOpenProfileAutoInitResult.Skipped("not a Gradle project")
        }

        val command = buildInstallCommand(workspaceRoot, config)
        val result = runCommand(command)
        return if (result.success) {
            ProjectOpenProfileAutoInitResult.Installed(command)
        } else {
            ProjectOpenProfileAutoInitResult.Failed(command, result.message)
        }
    }

    fun buildInstallCommand(workspaceRoot: Path, config: KastConfig): List<String> = buildList {
        add(config.cli.binaryPath.value)
        add("install")
        add("copilot")
        add("--target-dir")
        add(workspaceRoot.resolve(".github").toAbsolutePath().normalize().toString())
        if (!config.projectOpen.autoExcludeGit.value) {
            add("--no-auto-exclude-git")
        }
    }

    private fun Path.hasGradleMarker(): Boolean =
        listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")
            .any { marker -> Files.isRegularFile(resolve(marker)) }

    private fun runCommand(command: List<String>): CommandRunResult = try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        CommandRunResult(
            success = exitCode == 0,
            message = output.ifBlank { "exit code $exitCode" },
        )
    } catch (error: Exception) {
        CommandRunResult(success = false, message = error.message ?: error::class.java.name)
    }

    private val LOG = Logger.getInstance(KastProjectOpenProfileAutoInit::class.java)

    fun log(result: ProjectOpenProfileAutoInitResult) {
        when (result) {
            is ProjectOpenProfileAutoInitResult.Installed ->
                LOG.info("Kast project-open profile auto-init ran: ${result.command.joinToString(" ")}")
            is ProjectOpenProfileAutoInitResult.Skipped ->
                LOG.info("Kast project-open profile auto-init skipped: ${result.reason}")
            is ProjectOpenProfileAutoInitResult.Failed ->
                LOG.warn("Kast project-open profile auto-init failed: ${result.message}")
        }
    }
}

internal data class CommandRunResult(
    val success: Boolean,
    val message: String,
)

internal sealed class ProjectOpenProfileAutoInitResult {
    data class Skipped(val reason: String) : ProjectOpenProfileAutoInitResult()
    data class Installed(val command: List<String>) : ProjectOpenProfileAutoInitResult()
    data class Failed(val command: List<String>, val message: String) : ProjectOpenProfileAutoInitResult()
}
