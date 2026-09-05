package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapPhase
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.distribution.contract.bootstrap.correctiveAction
import java.time.Duration

internal fun interface RuntimeStartupProgressSink {
    fun publish(state: SemanticRuntimeBootstrapState)
    fun discoveringRuntime() = Unit
}

/** The only terminal effect: fixed phase text, elapsed time and finite stage counts on stderr. */
internal class TerminalRuntimeStartupProgress(
    private val terminal: Boolean,
    private val nanoTime: () -> Long,
    private val writeLine: (String) -> Unit,
) : RuntimeStartupProgressSink {
    private val started = nanoTime()
    private var last: SemanticRuntimeBootstrapState? = null
    private var lastWrittenAt = started
    private var lines = 0

    override fun discoveringRuntime() {
        if (terminal && lines == 0) {
            writeLine("kast: discovering runtime; elapsed=0s; progress=0/${SemanticRuntimeBootstrapPhase.entries.size} phases")
            lines++
        }
    }

    override fun publish(state: SemanticRuntimeBootstrapState) {
        if (!terminal || lines >= MAXIMUM_LINES) return
        if (state is SemanticRuntimeBootstrapState.Starting && lines >= MAXIMUM_LINES - 1) return
        val now = nanoTime()
        if (state == last && now - lastWrittenAt < HEARTBEAT.toNanos()) return
        val elapsed = Duration.ofNanos(now - started).seconds
        val text = when (state) {
            is SemanticRuntimeBootstrapState.Starting ->
                "${state.phase.displayName}; elapsed=${elapsed}s; " +
                    "progress=${state.phase.completedPhases}/${state.phase.totalPhases} phases"
            is SemanticRuntimeBootstrapState.Ready ->
                "ready; elapsed=${elapsed}s; " +
                    "progress=${SemanticRuntimeBootstrapPhase.entries.size}/${SemanticRuntimeBootstrapPhase.entries.size} phases"
            is SemanticRuntimeBootstrapState.Rejected ->
                "rejected during ${state.phase.displayName}; elapsed=${elapsed}s; " +
                    "cause=${state.failure.wireName}; next: ${state.correctiveAction().instruction}"
        }
        writeLine("kast: $text")
        last = state
        lastWrittenAt = now
        lines++
    }

    companion object {
        private val HEARTBEAT = Duration.ofSeconds(5)
        private const val MAXIMUM_LINES = 256
        fun create(): RuntimeStartupProgressSink = TerminalRuntimeStartupProgress(
            System.console()?.isTerminal == true,
            System::nanoTime,
            System.err::println,
        )
    }
}
