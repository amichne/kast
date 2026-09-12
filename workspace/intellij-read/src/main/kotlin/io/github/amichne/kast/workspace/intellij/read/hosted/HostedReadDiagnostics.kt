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
    private val publish: (HostedReadDiagnosticReceipt) -> Unit,
) : IntellijReadObservation {
    private val started = clock()
    private val stages = linkedMapOf<HostedQueryStage, Long>()
    private val counters = linkedMapOf<Pair<IntellijReadCounter, IntellijReadContributor>, Long>()
    private val terminations = linkedSetOf<Pair<IntellijReadTermination, IntellijReadContributor>>()
    private val unexpectedFailures = linkedSetOf<IntellijReadUnexpectedFailure>()
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
    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        require(amount >= 0)
        if (finished) return
        val key = counter to contributor
        counters[key] =
            ((counters[key] ?: 0L) + amount).coerceAtMost(limits[ReadLimitParameter.DIAGNOSTIC_COUNT].value.toLong())
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
    fun finish(outcome: HostedDiagnosticOutcome) {
        if (finished) return
        finished = true
        val duration = elapsed()
        val ordered = stages.toList()
        publish(
            HostedReadDiagnosticReceipt(
                UUID.randomUUID(),
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
                counters.map { (key, value) -> HostedNativeCount(key.first, key.second, value) },
                terminations.map { HostedNativeTermination(it.first, it.second) },
                outcome,
                unexpectedFailures.toList(),
                limits,
            )
        )
    }

    private fun elapsed(): Long = (clock() - started).coerceAtLeast(0)

    internal companion object {
        const val MAX_COUNT = 1_000_000_000L
    }
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
    val counters: List<HostedNativeCount>,
    val terminations: List<HostedNativeTermination>,
    val outcome: HostedDiagnosticOutcome,
    val unexpectedFailures: List<IntellijReadUnexpectedFailure>,
    val limits: ReadLimits,
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
    HostedReadDiagnostics(System::nanoTime, limits) { receipt ->
        Logger.getInstance(HostedReadDiagnostics::class.java).info("kast_semantic_read " + receipt.encode())
    }

internal fun HostedReadDiagnosticReceipt.encode(): String =
    diagnosticOutcomeJson.encodeToString(
        HostedReadDiagnosticDocument(
            schemaVersion = 3,
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
            counters = counters,
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
    val semanticEntry: HostedEntryDocument,
    val counters: List<HostedNativeCount>,
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
