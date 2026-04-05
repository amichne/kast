package io.github.amichne.kast.cli

import io.github.amichne.kast.api.StandaloneServerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class KastCli private constructor(
    private val processLauncher: ProcessLauncher,
    private val json: Json,
    private val commandExecutorFactory: (Json, ProcessLauncher) -> CliCommandExecutor,
) {
    constructor(
        internalDaemonRunner: (suspend (StandaloneServerOptions) -> Unit)? = null,
    ) : this(
        processLauncher = DefaultProcessLauncher(),
        json = defaultCliJson(),
        commandExecutorFactory = { configuredJson: Json, configuredProcessLauncher: ProcessLauncher ->
            DefaultCliCommandExecutor(
                cliService = CliService(configuredJson, configuredProcessLauncher),
                internalDaemonRunner = internalDaemonRunner,
            )
        },
    )

    internal companion object {
        fun testInstance(
            processLauncher: ProcessLauncher = DefaultProcessLauncher(),
            json: Json = defaultCliJson(),
            commandExecutorFactory: (Json, ProcessLauncher) -> CliCommandExecutor = { configuredJson, configuredProcessLauncher ->
                DefaultCliCommandExecutor(CliService(configuredJson, configuredProcessLauncher))
            },
        ): KastCli = KastCli(
            processLauncher = processLauncher,
            json = json,
            commandExecutorFactory = commandExecutorFactory,
        )
    }

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
