package io.github.amichne.kast.cli

import io.github.amichne.kast.api.StandaloneServerOptions
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
    private val internalDaemonRunner: (suspend (StandaloneServerOptions) -> Unit)? = null,
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

            is CliCommand.InternalDaemonRun -> {
                val runner = internalDaemonRunner
                    ?: throw CliFailure(
                        code = "UNSUPPORTED_COMMAND",
                        message = "internal daemon-run requires the JVM distribution",
                    )
                runner(checkNotNull(command.options.standaloneOptions))
                CliExecutionResult(output = CliOutput.None)
            }
        }
    }
}
