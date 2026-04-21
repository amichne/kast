package io.github.amichne.kast.cli

import io.github.amichne.kast.api.client.StandaloneServerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

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
        internal val NO_QUALIFYING_RESULT_CODES: Set<String> = setOf(
            "DEMO_NO_QUALIFYING_SYMBOL",
            "DEMO_NO_SYMBOLS",
        )
        internal val INFRASTRUCTURE_UNAVAILABLE_CODES: Set<String> = setOf(
            "DEMO_INDEX_UNAVAILABLE",
            "DAEMON_NOT_RUNNING",
            "CAPABILITIES_UNAVAILABLE",
            "RUNTIME_TIMEOUT",
        )

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
            val exitCode = writeCliOutput(stdout, stderr, execution.output)
            execution.daemonNote?.let { note ->
                stderr.append(note)
                stderr.append('\n')
            }
            exitCode
        }.fold(
            onSuccess = { it },
            onFailure = { throwable ->
                val errorResponse = cliErrorFromThrowable(throwable)
                writeCliJson(stderr, errorResponse, json)
                exitCodeFor(errorResponse.code)
            },
        )
    }

    /**
     * Maps CLI failure codes onto deterministic process exit codes so demo
     * automation (and `kast-demo-spec.md`) can distinguish "we ran but found
     * nothing interesting" (1) from "we couldn't run because the backend or
     * index isn't available" (2). All other failures fall back to 1.
     */
    private fun exitCodeFor(code: String?): Int = when (code) {
        in KastCli.NO_QUALIFYING_RESULT_CODES -> 1
        in KastCli.INFRASTRUCTURE_UNAVAILABLE_CODES -> 2
        else -> 1
    }

    private suspend fun writeCliOutput(
        stdout: Appendable,
        stderr: Appendable,
        output: CliOutput,
    ): Int {
        return when (output) {
            is CliOutput.JsonValue -> {
                writeCliJson(stdout, output.value, json)
                0
            }

            is CliOutput.Text -> {
                stdout.append(output.value)
                if (!output.value.endsWith('\n')) {
                    stdout.append('\n')
                }
                0
            }

            is CliOutput.ExternalProcess -> runExternalProcess(output.process, stdout, stderr)
            CliOutput.None -> 0
        }
    }

    private suspend fun runExternalProcess(
        processSpec: CliExternalProcess,
        stdout: Appendable,
        stderr: Appendable,
    ): Int {
        val processBuilder = ProcessBuilder(processSpec.command)
        processSpec.workingDirectory?.let { workingDirectory ->
            processBuilder.directory(workingDirectory.toFile())
        }
        processBuilder.environment().putAll(processSpec.environment)
        if (stdout === System.out && stderr === System.err) {
            return withContext(Dispatchers.IO) {
                processBuilder
                    .inheritIO()
                    .start()
                    .waitFor()
            }
        }

        return coroutineScope {
            val process = withContext(Dispatchers.IO) { processBuilder.start() }
            val stdoutCapture = async(Dispatchers.IO) {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
            }
            val stderrCapture = async(Dispatchers.IO) {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
            }
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
            stdout.append(stdoutCapture.await())
            stderr.append(stderrCapture.await())
            exitCode
        }
    }
}
