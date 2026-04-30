package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.CliCommandExecutor
import io.github.amichne.kast.cli.tty.CliCommandParser
import io.github.amichne.kast.cli.tty.CliExternalProcess
import io.github.amichne.kast.cli.tty.CliOutput
import io.github.amichne.kast.cli.tty.CliService
import io.github.amichne.kast.cli.tty.DefaultCliCommandExecutor
import io.github.amichne.kast.cli.tty.cliErrorFromThrowable
import io.github.amichne.kast.cli.tty.defaultCliJson
import io.github.amichne.kast.cli.tty.writeCliJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

class KastCli private constructor(
    private val json: Json,
    private val commandExecutorFactory: (Json) -> CliCommandExecutor,
) {
    constructor() : this(
        json = defaultCliJson(),
        commandExecutorFactory = { configuredJson: Json ->
            DefaultCliCommandExecutor(
                cliService = CliService(configuredJson),
            )
        },
    )

    internal companion object {
        fun testInstance(
            json: Json = defaultCliJson(),
            commandExecutorFactory: (Json) -> CliCommandExecutor = { configuredJson ->
                DefaultCliCommandExecutor(CliService(configuredJson))
            },
        ): KastCli = KastCli(
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
        val commandExecutor = commandExecutorFactory(json)
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
                writeCliJson(stderr, cliErrorFromThrowable(throwable), json)
                1
            },
        )
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

            is CliOutput.InteractiveGraph -> writeInteractiveGraph(output.graph, stdout)
            is CliOutput.InteractiveGraphPicker -> writeInteractiveGraphPicker(output, stdout, stderr)
            is CliOutput.ExternalProcess -> runExternalProcess(output.process, stdout, stderr)
            CliOutput.None -> 0
        }
    }

    private suspend fun writeInteractiveGraph(
        graph: io.github.amichne.kast.indexstore.MetricsGraph,
        stdout: Appendable,
    ): Int {
        if (stdout !== System.out) {
            val rendered = MetricsGraphShell.render(graph)
            stdout.append(rendered)
            if (!rendered.endsWith('\n')) {
                stdout.append('\n')
            }
            return 0
        }
        return withContext(Dispatchers.IO) {
            MetricsGraphTerminal(graph).run()
        }
    }

    private suspend fun writeInteractiveGraphPicker(
        spec: CliOutput.InteractiveGraphPicker,
        stdout: Appendable,
        stderr: Appendable,
    ): Int {
        if (stdout !== System.out) {
            stderr.append(
                "Interactive symbol picker requires a TTY. Pass --symbol=<fqName> to render the graph as JSON.\n",
            )
            return 2
        }
        return withContext(Dispatchers.IO) {
            MetricsGraphPicker(
                workspaceRoot = spec.workspaceRoot,
                depth = spec.depth,
                initialQuery = spec.initialQuery.orEmpty(),
            ).run()
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
