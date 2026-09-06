package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.cli.installedKastExecutable
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** The integration host owns the broker and client lifetime; semantic readiness stays in Kast. */
object KastCodexMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val result = runBlocking { runInstalledCodex(arguments.toList()) }
        when (result) {
            is CodexIntegrationRun.Completed -> exitProcess(result.exitCode)
            is CodexIntegrationRun.Rejected -> {
                System.err.println("kast-codex: ${result.failure.name.lowercase().replace('_', '-')}")
                exitProcess(64)
            }
        }
    }
}

internal sealed interface CodexIntegrationRun {
    data class Completed(val exitCode: Int) : CodexIntegrationRun
    data class Rejected(val failure: CodexIntegrationFailure) : CodexIntegrationRun
}

internal enum class CodexIntegrationFailure { INSTALLATION_UNAVAILABLE, CONFIGURATION_REJECTED, BROKER_REJECTED, CLIENT_UNAVAILABLE, INTERRUPTED, ARGUMENTS_REJECTED, SHUTDOWN_REJECTED }

internal suspend fun runInstalledCodex(arguments: List<String>): CodexIntegrationRun {
    val clientArguments = when (val admitted = CodexClientArguments.admit(arguments)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.ARGUMENTS_REJECTED)
    }
    if (System.getenv().containsKey("KAST_SAVED_CONFIGURATION_FAILURE")) {
        return CodexIntegrationRun.Rejected(CodexIntegrationFailure.CONFIGURATION_REJECTED)
    }
    val kast = when (val installed = installedKastExecutable()) {
        is Refinement.Refined -> installed.value
        is Refinement.Rejected -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.INSTALLATION_UNAVAILABLE)
    }
    val launch = when (val admitted = BrokerServiceLaunchCommand.resolve(kast, Path.of(System.getProperty("user.home")), System.getenv())) {
        is BrokerServiceLaunchCommandResolution.Resolved -> admitted.command
        is BrokerServiceLaunchCommandResolution.Rejected -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.CONFIGURATION_REJECTED)
    }
    val configuration = when (val admitted = InstalledBrokerServerConfiguration.admit(
        kast, launch.userHome, System.getenv(), clientTransport = BrokerClientTransport.INTEGRATION_OWNED,
    )) {
        is InstalledBrokerServerConfiguration.Configured -> admitted.options
        is InstalledBrokerServerConfiguration.Rejected -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.CONFIGURATION_REJECTED)
    }
    val server = when (val started = InstalledBrokerServer.start(configuration)) {
        is InstalledBrokerServerStart.Started -> started.server
        is InstalledBrokerServerStart.Rejected -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.BROKER_REJECTED)
    }
    return runOwnedCodexClient(
        closeServer = server::close,
        startClient = {
            ProcessBuilder(listOf(launch.codex.toString(), "--remote", "unix://${configuration.publicSocket.path}") + clientArguments.values)
                .inheritIO().start()
        },
    )
}

internal interface CodexIntegrationShutdownHooks {
    fun register(hook: Thread)
    fun remove(hook: Thread)
}

private object JvmCodexIntegrationShutdownHooks : CodexIntegrationShutdownHooks {
    override fun register(hook: Thread) { Runtime.getRuntime().addShutdownHook(hook) }
    override fun remove(hook: Thread) { Runtime.getRuntime().removeShutdownHook(hook) }
}

internal suspend fun runOwnedCodexClient(
    closeServer: suspend () -> Unit,
    startClient: () -> Process,
    shutdownHooks: CodexIntegrationShutdownHooks = JvmCodexIntegrationShutdownHooks,
): CodexIntegrationRun {
    val lifetime = OwnedCodexIntegration(closeServer)
    val hook = Thread({ lifetime.close() }, "kast-codex-shutdown")
    return try {
        val result = try {
            shutdownHooks.register(hook)
            when (val started = lifetime.startClient(startClient)) {
                is CodexClientStart.Started -> CodexIntegrationRun.Completed(started.client.waitFor())
                CodexClientStart.Shutdown -> CodexIntegrationRun.Rejected(CodexIntegrationFailure.INTERRUPTED)
            }
        } catch (_: IOException) {
            CodexIntegrationRun.Rejected(CodexIntegrationFailure.CLIENT_UNAVAILABLE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            CodexIntegrationRun.Rejected(CodexIntegrationFailure.INTERRUPTED)
        } catch (_: IllegalStateException) {
            // Shutdown-hook registration rejects when JVM shutdown has already begun.
            CodexIntegrationRun.Rejected(CodexIntegrationFailure.INTERRUPTED)
        }
        when (lifetime.close()) {
            CodexIntegrationShutdown.COMPLETED -> result
            CodexIntegrationShutdown.CLIENT_UNREAPED,
            CodexIntegrationShutdown.SERVER_TIMED_OUT,
            CodexIntegrationShutdown.SERVER_INTERRUPTED,
            CodexIntegrationShutdown.FAILED,
                -> CodexIntegrationRun.Rejected(CodexIntegrationFailure.SHUTDOWN_REJECTED)
        }
    } finally {
        try {
            lifetime.close()
        } finally {
            try { shutdownHooks.remove(hook) } catch (_: IllegalStateException) { /* Shutdown still owns its registered hook. */ }
        }
    }
}

private enum class CodexArgumentFailure { REMOTE_OVERRIDE, TOO_MANY_ARGUMENTS, TOO_MANY_BYTES, INVALID_CHARACTER }

/** A copied argument vector cannot redirect the client away from the host-owned broker. */
private class CodexClientArguments private constructor(val values: List<String>) {
    companion object {
        fun admit(arguments: List<String>): Refinement<CodexClientArguments, CodexArgumentFailure> {
            if (arguments.size > 256) return Refinement.Rejected(CodexArgumentFailure.TOO_MANY_ARGUMENTS)
            if (arguments.any { '\u0000' in it }) return Refinement.Rejected(CodexArgumentFailure.INVALID_CHARACTER)
            if (arguments.any { it == "--remote" || it.startsWith("--remote=") }) {
                return Refinement.Rejected(CodexArgumentFailure.REMOTE_OVERRIDE)
            }
            if (arguments.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } > 65_536) {
                return Refinement.Rejected(CodexArgumentFailure.TOO_MANY_BYTES)
            }
            return Refinement.Refined(CodexClientArguments(arguments.toList()))
        }
    }
}

private sealed interface CodexClientStart {
    class Started(val client: Process) : CodexClientStart
    data object Shutdown : CodexClientStart
}

private enum class CodexIntegrationShutdown { COMPLETED, CLIENT_UNREAPED, SERVER_TIMED_OUT, SERVER_INTERRUPTED, FAILED }

/** The lock spans cleanup so concurrent hook and normal completion join the same owned transition. */
private class OwnedCodexIntegration(private val closeServer: suspend () -> Unit) {
    private sealed interface State {
        data object AwaitingClient : State
        class Running(val client: Process) : State
        class Released(val result: CodexIntegrationShutdown) : State
    }
    private var state: State = State.AwaitingClient

    @Synchronized
    fun startClient(start: () -> Process): CodexClientStart = when (state) {
        State.AwaitingClient -> start().let { client ->
            state = State.Running(client)
            CodexClientStart.Started(client)
        }
        is State.Running, is State.Released -> CodexClientStart.Shutdown
    }

    @Synchronized
    fun close(): CodexIntegrationShutdown {
        val owned = state
        if (owned is State.Released) return owned.result
        state = State.Released(CodexIntegrationShutdown.FAILED)
        var interrupted = Thread.interrupted()
        var result = CodexIntegrationShutdown.COMPLETED
        try {
            try {
                if (owned is State.Running && owned.client.isAlive) {
                    val client = owned.client
                    client.destroy()
                    try {
                        if (!client.waitFor(2, TimeUnit.SECONDS)) {
                            client.destroyForcibly()
                            if (!client.waitFor(2, TimeUnit.SECONDS)) result = CodexIntegrationShutdown.CLIENT_UNREAPED
                        }
                    } catch (_: InterruptedException) {
                        interrupted = true
                        client.destroyForcibly()
                        try {
                            if (!client.waitFor(2, TimeUnit.SECONDS)) result = CodexIntegrationShutdown.CLIENT_UNREAPED
                        } catch (_: InterruptedException) {
                            interrupted = true
                            result = CodexIntegrationShutdown.CLIENT_UNREAPED
                        }
                    }
                }
            } finally {
                try {
                    runBlocking { withTimeout(10_000) { closeServer() } }
                } catch (_: TimeoutCancellationException) {
                    result = CodexIntegrationShutdown.SERVER_TIMED_OUT
                } catch (_: InterruptedException) {
                    interrupted = true
                    result = CodexIntegrationShutdown.SERVER_INTERRUPTED
                }
            }
            state = State.Released(result)
            return result
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
