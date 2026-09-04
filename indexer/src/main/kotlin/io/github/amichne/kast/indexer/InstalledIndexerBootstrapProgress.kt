package io.github.amichne.kast.indexer

import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeBootstrapPhase

/** Ordered installed sidecar bootstrap phases that may be observed before endpoint readiness. */
enum class InstalledIndexerBootstrapPhase {
    DISCOVERING_RUNTIME,
    GRADLE_JVM_SELECTION,
    PROJECT_IMPORT,
    INDEXING,
    MODEL_CAPTURE,
    RUNTIME_ASSEMBLY,
    TRANSPORT_ACTIVATION,
}

/** A proven count within the closed installed bootstrap phase set. */
@JvmInline
value class InstalledIndexerBootstrapPhaseCount private constructor(
    val value: Int,
) {
    companion object {
        internal fun completed(phase: InstalledIndexerBootstrapPhase):
            InstalledIndexerBootstrapPhaseCount = InstalledIndexerBootstrapPhaseCount(phase.ordinal)

        internal fun total(): InstalledIndexerBootstrapPhaseCount =
            InstalledIndexerBootstrapPhaseCount(InstalledIndexerBootstrapPhase.entries.size)
    }
}

enum class InstalledIndexerBootstrapAdvanceFailure {
    PHASE_OUT_OF_ORDER,
}

sealed interface InstalledIndexerBootstrapTerminalFailure {
    data class Runtime internal constructor(
        val failures: Set<InstalledKastRuntimeFailure>,
    ) : InstalledIndexerBootstrapTerminalFailure

    data class Transport(
        val failure: IndexerTransportFailure,
    ) : InstalledIndexerBootstrapTerminalFailure
}

/** Closed externally observable sidecar bootstrap state. */
sealed interface InstalledIndexerBootstrapState {
    val completedPhases: InstalledIndexerBootstrapPhaseCount
    val totalPhases: InstalledIndexerBootstrapPhaseCount

    data class Starting(
        val phase: InstalledIndexerBootstrapPhase,
        override val completedPhases: InstalledIndexerBootstrapPhaseCount,
        override val totalPhases: InstalledIndexerBootstrapPhaseCount,
    ) : InstalledIndexerBootstrapState

    data class Ready(
        override val completedPhases: InstalledIndexerBootstrapPhaseCount,
        override val totalPhases: InstalledIndexerBootstrapPhaseCount,
    ) : InstalledIndexerBootstrapState

    data class Rejected(
        val phase: InstalledIndexerBootstrapPhase,
        override val completedPhases: InstalledIndexerBootstrapPhaseCount,
        override val totalPhases: InstalledIndexerBootstrapPhaseCount,
        val failure: InstalledIndexerBootstrapTerminalFailure,
    ) : InstalledIndexerBootstrapState

    data class TransitionRejected(
        val phase: InstalledIndexerBootstrapPhase,
        override val completedPhases: InstalledIndexerBootstrapPhaseCount,
        override val totalPhases: InstalledIndexerBootstrapPhaseCount,
        val failure: InstalledIndexerBootstrapAdvanceFailure,
    ) : InstalledIndexerBootstrapState
}

sealed interface InstalledIndexerBootstrapAdvance {
    data class Advanced(
        val progress: InstalledIndexerBootstrapProgress,
    ) : InstalledIndexerBootstrapAdvance

    data class Rejected(
        val failure: InstalledIndexerBootstrapAdvanceFailure,
    ) : InstalledIndexerBootstrapAdvance
}

/**
 * One immutable active bootstrap position. Construction and transitions admit only the canonical
 * phase order, so observed progress cannot skip or repeat import, indexing, or model capture.
 */
class InstalledIndexerBootstrapProgress private constructor(
    val phase: InstalledIndexerBootstrapPhase,
) {
    val completedPhases: InstalledIndexerBootstrapPhaseCount =
        InstalledIndexerBootstrapPhaseCount.completed(phase)
    val totalPhases: InstalledIndexerBootstrapPhaseCount =
        InstalledIndexerBootstrapPhaseCount.total()

    fun snapshot(): InstalledIndexerBootstrapState.Starting =
        InstalledIndexerBootstrapState.Starting(phase, completedPhases, totalPhases)

    fun advance(next: InstalledIndexerBootstrapPhase): InstalledIndexerBootstrapAdvance =
        if (phase.next() == next) {
            InstalledIndexerBootstrapAdvance.Advanced(InstalledIndexerBootstrapProgress(next))
        } else {
            InstalledIndexerBootstrapAdvance.Rejected(
                InstalledIndexerBootstrapAdvanceFailure.PHASE_OUT_OF_ORDER,
            )
        }

    fun ready(): InstalledIndexerBootstrapState =
        if (phase == InstalledIndexerBootstrapPhase.TRANSPORT_ACTIVATION) {
            InstalledIndexerBootstrapState.Ready(totalPhases, totalPhases)
        } else {
            InstalledIndexerBootstrapState.TransitionRejected(
                phase,
                completedPhases,
                totalPhases,
                InstalledIndexerBootstrapAdvanceFailure.PHASE_OUT_OF_ORDER,
            )
        }

    fun reject(
        failure: InstalledIndexerBootstrapTerminalFailure,
    ): InstalledIndexerBootstrapState.Rejected = InstalledIndexerBootstrapState.Rejected(
        phase,
        completedPhases,
        totalPhases,
        failure,
    )

    companion object {
        fun start(): InstalledIndexerBootstrapProgress = InstalledIndexerBootstrapProgress(
            InstalledIndexerBootstrapPhase.DISCOVERING_RUNTIME,
        )
    }
}

fun interface InstalledIndexerBootstrapStateSink {
    fun publish(state: InstalledIndexerBootstrapState)
}

/** Finite reporter outcomes for attempted installed bootstrap state publication. */
sealed interface InstalledIndexerBootstrapReport {
    data class Published(
        val state: InstalledIndexerBootstrapState,
    ) : InstalledIndexerBootstrapReport

    data class Rejected(
        val failure: InstalledIndexerBootstrapReportFailure,
    ) : InstalledIndexerBootstrapReport
}

sealed interface InstalledIndexerBootstrapReportFailure {
    data class Advance(
        val failure: InstalledIndexerBootstrapAdvanceFailure,
    ) : InstalledIndexerBootstrapReportFailure

    data object EmptyRuntimeFailureSet : InstalledIndexerBootstrapReportFailure
}

/**
 * Process-owned bridge from runtime observations to one monotonic installed sidecar state stream.
 */
class InstalledIndexerBootstrapReporter(
    private val sink: InstalledIndexerBootstrapStateSink,
) {
    private var progress: InstalledIndexerBootstrapProgress =
        InstalledIndexerBootstrapProgress.start()
    private var terminal: Boolean = false

    init {
        sink.publish(progress.snapshot())
    }

    fun observe(phase: InstalledRuntimeBootstrapPhase): InstalledIndexerBootstrapReport {
        val sidecarPhase = phase.sidecarPhase()
        if (sidecarPhase == progress.phase && !terminal) {
            return InstalledIndexerBootstrapReport.Published(progress.snapshot())
        }
        return advance(sidecarPhase)
    }

    fun beginTransportActivation(): InstalledIndexerBootstrapReport =
        advance(InstalledIndexerBootstrapPhase.TRANSPORT_ACTIVATION)

    fun ready(): InstalledIndexerBootstrapReport {
        if (terminal) {
            return InstalledIndexerBootstrapReport.Rejected(
                InstalledIndexerBootstrapReportFailure.Advance(
                    InstalledIndexerBootstrapAdvanceFailure.PHASE_OUT_OF_ORDER,
                ),
            )
        }
        val state = progress.ready()
        return when (state) {
            is InstalledIndexerBootstrapState.Ready -> {
                sink.publish(state)
                terminal = true
                InstalledIndexerBootstrapReport.Published(state)
            }
            is InstalledIndexerBootstrapState.TransitionRejected ->
                InstalledIndexerBootstrapReport.Rejected(
                    InstalledIndexerBootstrapReportFailure.Advance(state.failure),
                )
            is InstalledIndexerBootstrapState.Rejected,
            is InstalledIndexerBootstrapState.Starting,
                -> InstalledIndexerBootstrapReport.Rejected(
                    InstalledIndexerBootstrapReportFailure.Advance(
                        InstalledIndexerBootstrapAdvanceFailure.PHASE_OUT_OF_ORDER,
                    ),
                )
        }
    }

    fun rejectRuntime(
        failures: Set<InstalledKastRuntimeFailure>,
    ): InstalledIndexerBootstrapReport = if (failures.isEmpty()) {
        InstalledIndexerBootstrapReport.Rejected(
            InstalledIndexerBootstrapReportFailure.EmptyRuntimeFailureSet,
        )
    } else {
        reject(InstalledIndexerBootstrapTerminalFailure.Runtime(failures))
    }

    fun rejectTransport(
        failure: IndexerTransportFailure,
    ): InstalledIndexerBootstrapReport = reject(
        InstalledIndexerBootstrapTerminalFailure.Transport(failure),
    )

    private fun advance(
        phase: InstalledIndexerBootstrapPhase,
    ): InstalledIndexerBootstrapReport {
        if (terminal) {
            return InstalledIndexerBootstrapReport.Rejected(
                InstalledIndexerBootstrapReportFailure.Advance(
                    InstalledIndexerBootstrapAdvanceFailure.PHASE_OUT_OF_ORDER,
                ),
            )
        }
        return when (val advanced = progress.advance(phase)) {
            is InstalledIndexerBootstrapAdvance.Advanced -> {
                progress = advanced.progress
                val state = progress.snapshot()
                sink.publish(state)
                InstalledIndexerBootstrapReport.Published(state)
            }
            is InstalledIndexerBootstrapAdvance.Rejected ->
                InstalledIndexerBootstrapReport.Rejected(
                    InstalledIndexerBootstrapReportFailure.Advance(advanced.failure),
                )
        }
    }

    private fun reject(
        failure: InstalledIndexerBootstrapTerminalFailure,
    ): InstalledIndexerBootstrapReport {
        if (terminal) {
            return InstalledIndexerBootstrapReport.Rejected(
                InstalledIndexerBootstrapReportFailure.Advance(
                    InstalledIndexerBootstrapAdvanceFailure.PHASE_OUT_OF_ORDER,
                ),
            )
        }
        val state = progress.reject(failure)
        terminal = true
        sink.publish(state)
        return InstalledIndexerBootstrapReport.Published(state)
    }
}

private fun InstalledRuntimeBootstrapPhase.sidecarPhase(): InstalledIndexerBootstrapPhase =
    when (this) {
        InstalledRuntimeBootstrapPhase.DISCOVERING_RUNTIME ->
            InstalledIndexerBootstrapPhase.DISCOVERING_RUNTIME
        InstalledRuntimeBootstrapPhase.GRADLE_JVM_SELECTION ->
            InstalledIndexerBootstrapPhase.GRADLE_JVM_SELECTION
        InstalledRuntimeBootstrapPhase.TRANSPORT_ACTIVATION ->
            InstalledIndexerBootstrapPhase.TRANSPORT_ACTIVATION
        InstalledRuntimeBootstrapPhase.PROJECT_IMPORT ->
            InstalledIndexerBootstrapPhase.PROJECT_IMPORT
        InstalledRuntimeBootstrapPhase.INDEXING -> InstalledIndexerBootstrapPhase.INDEXING
        InstalledRuntimeBootstrapPhase.MODEL_CAPTURE ->
            InstalledIndexerBootstrapPhase.MODEL_CAPTURE
        InstalledRuntimeBootstrapPhase.RUNTIME_ASSEMBLY ->
            InstalledIndexerBootstrapPhase.RUNTIME_ASSEMBLY
    }

private fun InstalledIndexerBootstrapPhase.next(): InstalledIndexerBootstrapPhase? = when (this) {
    InstalledIndexerBootstrapPhase.DISCOVERING_RUNTIME -> InstalledIndexerBootstrapPhase.GRADLE_JVM_SELECTION
    InstalledIndexerBootstrapPhase.GRADLE_JVM_SELECTION -> InstalledIndexerBootstrapPhase.PROJECT_IMPORT
    InstalledIndexerBootstrapPhase.PROJECT_IMPORT -> InstalledIndexerBootstrapPhase.INDEXING
    InstalledIndexerBootstrapPhase.INDEXING -> InstalledIndexerBootstrapPhase.MODEL_CAPTURE
    InstalledIndexerBootstrapPhase.MODEL_CAPTURE -> InstalledIndexerBootstrapPhase.RUNTIME_ASSEMBLY
    InstalledIndexerBootstrapPhase.RUNTIME_ASSEMBLY ->
        InstalledIndexerBootstrapPhase.TRANSPORT_ACTIVATION
    InstalledIndexerBootstrapPhase.TRANSPORT_ACTIVATION -> null
}

/** Exhaustive projection into the runtime-owned cross-process bootstrap document facade. */
internal fun InstalledIndexerBootstrapPhase.runtimePhase(): InstalledRuntimeBootstrapPhase = when (this) {
    InstalledIndexerBootstrapPhase.DISCOVERING_RUNTIME -> InstalledRuntimeBootstrapPhase.DISCOVERING_RUNTIME
    InstalledIndexerBootstrapPhase.GRADLE_JVM_SELECTION -> InstalledRuntimeBootstrapPhase.GRADLE_JVM_SELECTION
    InstalledIndexerBootstrapPhase.PROJECT_IMPORT -> InstalledRuntimeBootstrapPhase.PROJECT_IMPORT
    InstalledIndexerBootstrapPhase.INDEXING -> InstalledRuntimeBootstrapPhase.INDEXING
    InstalledIndexerBootstrapPhase.MODEL_CAPTURE -> InstalledRuntimeBootstrapPhase.MODEL_CAPTURE
    InstalledIndexerBootstrapPhase.RUNTIME_ASSEMBLY -> InstalledRuntimeBootstrapPhase.RUNTIME_ASSEMBLY
    InstalledIndexerBootstrapPhase.TRANSPORT_ACTIVATION -> InstalledRuntimeBootstrapPhase.TRANSPORT_ACTIVATION
}
