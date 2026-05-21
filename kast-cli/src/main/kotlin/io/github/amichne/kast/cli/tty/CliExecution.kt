package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveLong
import io.github.amichne.kast.cli.EvalSkillExecutor
import io.github.amichne.kast.cli.RuntimeCandidateStatus
import io.github.amichne.kast.cli.SmokeOutputFormat
import io.github.amichne.kast.cli.options.RuntimeCommandOptions
import io.github.amichne.kast.indexstore.api.metrics.impact.ChangeImpactNode
import io.github.amichne.kast.indexstore.api.metrics.impact.DeadCodeCandidate
import io.github.amichne.kast.indexstore.api.metrics.impact.FanInMetric
import io.github.amichne.kast.indexstore.api.metrics.impact.FanOutMetric
import io.github.amichne.kast.indexstore.api.metrics.general.FileFilterSpec
import io.github.amichne.kast.indexstore.api.metrics.impact.LowUsageSymbol
import io.github.amichne.kast.indexstore.metrics.MetricsEngine
import io.github.amichne.kast.indexstore.api.graph.MetricsGraph
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCouplingMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCycleMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleDepthMetric
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path

internal data class CliExternalProcess(
    val command: List<String>,
    val workingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
)

internal data class CliExecutionResult(
    val output: CliOutput,
    val daemonNote: String? = null,
)

internal data class RuntimeAttachedResult<out T>(
    val payload: T,
    val runtime: RuntimeCandidateStatus,
    val daemonNote: String? = null,
)

internal interface CliCommandExecutor {
    suspend fun execute(command: CliCommand): CliExecutionResult
}

internal class DefaultCliCommandExecutor(
    private val cliService: CliService,
    private val json: Json = defaultCliJson(),
    private val cwdProvider: () -> Path = {
        Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
    },
) : CliCommandExecutor {

    override suspend fun execute(command: CliCommand): CliExecutionResult {
        return when (command) {
            is CliCommand.Help -> CliExecutionResult(
                output = CliOutput.Text(CliCommandCatalog.helpText(command.topic)),
            )

            CliCommand.Version -> CliExecutionResult(
                output = CliOutput.Text(CliCommandCatalog.versionText()),
            )

            CliCommand.VerifyExtension -> {
                val result = verifyCopilotExtension(cwdProvider())
                CliExecutionResult(
                    output = CliOutput.JsonValueWithExitCode(
                        value = result,
                        exitCode = if (result.ok) 0 else 1,
                    ),
                )
            }

            is CliCommand.Completion -> CliExecutionResult(
                output = CliOutput.Text(CliCompletionScripts.render(command.shell)),
            )

            is CliCommand.Capabilities -> {
                val result = cliService.capabilities(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.Install -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.install(command.options)),
            )

            is CliCommand.InstallSkill -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.installSkill(command.options)),
            )

            is CliCommand.InstallCopilotExtension -> {
                val result = cliService.installCopilotExtension(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = result.warnings.joinToString("\n").takeIf { it.isNotBlank() },
                )
            }

            CliCommand.Info -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.selfStatus()),
            )

            CliCommand.Doctor -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.selfDoctor()),
            )

            CliCommand.Uninstall -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.selfUninstall()),
            )

            is CliCommand.UninstallCopilotExtension -> {
                val result = cliService.installCopilotExtension(command.options.copy(uninstall = true))
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = result.warnings.joinToString("\n").takeIf { it.isNotBlank() },
                )
            }

            is CliCommand.Smoke -> {
                val report = cliService.smoke(command.options)
                val output = when (command.options.format) {
                    SmokeOutputFormat.JSON -> CliOutput.JsonValue(report)
                    SmokeOutputFormat.MARKDOWN -> CliOutput.Text(report.toMarkdown())
                }
                CliExecutionResult(output = output)
            }

            is CliCommand.DaemonStart -> CliExecutionResult(
                output = cliService.daemonStart(command.options),
            )

            CliCommand.ConfigInit -> CliExecutionResult(
                output = cliService.configInit(),
            )


            is CliCommand.Up -> {
                val result = cliService.workspaceEnsure(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = daemonNoteFor(result),
                )
            }

            is CliCommand.Status -> {
                val result = cliService.workspaceStatus(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = daemonNoteFor(result),
                )
            }

            is CliCommand.Stop -> {
                val result = cliService.workspaceStop(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = daemonNoteFor(result),
                )
            }

            is CliCommand.Rpc -> {
                val workspaceRoot = command.workspaceRootOverride ?: cwdProvider()
                val options = RuntimeCommandOptions(
                    workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
                    backendName = null,
                    waitTimeoutMillis = PositiveLong(60_000L),
                )
                val result = cliService.rpcPassthrough(options, command.rawJson)
                CliExecutionResult(output = CliOutput.Text(result))
            }

            is CliCommand.EvalSkill -> {
                val result = EvalSkillExecutor(json).execute(command.options)
                CliExecutionResult(output = result)
            }

            is CliCommand.GradleRun -> CliExecutionResult(
                output = CliOutput.JsonValue(
                    GradleRunExecutor().run(
                        workspaceRoot = command.workspaceRoot,
                        task = command.task,
                        extraArgs = command.extraArgs,
                    ),
                ),
            )
        }
    }

}
