package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.skill.SkillWrapperExecutor
import io.github.amichne.kast.cli.skill.SkillWrapperSerializer
import io.github.amichne.kast.indexstore.MetricsEngine
import kotlinx.serialization.json.Json
import java.nio.file.Path

internal sealed interface CliOutput {
    data class JsonValue(val value: Any) : CliOutput
    data class Text(val value: String) : CliOutput
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

            is CliCommand.WorkspaceRefresh -> {
                val result = cliService.workspaceRefresh(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
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

            is CliCommand.ResolveSymbol -> {
                val result = cliService.resolveSymbol(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.FindReferences -> {
                val result = cliService.findReferences(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.CallHierarchy -> {
                val result = cliService.callHierarchy(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.TypeHierarchy -> {
                val result = cliService.typeHierarchy(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.WorkspaceFiles -> {
                val result = cliService.workspaceFiles(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.Implementations -> {
                val result = cliService.implementations(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.CodeActions -> {
                val result = cliService.codeActions(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.Completions -> {
                val result = cliService.completions(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.SemanticInsertionPoint -> {
                val result = cliService.semanticInsertionPoint(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.Diagnostics -> {
                val result = cliService.diagnostics(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.FileOutline -> {
                val result = cliService.fileOutline(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.WorkspaceSymbol -> {
                val result = cliService.workspaceSymbolSearch(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.Rename -> {
                val result = cliService.rename(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.ImportOptimize -> {
                val result = cliService.optimizeImports(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = result.daemonNote ?: daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.ApplyEdits -> {
                val result = cliService.applyEdits(command.options, command.query)
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

            is CliCommand.Smoke -> CliExecutionResult(
                output = CliOutput.ExternalProcess(cliService.smoke(command.options)),
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
                MetricsEngine(command.workspaceRoot).use { engine ->
                    val result = when (command.subcommand) {
                        MetricsSubcommand.FAN_IN -> engine.fanInRanking(command.limit)
                        MetricsSubcommand.FAN_OUT -> engine.fanOutRanking(command.limit)
                        MetricsSubcommand.COUPLING -> engine.moduleCouplingMatrix()
                        MetricsSubcommand.DEAD_CODE -> engine.deadCodeCandidates()
                        MetricsSubcommand.IMPACT -> engine.changeImpactRadius(
                            fqName = requireNotNull(command.symbol) { "--symbol is required for impact" },
                            depth = command.depth,
                        )
                    }
                    CliExecutionResult(output = CliOutput.JsonValue(result))
                }
            }
        }
    }
}
