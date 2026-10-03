package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.workspace.intellij.read.*
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

/** Counts are saturated rather than allocating one event per declaration. */
internal class HostedReadDiagnostics(
    private val clock: () -> Long,
    private val limits: ReadLimits = ReadLimits.Default,
    private val publishPhase: (HostedNativePhaseEntry) -> Unit = {},
    private val publish: (HostedReadDiagnosticReceipt) -> Unit,
) : IntellijReadObservation {
    private val started = clock()
    private val readId = UUID.randomUUID()
    private val phaseDurations = linkedMapOf<IntellijReadPhase, Long>()
    private var nativePhase: HostedNativePhaseState = HostedNativePhaseState.NotEntered
    private var phaseStarted = 0L
    private val stages = linkedMapOf<HostedQueryStage, Long>()
    private val counters = linkedMapOf<Pair<IntellijReadCounter, IntellijReadContributor>, Long>()

    init {
        // Explicit zeros prove page observation capability even when a workload never enters that provider.
        counters[IntellijReadCounter.NATIVE_DISCOVERY_PAGES to IntellijReadContributor.NONE] = 0L
        counters[IntellijReadCounter.NATIVE_RELATION_PAGES to IntellijReadContributor.NONE] = 0L
        // Retained value evidence reads must prove that no compiler provider was replayed.
        counters[IntellijReadCounter.VALUE_PRODUCER_SEED_READS to IntellijReadContributor.NONE] = 0L
        counters[IntellijReadCounter.VALUE_MODEL_REVALIDATIONS to IntellijReadContributor.NONE] = 0L
        counters[IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS to IntellijReadContributor.NONE] = 0L
        counters[IntellijReadCounter.VALUE_FLOW_READS to IntellijReadContributor.NONE] = 0L
        // Both alternatives must remain observable when comparing locator retention across reads.
        counters[IntellijReadCounter.REVALIDATION_LOCATORS_RETAINED to IntellijReadContributor.NONE] = 0L
        counters[IntellijReadCounter.REVALIDATION_LOCATORS_REJECTED to IntellijReadContributor.NONE] = 0L
        counters[IntellijReadCounter.EPOCH_READ_PREEMPTIONS to IntellijReadContributor.NONE] = 0L
    }

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
)

/** A completed read transaction and its evaluator's semantic classification are distinct facts. */
enum class HostedEvaluationOutcome {
    EVALUATED,
    COMPLETE,
    QUALIFIED,
    REJECTED,
}

/** Default evidence at the hosted native boundary; one bounded record after drainage. */
internal fun hostedReadDiagnostics(limits: ReadLimits = ReadLimits.Default): HostedReadDiagnostics =
    HostedReadDiagnostics(
        System::nanoTime,
        limits,
        publish = { receipt ->
            Logger.getInstance(HostedReadDiagnostics::class.java).info("kast_semantic_read " + receipt.encode())
        },
        publishPhase = { phase ->
            Logger.getInstance(HostedReadDiagnostics::class.java)
                .info("kast_semantic_phase " + diagnosticOutcomeJson.encodeToString(phase))
        },
    )

internal fun HostedReadDiagnosticReceipt.encode(): String =
    diagnosticOutcomeJson.encodeToString(
        HostedReadDiagnosticDocument(
            schemaVersion = 6,
            limits =
                limits.values.map {
                    HostedLimitDocument(it.parameter.name, it.value, it.parameter.unit.name, it.source.name)
                },
            pid = ProcessHandle.current().pid(),
            readId = readId.toString(),
            correlation =
                when (val value = correlation) {
                    HostedReadCorrelation.Unbound -> HostedCorrelationDocument.Unbound
                    is HostedReadCorrelation.HostObserved ->
                        HostedCorrelationDocument.HostObserved(value.host.value.toString())
                    is HostedReadCorrelation.Bound ->
                        HostedCorrelationDocument.Bound(value.host.value.toString(), value.epoch.value)
                },
            durationNanos = durationNanos,
            stages = stages,
            semanticEntry =
                when (val value = semanticEntry) {
                    HostedSemanticEntry.NotEntered -> HostedEntryDocument.NotEntered
                    is HostedSemanticEntry.Entered -> HostedEntryDocument.Entered(value.remainingDeadlineNanos)
                },
            semanticBudget = semanticBudget,
            nativePhase = nativePhase,
            nativePhaseDurations = nativePhaseDurations,
            counters = counters,
            gauges = gauges,
            terminations = terminations,
            outcome =
                when (val value = outcome) {
                    HostedDiagnosticOutcome.Completed -> HostedDiagnosticOutcomeDocument.Completed
                    is HostedDiagnosticOutcome.Evaluated -> HostedDiagnosticOutcomeDocument.Evaluated(value.outcome)
                    is HostedDiagnosticOutcome.Rejected ->
                        HostedDiagnosticOutcomeDocument.Rejected(value.failure.code(), value.failure.detail())
                },
            unexpectedFailures =
                unexpectedFailures.map {
                    HostedUnexpectedFailureDocument(it.stage, it.kind, it.exceptionType, it.adapterFrames)
                },
        )
    )

private val diagnosticOutcomeJson =
    kotlinx.serialization.json.Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

@Serializable
private data class HostedReadDiagnosticDocument(
    val schemaVersion: Int,
    val limits: List<HostedLimitDocument>,
    val pid: Long,
    val readId: String,
    val correlation: HostedCorrelationDocument,
    val durationNanos: Long,
    val stages: List<HostedReadStageDuration>,
    val nativePhase: HostedNativePhaseState,
    val nativePhaseDurations: List<HostedNativePhaseDuration>,
    val semanticEntry: HostedEntryDocument,
    val semanticBudget: HostedSemanticBudgetObservation,
    val counters: List<HostedNativeCount>,
    val gauges: List<HostedNativeGauge>,
    val terminations: List<HostedNativeTermination>,
    val outcome: HostedDiagnosticOutcomeDocument,
    val unexpectedFailures: List<HostedUnexpectedFailureDocument>,
)

@Serializable
private data class HostedLimitDocument(val parameter: String, val value: Int, val unit: String, val source: String)

@Serializable
private data class HostedUnexpectedFailureDocument(
    val stage: IntellijReadStage,
    val kind: IntellijReadUnexpectedKind,
    val exceptionType: String,
    val adapterFrames: List<String>,
)

@Serializable
private sealed interface HostedCorrelationDocument {
    @Serializable @SerialName("unbound") data object Unbound : HostedCorrelationDocument

    @Serializable @SerialName("host-observed") data class HostObserved(val host: String) : HostedCorrelationDocument

    @Serializable @SerialName("bound") data class Bound(val host: String, val epoch: Long) : HostedCorrelationDocument
}

@Serializable
private sealed interface HostedEntryDocument {
    @Serializable @SerialName("not-entered") data object NotEntered : HostedEntryDocument

    @Serializable @SerialName("entered") data class Entered(val remainingDeadlineNanos: Long) : HostedEntryDocument
}

@Serializable
internal sealed interface HostedDiagnosticOutcomeDocument {
    @Serializable @SerialName("completed") data object Completed : HostedDiagnosticOutcomeDocument

    @Serializable
    @SerialName("evaluated")
    data class Evaluated(val outcome: HostedEvaluationOutcome) : HostedDiagnosticOutcomeDocument

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val failure: String,
        /** Hosted failure detail is the existing schema-defined union of finite codes and structured causes. */
        val detail: JsonElement,
    ) : HostedDiagnosticOutcomeDocument
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
