package io.github.amichne.kast.cli

import io.github.amichne.kast.standalone.StandaloneRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

internal class KastCli(
    private val processLauncher: ProcessLauncher = DefaultProcessLauncher(),
    private val json: Json = defaultCliJson(),
) {
    fun run(
        args: Array<String>,
        stdout: Appendable = System.out,
        stderr: Appendable = System.err,
    ): Int = runBlocking {
        val commandParser = CliCommandParser(json)
        val cliService = CliService(json, processLauncher)
        runCatching {
            execute(commandParser.parse(args), cliService, stdout)
        }.fold(
            onSuccess = { 0 },
            onFailure = { throwable ->
                writeCliJson(stderr, cliErrorFromThrowable(throwable), json)
                1
            },
        )
    }

    private suspend fun execute(
        command: CliCommand,
        cliService: CliService,
        stdout: Appendable,
    ) {
        when (command) {
            is CliCommand.WorkspaceStatus -> writeCliJson(stdout, cliService.workspaceStatus(command.options), json)
            is CliCommand.WorkspaceEnsure -> writeCliJson(stdout, cliService.workspaceEnsure(command.options), json)
            is CliCommand.DaemonStart -> writeCliJson(stdout, cliService.daemonStart(command.options), json)
            is CliCommand.DaemonStop -> writeCliJson(stdout, cliService.daemonStop(command.options), json)
            is CliCommand.Capabilities -> writeCliJson(stdout, cliService.capabilities(command.options), json)
            is CliCommand.ResolveSymbol -> writeCliJson(stdout, cliService.resolveSymbol(command.options, command.query), json)
            is CliCommand.FindReferences -> writeCliJson(stdout, cliService.findReferences(command.options, command.query), json)
            is CliCommand.Diagnostics -> writeCliJson(stdout, cliService.diagnostics(command.options, command.query), json)
            is CliCommand.Rename -> writeCliJson(stdout, cliService.rename(command.options, command.query), json)
            is CliCommand.ApplyEdits -> writeCliJson(stdout, cliService.applyEdits(command.options, command.query), json)
            is CliCommand.InternalDaemonRun -> StandaloneRuntime.run(checkNotNull(command.options.standaloneOptions))
        }
    }
}
