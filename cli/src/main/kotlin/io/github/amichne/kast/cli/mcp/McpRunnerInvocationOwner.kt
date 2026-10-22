package io.github.amichne.kast.cli.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** The input owner selects cancellation. The worker owns one interruptible semantic exchange. */
internal class McpRunnerInvocationOwner(
    private val publish: (McpResponse) -> Unit,
    private val cancelled: (String) -> Unit,
    private val failed: (String) -> Unit,
) {
    private sealed interface State {
        data object Idle : State

        data class Owned(val invocation: Invocation) : State
    }

    private var state: State = State.Idle

    val active: Boolean
        get() =
            when (val current = state) {
                State.Idle -> false
                is State.Owned -> current.invocation.active
            }

    fun start(id: JsonElement, tool: String, operation: () -> McpResponse) {
        stopCompleted()
        check(state == State.Idle)
        val invocation = Invocation(id, tool, operation, this)
        state = State.Owned(invocation)
        invocation.start()
    }

    fun cancel(request: McpRequest) {
        if (request.method == "notifications/cancelled") {
            val cancellation =
                try {
                    request.params?.let { mcpWire.decodeFromJsonElement<McpCancellationParams>(it) }
                } catch (_: SerializationException) {
                    null
                }
            when (val current = state) {
                State.Idle -> Unit
                is State.Owned ->
                    if (cancellation?.requestId == current.invocation.id) {
                        current.invocation.cancel()
                        current.invocation.join()
                    }
            }
        }
    }

    fun stop() {
        when (val current = state) {
            State.Idle -> Unit
            is State.Owned -> {
                current.invocation.cancel()
                current.invocation.join()
                state = State.Idle
            }
        }
    }

    private fun stopCompleted() {
        when (val current = state) {
            State.Idle -> Unit
            is State.Owned ->
                if (!current.invocation.active) {
                    current.invocation.join()
                    state = State.Idle
                }
        }
    }

    private class Invocation(
        val id: JsonElement,
        private val tool: String,
        private val operation: () -> McpResponse,
        private val owner: McpRunnerInvocationOwner,
    ) {
        private enum class Completion {
            RUNNING,
            PUBLISHED,
            CANCELLED,
            FAILED,
        }

        private val lock = Any()
        private var completion = Completion.RUNNING
        private val worker = Thread(::execute, "kast-mcp-runner")

        private fun execute() {
            try {
                publishResponse(operation())
            } catch (failure: CancellationException) {
                if (active) throw failure
            } finally {
                val failedBeforePublication =
                    synchronized(lock) {
                        if (completion == Completion.RUNNING) {
                            completion = Completion.FAILED
                            true
                        } else false
                    }
                if (failedBeforePublication) owner.failed(tool)
            }
        }

        private fun publishResponse(response: McpResponse) =
            synchronized(lock) {
                when (completion) {
                    Completion.RUNNING -> {
                        owner.publish(response)
                        completion = Completion.PUBLISHED
                    }
                    Completion.CANCELLED -> Unit
                    Completion.PUBLISHED,
                    Completion.FAILED -> error("A worker publishes one response")
                }
            }

        val active: Boolean
            get() = synchronized(lock) { completion == Completion.RUNNING }

        fun start() = worker.start()

        fun cancel() =
            synchronized(lock) {
                when (completion) {
                    Completion.RUNNING -> {
                        completion = Completion.CANCELLED
                        owner.cancelled(tool)
                        worker.interrupt()
                    }
                    Completion.PUBLISHED,
                    Completion.FAILED,
                    Completion.CANCELLED -> Unit
                }
            }

        fun join() {
            var interrupted = false
            var drained = false
            while (!drained) {
                try {
                    worker.join()
                    drained = true
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

@Serializable private data class McpCancellationParams(val requestId: JsonPrimitive)
