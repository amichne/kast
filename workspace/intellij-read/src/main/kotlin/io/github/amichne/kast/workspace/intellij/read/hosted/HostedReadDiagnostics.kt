package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.workspace.intellij.read.*
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Counts are saturated rather than allocating one event per declaration. */
internal class HostedReadDiagnostics(
    private val clock: () -> Long,
    private val limits: ReadLimits = ReadLimits.Default,
    private val publishPhase: (HostedNativePhaseEntry) -> Unit = {},
    private val publishCall: (HostedReadCallStarted) -> Unit = {},
    private val publish: (HostedReadDiagnosticReceipt) -> Unit,
) : IntellijReadObservation {
    private val started = clock()
    private val readId = UUID.randomUUID()
    private val phaseDurations = linkedMapOf<IntellijReadPhase, Long>()
    private var nativePhase: HostedNativePhaseState = HostedNativePhaseState.NotEntered
    private var phaseStarted = 0L
    private val stages = linkedMapOf<HostedQueryStage, Long>()
    private val counters = initialHostedReadCounters()
    private val calls =
        HostedReadCallAccounting(::elapsed, limits[ReadLimitParameter.DIAGNOSTIC_COUNT].value.toLong()) {
            call,
            parent,
            entered ->
            publishCall(HostedReadCallStarted(readId.toString(), call, parent, entered))
        }

    private val searches =
        HostedReadSearchAccounting(::elapsed, limits[ReadLimitParameter.DIAGNOSTIC_COUNT].value.toLong(), calls::enter)

    override fun enterSearch(search: IntellijReadSearch): IntellijReadSearchScope = searches.enter(search)

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope = calls.enter(call)

    private val gauges = linkedMapOf<IntellijReadGauge, IntellijReadGaugeValue>()
    private val terminations = linkedSetOf<Pair<IntellijReadTermination, IntellijReadContributor>>()
    private val unexpectedFailures = linkedSetOf<IntellijReadUnexpectedFailure>()
    private var semanticBudget: HostedSemanticBudgetObservation = HostedSemanticBudgetObservation.NotAdmitted
    private var finished = false
    private var correlation: HostedReadCorrelation = HostedReadCorrelation.Unbound

    @Synchronized
    fun bindHost(host: io.github.amichne.kast.workspace.contract.IdeReadHostLifetime) {
        correlation = HostedReadCorrelation.HostObserved(host)
    }

    @Synchronized
    fun bind(reference: io.github.amichne.kast.workspace.contract.LiveSemanticReadReference) {
        correlation = HostedReadCorrelation.Bound(reference.host, reference.epoch)
    }

    @Synchronized
    fun stage(stage: HostedQueryStage) {
        if (!finished) stages.putIfAbsent(stage, elapsed())
    }

    @Synchronized
    override fun phase(value: IntellijReadPhase) {
        if (finished) return
        val elapsed = elapsed()
        val previous = nativePhase as? HostedNativePhaseState.Entered
        previous?.let {
            phaseDurations[it.phase] = (phaseDurations[it.phase] ?: 0L) + (elapsed - phaseStarted).coerceAtLeast(0L)
        }
        nativePhase = HostedNativePhaseState.Entered(value)
        phaseStarted = elapsed
        // First entry is a bounded durable signal even if an arbitrary native call never drains.
        if (value !in phaseDurations) {
            phaseDurations[value] = 0L
            publishPhase(HostedNativePhaseEntry(readId.toString(), value, elapsed))
        }
    }

    @Synchronized
    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        require(amount >= 0)
        if (finished) return
        val key = counter to contributor
        counters[key] =
            ((counters[key] ?: 0L) + amount).coerceAtMost(limits[ReadLimitParameter.DIAGNOSTIC_COUNT].value.toLong())
    }

    @Synchronized
    override fun measure(gauge: IntellijReadGauge, value: IntellijReadGaugeValue) {
        if (!finished) gauges[gauge] = gauge.merge(gauges[gauge], value)
    }

    @Synchronized
    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        if (!finished) terminations += reason to contributor
    }

    @Synchronized
    override fun unexpected(failure: IntellijReadUnexpectedFailure) {
        if (!finished && unexpectedFailures.size < limits[ReadLimitParameter.DIAGNOSTIC_FAILURES].value)
            unexpectedFailures += failure
    }

    @Synchronized
    fun budget(value: HostedSemanticBudgetObservation) {
        if (!finished) semanticBudget = value
    }

    @Synchronized
    fun finish(outcome: HostedDiagnosticOutcome) {
        if (finished) return
        val duration = elapsed()
        (nativePhase as? HostedNativePhaseState.Entered)?.let {
            phaseDurations[it.phase] = (phaseDurations[it.phase] ?: 0L) + (duration - phaseStarted).coerceAtLeast(0L)
        }
        finished = true
        val ordered = stages.toList()
        publish(
            HostedReadDiagnosticReceipt(
                readId,
                correlation,
                duration,
                ordered.mapIndexed { index, (stage, start) ->
                    HostedReadStageDuration(stage, start, (ordered.getOrNull(index + 1)?.second ?: duration) - start)
                },
                when (val entry = stages[HostedQueryStage.SEMANTIC_READ]) {
                    null -> HostedSemanticEntry.NotEntered
                    else ->
                        HostedSemanticEntry.Entered(
                            (limits[ReadLimitParameter.HOST_QUERY_MILLIS].value.toLong() * 1_000_000 - entry)
                                .coerceAtLeast(0)
                        )
                },
                semanticBudget,
                counters.map { (key, value) -> HostedNativeCount(key.first, key.second, value) },
                terminations.map { HostedNativeTermination(it.first, it.second) },
                outcome,
                unexpectedFailures.toList(),
                limits,
                nativePhase,
                phaseDurations.map { HostedNativePhaseDuration(it.key, it.value) },
                gauges.map { HostedNativeGauge(it.key, it.value) },
                calls.finish(),
                searches.finish(),
            )
        )
    }

    private fun elapsed(): Long = (clock() - started).coerceAtLeast(0)

    internal companion object {
        const val MAX_COUNT = 1_000_000_000L
    }
}

@Serializable
internal data class HostedNativePhaseEntry(val readId: String, val phase: IntellijReadPhase, val enteredNanos: Long)

@Serializable internal data class HostedNativePhaseDuration(val phase: IntellijReadPhase, val durationNanos: Long)

@Serializable
internal sealed interface HostedNativePhaseState {
    @Serializable @SerialName("not-entered") data object NotEntered : HostedNativePhaseState

    @Serializable @SerialName("entered") data class Entered(val phase: IntellijReadPhase) : HostedNativePhaseState
}

@Serializable
internal data class HostedReadStageDuration(
    val stage: HostedQueryStage,
    val startedNanos: Long,
    val durationNanos: Long,
)

@Serializable
internal data class HostedNativeCount(
    val counter: IntellijReadCounter,
    val contributor: IntellijReadContributor,
    val count: Long,
)

@Serializable internal data class HostedNativeGauge(val gauge: IntellijReadGauge, val value: IntellijReadGaugeValue)

@Serializable
internal data class HostedNativeTermination(
    val reason: IntellijReadTermination,
    val contributor: IntellijReadContributor,
)

internal sealed interface HostedSemanticEntry {
    data object NotEntered : HostedSemanticEntry

    data class Entered(val remainingDeadlineNanos: Long) : HostedSemanticEntry
}

internal sealed interface HostedDiagnosticOutcome {
    data class Evaluated(val outcome: HostedEvaluationOutcome) : HostedDiagnosticOutcome

    data object Completed : HostedDiagnosticOutcome

    data class Rejected(val failure: HostedQueryFailure) : HostedDiagnosticOutcome
}

internal sealed interface HostedReadCorrelation {
    data object Unbound : HostedReadCorrelation

    data class HostObserved(val host: io.github.amichne.kast.workspace.contract.IdeReadHostLifetime) :
        HostedReadCorrelation

    data class Bound(
        val host: io.github.amichne.kast.workspace.contract.IdeReadHostLifetime,
        val epoch: io.github.amichne.kast.workspace.contract.IdeReadEpochRevision,
    ) : HostedReadCorrelation
}

internal data class HostedReadDiagnosticReceipt(
    val readId: UUID,
    val correlation: HostedReadCorrelation,
    val durationNanos: Long,
    val stages: List<HostedReadStageDuration>,
    val semanticEntry: HostedSemanticEntry,
    val semanticBudget: HostedSemanticBudgetObservation,
    val counters: List<HostedNativeCount>,
    val terminations: List<HostedNativeTermination>,
    val outcome: HostedDiagnosticOutcome,
    val unexpectedFailures: List<IntellijReadUnexpectedFailure>,
    val limits: ReadLimits,
    val nativePhase: HostedNativePhaseState = HostedNativePhaseState.NotEntered,
    val nativePhaseDurations: List<HostedNativePhaseDuration> = emptyList(),
    val gauges: List<HostedNativeGauge> = emptyList(),
    val nativeCalls: List<HostedReadCallCount> = emptyList(),
    val nativeSearches: List<HostedReadSearchCount> = emptyList(),
)

/** A completed read transaction and its evaluator's semantic classification are distinct facts. */
enum class HostedEvaluationOutcome {
    EVALUATED,
    COMPLETE,
    QUALIFIED,
    REJECTED,
}

/** Bounded configured-versus-admitted timing evidence; never includes request content. */
@Serializable
internal sealed interface HostedSemanticBudgetObservation {
    @Serializable @SerialName("not-admitted") data object NotAdmitted : HostedSemanticBudgetObservation

    @Serializable
    @SerialName("admitted")
    data class Admitted(
        val remainingHostMillis: Long,
        val completionReserveMillis: Long,
        val semanticMillis: Long,
        val diagnosticScopeMillis: Long,
    ) : HostedSemanticBudgetObservation

    @Serializable
    @SerialName("publication-rejected")
    data class PublicationRejected(val candidate: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport) :
        HostedSemanticBudgetObservation

    @Serializable
    @SerialName("exhausted")
    data class Exhausted(
        val remainingHostMillis: Long,
        val completionReserveMillis: Long,
    ) : HostedSemanticBudgetObservation
}
