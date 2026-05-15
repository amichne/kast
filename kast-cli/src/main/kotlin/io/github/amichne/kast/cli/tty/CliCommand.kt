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
    data class WorkspaceStatus(val options: RuntimeCommandOptions) : CliCommand
    data class WorkspaceEnsure(val options: RuntimeCommandOptions) : CliCommand
    data class WorkspaceStop(val options: RuntimeCommandOptions) : CliCommand
    data class Capabilities(val options: RuntimeCommandOptions) : CliCommand
    data class Install(val options: InstallOptions) : CliCommand
    data class InstallSkill(val options: InstallSkillOptions) : CliCommand
    data class InstallCopilotExtension(val options: InstallCopilotExtensionOptions) : CliCommand
    data object SelfStatus : CliCommand
    data object SelfDoctor : CliCommand
    data object SelfUninstall : CliCommand
    data object SelfUpgrade : CliCommand
    data class Smoke(val options: SmokeOptions) : CliCommand
    data class DaemonStart(val options: DaemonStartOptions) : CliCommand
    data object ConfigInit : CliCommand
    data class EvalSkill(val options: EvalSkillOptions) : CliCommand
    data class Rpc(
        val rawJson: String,
        val workspaceRootOverride: Path?,
    ) : CliCommand

    data class Up(val options: RuntimeCommandOptions) : CliCommand
    data class Status(val options: RuntimeCommandOptions) : CliCommand
    data class Stop(val options: RuntimeCommandOptions) : CliCommand
    data class GradleRun(
        val workspaceRoot: Path,
        val task: String,
        val extraArgs: List<String> = emptyList(),
    ) : CliCommand

    data class Metrics(
        val subcommand: MetricsSubcommand,
        val workspaceRoot: Path,
        val limit: Int = 50,
        val symbol: String? = null,
        val depth: Int = 3,
        val interactive: Boolean = false,
        val fileGlob: String? = null,
        val folderFilter: String? = null,
    ) : CliCommand
}

internal enum class MetricsSubcommand {
    FAN_IN,
    FAN_OUT,
    COUPLING,
    LOW_USAGE,
    CYCLES,
    MODULE_DEPTH,
    DEAD_CODE,
    IMPACT,
    GRAPH,
}

internal data class EvalSkillOptions(
    val skillDir: Path,
    val compareBaseline: Path? = null,
    val format: EvalOutputFormat = EvalOutputFormat.JSON,
)

internal enum class EvalOutputFormat { JSON, MARKDOWN }
