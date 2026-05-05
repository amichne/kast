package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.cli.EvalSkillExecutor
import io.github.amichne.kast.cli.RuntimeCandidateStatus
import io.github.amichne.kast.cli.SmokeOutputFormat
import io.github.amichne.kast.cli.skill.SkillWrapperExecutor
import io.github.amichne.kast.cli.skill.SkillWrapperSerializer
import io.github.amichne.kast.indexstore.ChangeImpactNode
import io.github.amichne.kast.indexstore.DeadCodeCandidate
import io.github.amichne.kast.indexstore.FanInMetric
import io.github.amichne.kast.indexstore.FanOutMetric
import io.github.amichne.kast.indexstore.FileFilterSpec
import io.github.amichne.kast.indexstore.LowUsageSymbol
import io.github.amichne.kast.indexstore.MetricsGraph
import io.github.amichne.kast.indexstore.MetricsEngine
import io.github.amichne.kast.indexstore.ModuleCouplingMetric
import io.github.amichne.kast.indexstore.ModuleCycleMetric
import io.github.amichne.kast.indexstore.ModuleDepthMetric
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.reflect.KClass

internal sealed interface CliOutput {
    data class JsonValue(val value: Any) : CliOutput
    data class Text(val value: String) : CliOutput
    data class InteractiveGraph(val graph: MetricsGraph) : CliOutput
    data class InteractiveGraphPicker(
        val workspaceRoot: Path,
        val depth: Int,
        val initialQuery: String? = null,
    ) : CliOutput
    data class ExternalProcess(val process: CliExternalProcess) : CliOutput
    data object None : CliOutput
}

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
) : CliCommandExecutor {
    private val backendQueryHandlers: Map<KClass<out CliCommand.BackendQuery<*>>, suspend (CliCommand.BackendQuery<*>) -> RuntimeAttachedResult<*>> = mapOf(
        CliCommand.WorkspaceRefresh::class to { command ->
            command as CliCommand.WorkspaceRefresh
            cliService.workspaceRefresh(command.options, command.query)
        },
        CliCommand.ResolveSymbol::class to { command ->
            command as CliCommand.ResolveSymbol
            cliService.resolveSymbol(command.options, command.query)
        },
        CliCommand.FindReferences::class to { command ->
            command as CliCommand.FindReferences
            cliService.findReferences(command.options, command.query)
        },
        CliCommand.CallHierarchy::class to { command ->
            command as CliCommand.CallHierarchy
            cliService.callHierarchy(command.options, command.query)
        },
        CliCommand.TypeHierarchy::class to { command ->
            command as CliCommand.TypeHierarchy
            cliService.typeHierarchy(command.options, command.query)
        },
        CliCommand.SemanticInsertionPoint::class to { command ->
            command as CliCommand.SemanticInsertionPoint
            cliService.semanticInsertionPoint(command.options, command.query)
        },
        CliCommand.Diagnostics::class to { command ->
            command as CliCommand.Diagnostics
            cliService.diagnostics(command.options, command.query)
        },
        CliCommand.FileOutline::class to { command ->
            command as CliCommand.FileOutline
            cliService.fileOutline(command.options, command.query)
        },
        CliCommand.WorkspaceSymbol::class to { command ->
            command as CliCommand.WorkspaceSymbol
            cliService.workspaceSymbolSearch(command.options, command.query)
        },
        CliCommand.WorkspaceFiles::class to { command ->
            command as CliCommand.WorkspaceFiles
            cliService.workspaceFiles(command.options, command.query)
        },
        CliCommand.Implementations::class to { command ->
            command as CliCommand.Implementations
            cliService.implementations(command.options, command.query)
        },
        CliCommand.CodeActions::class to { command ->
            command as CliCommand.CodeActions
            cliService.codeActions(command.options, command.query)
        },
        CliCommand.Completions::class to { command ->
            command as CliCommand.Completions
            cliService.completions(command.options, command.query)
        },
        CliCommand.Rename::class to { command ->
            command as CliCommand.Rename
            cliService.rename(command.options, command.query)
        },
        CliCommand.ImportOptimize::class to { command ->
            command as CliCommand.ImportOptimize
            cliService.optimizeImports(command.options, command.query)
        },
        CliCommand.ApplyEdits::class to { command ->
            command as CliCommand.ApplyEdits
            cliService.applyEdits(command.options, command.query)
        },
    )

    override suspend fun execute(command: CliCommand): CliExecutionResult {
        return when (command) {
            is CliCommand.Help -> CliExecutionResult(
                output = CliOutput.Text(CliCommandCatalog.helpText(command.topic)),
            )

            CliCommand.Version -> CliExecutionResult(
                output = CliOutput.Text(CliCommandCatalog.versionText()),
            )

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

            is CliCommand.BackendQuery<*> -> executeBackendQuery(command)

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

            is CliCommand.InstallCopilotExtension -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.installCopilotExtension(command.options)),
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

            is CliCommand.Skill -> {
                val executor = SkillWrapperExecutor(cliService, json)
                val response = executor.execute(command)
                val encoded = SkillWrapperSerializer.encode(json, command.name, response)
                CliExecutionResult(
                    output = CliOutput.Text(encoded),
                )
            }

            is CliCommand.EvalSkill -> {
                val result = EvalSkillExecutor(json).execute(command.options)
                CliExecutionResult(output = result)
            }

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
                            ListSerializer(LowUsageSymbol.serializer()), engine.lowUsageSymbols(limit = command.limit, filter = filter),
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

    private suspend fun executeBackendQuery(command: CliCommand.BackendQuery<*>): CliExecutionResult {
        val dispatcher = backendQueryHandlers[command::class]
            ?: throw CliFailure(
                code = "CLI_USAGE",
                message = "Unsupported backend query command: ${command::class.simpleName}",
            )
        val result = dispatcher(command)
        return CliExecutionResult(
            output = CliOutput.JsonValue(
                result.payload ?: throw CliFailure(
                    code = "CLI_EXECUTION",
                    message = "Backend query ${command::class.simpleName} completed without a payload",
                ),
            ),
            daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
        )
    }
}
