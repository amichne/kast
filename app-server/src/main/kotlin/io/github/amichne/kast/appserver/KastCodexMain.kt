package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.host.CliRemoteClientHost
import io.github.amichne.kast.appserver.host.CodexIntegrationHost
import io.github.amichne.kast.appserver.host.DesktopStdioHost
import io.github.amichne.kast.appserver.host.admission.CodexServiceInvocation
import io.github.amichne.kast.appserver.host.admission.CodexHostInvocation
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnector
import io.github.amichne.kast.appserver.runtime.connectCodexUnixWebSocket
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

sealed interface CodexIntegrationRun {
    data class Completed(val exitCode: Int) : CodexIntegrationRun
    data class Rejected(val failure: CodexIntegrationFailure) : CodexIntegrationRun
}

enum class CodexIntegrationFailure {
    INSTALLATION_UNAVAILABLE,
    CONFIGURATION_REJECTED,
    BROKER_REJECTED,
    CLIENT_UNAVAILABLE,
    HOST_TRANSPORT_REJECTED,
    UPSTREAM_EXITED,
    STDIO_REJECTED,
    INTERRUPTED,
    ARGUMENTS_REJECTED,
    SHUTDOWN_REJECTED,
}

suspend fun runInstalledCodex(arguments: List<String>, kast: Path): CodexIntegrationRun {
    val requested = when (val admitted = CodexHostInvocation.admit(arguments)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.ARGUMENTS_REJECTED)
    }
    val invocation = when (val admitted = CodexServiceInvocation.admit(requested)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.ARGUMENTS_REJECTED)
    }
    if (System.getenv().containsKey("KAST_SAVED_CONFIGURATION_FAILURE")) {
        return CodexIntegrationRun.Rejected(CodexIntegrationFailure.CONFIGURATION_REJECTED)
    }
    val launch = when (val admitted = BrokerServiceLaunchCommand.resolve(kast, Path.of(System.getProperty("user.home")), System.getenv())) {
        is BrokerServiceLaunchCommandResolution.Resolved -> admitted.command
        is BrokerServiceLaunchCommandResolution.Rejected -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.CONFIGURATION_REJECTED)
    }
    val codex = when (val host = launch.host) {
        is BrokerHostSelection.Selected -> host.executable
        BrokerHostSelection.Disabled, BrokerHostSelection.NotConfigured -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.CLIENT_UNAVAILABLE)
    }
    when (MacOsPersistentBrokerServiceHost().ensure(launch)) {
        PersistentBrokerServiceAdmission.Ready -> Unit
        is PersistentBrokerServiceAdmission.Rejected -> return CodexIntegrationRun.Rejected(CodexIntegrationFailure.BROKER_REJECTED)
    }
    val host: CodexIntegrationHost = when (invocation) {
        is CodexServiceInvocation.Cli -> CliRemoteClientHost(
            codex,
            launch.publicSocket,
            invocation.arguments,
        )
        CodexServiceInvocation.Stdio -> DesktopStdioHost(
            System.`in`,
            System.out,
            BrokerUpstreamConnector {
                connectCodexUnixWebSocket(
                    launch.publicSocket,
                    4 * 1_024 * 1_024,
                    CONNECTION_TIMEOUT_MILLIS,
                )
            },
            4 * 1_024 * 1_024,
        )
    }
    return host.run { /* The service outlives this attachment. */ }
}

private const val CONNECTION_TIMEOUT_MILLIS = 10_000L

internal interface CodexIntegrationShutdownHooks {
    fun register(hook: Thread)
    fun remove(hook: Thread)
}

internal object JvmCodexIntegrationShutdownHooks : CodexIntegrationShutdownHooks {
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
