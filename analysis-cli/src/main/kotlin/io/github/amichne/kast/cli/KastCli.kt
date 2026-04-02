package io.github.amichne.kast.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

internal class KastCli(
    private val processLauncher: ProcessLauncher = DefaultProcessLauncher(),
    private val json: Json = defaultCliJson(),
    private val commandExecutorFactory: (Json, ProcessLauncher) -> CliCommandExecutor = { configuredJson, configuredProcessLauncher ->
        DefaultCliCommandExecutor(CliService(configuredJson, configuredProcessLauncher))
    },
) {
    fun run(
        args: Array<String>,
        stdout: Appendable = System.out,
        stderr: Appendable = System.err,
    ): Int = runBlocking {
        val commandParser = CliCommandParser(json)
        val commandExecutor = commandExecutorFactory(json, processLauncher)
        runCatching {
            val execution = commandExecutor.execute(commandParser.parse(args))
            writeCliOutput(stdout, execution.output)
            execution.daemonNote?.let { note ->
                stderr.append(note)
                stderr.append('\n')
            }
        }.fold(
            onSuccess = { 0 },
            onFailure = { throwable ->
                writeCliJson(stderr, cliErrorFromThrowable(throwable), json)
                1
            },
        )
    }

    private fun writeCliOutput(
        stdout: Appendable,
        output: CliOutput,
    ) {
        when (output) {
            is CliOutput.JsonValue -> writeCliJson(stdout, output.value, json)
            is CliOutput.Text -> {
                stdout.append(output.value)
                if (!output.value.endsWith('\n')) {
                    stdout.append('\n')
                }
            }

            CliOutput.None -> Unit
        }
    }
}
