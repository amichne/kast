package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveLong
import io.github.amichne.kast.cli.EvalSkillExecutor
import io.github.amichne.kast.cli.RuntimeCandidateStatus
import io.github.amichne.kast.cli.SmokeOutputFormat
import io.github.amichne.kast.cli.options.RuntimeCommandOptions
import io.github.amichne.kast.indexstore.api.graph.MetricsGraph
import io.github.amichne.kast.indexstore.api.metrics.general.FileFilterSpec
import io.github.amichne.kast.indexstore.api.metrics.impact.ChangeImpactNode
import io.github.amichne.kast.indexstore.api.metrics.impact.DeadCodeCandidate
import io.github.amichne.kast.indexstore.api.metrics.impact.FanInMetric
import io.github.amichne.kast.indexstore.api.metrics.impact.FanOutMetric
import io.github.amichne.kast.indexstore.api.metrics.impact.LowUsageSymbol
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCouplingMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleCycleMetric
import io.github.amichne.kast.indexstore.api.metrics.module.ModuleDepthMetric
import io.github.amichne.kast.indexstore.metrics.MetricsEngine
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

            is CliCommand.WorkspaceStatus -> {
                val result = cliService.workspaceStatus(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = daemonNoteFor(result),
                )
            }

            is CliCommand.WorkspaceEnsure -> {
                val result = cliService.workspaceEnsure(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = daemonNoteFor(result),
                )
            }

            is CliCommand.WorkspaceStop -> {
                val result = cliService.workspaceStop(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = daemonNoteFor(result),
                )
            }

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

            CliCommand.SelfStatus -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.selfStatus()),
            )

            CliCommand.SelfDoctor -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.selfDoctor()),
            )

            CliCommand.SelfUninstall -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.selfUninstall()),
            )

            CliCommand.SelfUpgrade -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.selfUpgrade()),
            )

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

            is CliCommand.Metrics -> {
                if (command.subcommand == MetricsSubcommand.GRAPH && command.interactive) {
                    if (command.symbol == null) {
                        return CliExecutionResult(
                            output = CliOutput.InteractiveGraphPicker(
                                workspaceRoot = command.workspaceRoot,
                                depth = command.depth,
                            ),
                        )
                    }
                    val graph = MetricsEngine(command.workspaceRoot).use { engine ->
                        engine.graph(
                            fqName = command.symbol,
                            depth = command.depth,
                        )
                    }
                    return CliExecutionResult(output = CliOutput.InteractiveGraph(graph))
                }
                val filter = FileFilterSpec(
                    fileGlob = command.fileGlob,
                    folderPrefix = command.folderFilter,
                )
                val encoded = MetricsEngine(command.workspaceRoot).use { engine ->
                    when (command.subcommand) {
                        MetricsSubcommand.FAN_IN -> json.encodeToString(
                            ListSerializer(FanInMetric.serializer()), engine.fanInRanking(command.limit, filter),
                        )
                        MetricsSubcommand.FAN_OUT -> json.encodeToString(
                            ListSerializer(FanOutMetric.serializer()), engine.fanOutRanking(command.limit, filter),
                        )
                        MetricsSubcommand.COUPLING -> json.encodeToString(
                            ListSerializer(ModuleCouplingMetric.serializer()), engine.moduleCouplingMatrix(),
                        )
                        MetricsSubcommand.LOW_USAGE -> json.encodeToString(
                            ListSerializer(LowUsageSymbol.serializer()),
                            engine.lowUsageSymbols(limit = command.limit, filter = filter),
                        )
                        MetricsSubcommand.CYCLES -> json.encodeToString(
                            ListSerializer(ModuleCycleMetric.serializer()), engine.moduleCycles(),
                        )
                        MetricsSubcommand.MODULE_DEPTH -> json.encodeToString(
                            ListSerializer(ModuleDepthMetric.serializer()), engine.moduleDepthMetrics(),
                        )
                        MetricsSubcommand.DEAD_CODE -> json.encodeToString(
                            ListSerializer(DeadCodeCandidate.serializer()), engine.deadCodeCandidates(filter),
                        )
                        MetricsSubcommand.IMPACT -> json.encodeToString(
                            ListSerializer(ChangeImpactNode.serializer()),
                            engine.changeImpactRadius(
                                fqName = requireNotNull(command.symbol) { "--symbol is required for impact" },
                                depth = command.depth,
                                filter = filter,
                            ),
                        )
                        MetricsSubcommand.GRAPH -> json.encodeToString(
                            MetricsGraph.serializer(),
                            engine.graph(
                                fqName = requireNotNull(command.symbol) { "--symbol is required for graph" },
                                depth = command.depth,
                            ),
                        )
                    }
                }
                CliExecutionResult(output = CliOutput.Text(encoded))
            }
        }
    }
}
