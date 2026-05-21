package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.cli.options.DaemonStartOptions
import io.github.amichne.kast.cli.options.InstallCopilotExtensionOptions
import io.github.amichne.kast.cli.options.InstallOptions
import io.github.amichne.kast.cli.options.InstallSkillOptions
import io.github.amichne.kast.cli.options.RuntimeCommandOptions
import io.github.amichne.kast.cli.options.SmokeOptions
import java.nio.file.Path

internal sealed interface CliCommand {
    data class Help(val topic: List<String> = emptyList()) : CliCommand
    data object Version : CliCommand
    data object VerifyExtension : CliCommand
    data class Completion(val shell: CliCompletionShell) : CliCommand
    data class Capabilities(val options: RuntimeCommandOptions) : CliCommand
    data class Install(val options: InstallOptions) : CliCommand
    data class InstallSkill(val options: InstallSkillOptions) : CliCommand
    data class InstallCopilotExtension(val options: InstallCopilotExtensionOptions) : CliCommand
    data object Info : CliCommand
    data object Doctor : CliCommand
    data object Uninstall : CliCommand
    data class UninstallCopilotExtension(val options: InstallCopilotExtensionOptions) : CliCommand
    data class Smoke(val options: SmokeOptions) : CliCommand
    data class DaemonStart(val options: DaemonStartOptions) : CliCommand
    data object ConfigInit : CliCommand
    data class EvalSkill(val options: EvalSkillOptions) : CliCommand
    data class Rpc(val rawJson: String, val workspaceRootOverride: Path?) : CliCommand
    data class Up(val options: RuntimeCommandOptions) : CliCommand
    data class Status(val options: RuntimeCommandOptions) : CliCommand
    data class Stop(val options: RuntimeCommandOptions) : CliCommand
    data class GradleRun(
        val workspaceRoot: Path,
        val task: String,
        val extraArgs: List<String> = emptyList(),
    ) : CliCommand
}

internal data class EvalSkillOptions(
    val skillDir: Path,
    val compareBaseline: Path? = null,
    val format: EvalOutputFormat = EvalOutputFormat.JSON,
)

internal enum class EvalOutputFormat { JSON, MARKDOWN }
