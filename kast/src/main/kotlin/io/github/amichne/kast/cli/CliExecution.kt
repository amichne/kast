package io.github.amichne.kast.cli

import io.github.amichne.kast.standalone.StandaloneRuntime

internal sealed interface CliOutput {
    data class JsonValue(val value: Any) : CliOutput
    data class Text(val value: String) : CliOutput
    data object None : CliOutput
}

internal data class CliExecutionResult(
    val output: CliOutput,
    val daemonNote: String? = null,
)

internal data class RuntimeAttachedResult<out T>(
    val payload: T,
    val runtime: RuntimeCandidateStatus,
)

internal interface CliCommandExecutor {
    suspend fun execute(command: CliCommand): CliExecutionResult
}

internal class DefaultCliCommandExecutor(
    private val cliService: CliService,
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

            is CliCommand.DaemonStart -> {
                val result = cliService.daemonStart(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = daemonNoteFor(result),
                )
            }

            is CliCommand.DaemonStop -> {
                val result = cliService.daemonStop(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result),
                    daemonNote = daemonNoteFor(result),
                )
            }

            is CliCommand.Capabilities -> {
                val result = cliService.capabilities(command.options)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.ResolveSymbol -> {
                val result = cliService.resolveSymbol(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.FindReferences -> {
                val result = cliService.findReferences(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.Diagnostics -> {
                val result = cliService.diagnostics(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.Rename -> {
                val result = cliService.rename(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.ApplyEdits -> {
                val result = cliService.applyEdits(command.options, command.query)
                CliExecutionResult(
                    output = CliOutput.JsonValue(result.payload),
                    daemonNote = daemonNoteForRuntime(result.runtime),
                )
            }

            is CliCommand.Install -> CliExecutionResult(
                output = CliOutput.JsonValue(cliService.install(command.options)),
            )

            is CliCommand.InternalDaemonRun -> {
                StandaloneRuntime.run(checkNotNull(command.options.standaloneOptions))
                CliExecutionResult(output = CliOutput.None)
            }
        }
    }
}
