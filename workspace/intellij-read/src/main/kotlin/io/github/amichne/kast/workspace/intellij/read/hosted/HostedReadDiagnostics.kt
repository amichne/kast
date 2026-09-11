package io.github.amichne.kast.workspace.intellij.read.hosted

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.workspace.intellij.read.*
import java.util.UUID

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

internal data class HostedReadStageDuration(
    val stage: HostedQueryStage,
    val startedNanos: Long,
    val durationNanos: Long,
)

internal data class HostedNativeCount(
    val counter: IntellijReadCounter,
    val contributor: IntellijReadContributor,
    val count: Long,
)

internal data class HostedNativeTermination(
    val reason: IntellijReadTermination,
    val contributor: IntellijReadContributor,
)

internal sealed interface HostedSemanticEntry {
    data object NotEntered : HostedSemanticEntry

    data class Entered(val remainingDeadlineNanos: Long) : HostedSemanticEntry
}

internal sealed interface HostedDiagnosticOutcome {
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

/** Default evidence at the hosted native boundary; one bounded record after drainage. */
internal fun hostedReadDiagnostics(limits: ReadLimits = ReadLimits.Default): HostedReadDiagnostics =
    HostedReadDiagnostics(System::nanoTime, limits) { receipt ->
        val outcome =
            when (val result = receipt.outcome) {
                HostedDiagnosticOutcome.Completed -> mapOf("type" to "completed")
                is HostedDiagnosticOutcome.Rejected ->
                    mapOf("type" to "rejected", "failure" to result.failure.code(), "detail" to result.failure.detail())
            }
        Logger.getInstance(HostedReadDiagnostics::class.java)
            .info(
                "kast_semantic_read " +
                    Gson()
                        .toJson(
                            mapOf(
                                "schemaVersion" to 2,
                                "limits" to
                                    receipt.limits.values.map {
                                        mapOf(
                                            "parameter" to it.parameter.name,
                                            "value" to it.value,
                                            "unit" to it.parameter.unit.name,
                                            "source" to it.source.name,
                                        )
                                    },
                                "pid" to ProcessHandle.current().pid(),
                                "readId" to receipt.readId,
                                "correlation" to
                                    when (val value = receipt.correlation) {
                                        HostedReadCorrelation.Unbound -> mapOf("type" to "unbound")
                                        is HostedReadCorrelation.HostObserved ->
                                            mapOf("type" to "host-observed", "host" to value.host.value.toString())
                                        is HostedReadCorrelation.Bound ->
                                            mapOf(
                                                "type" to "bound",
                                                "host" to value.host.value.toString(),
                                                "epoch" to value.epoch.value,
                                            )
                                    },
                                "durationNanos" to receipt.durationNanos,
                                "stages" to receipt.stages,
                                "semanticEntry" to
                                    when (val entry = receipt.semanticEntry) {
                                        HostedSemanticEntry.NotEntered -> mapOf("type" to "not-entered")
                                        is HostedSemanticEntry.Entered ->
                                            mapOf(
                                                "type" to "entered",
                                                "remainingDeadlineNanos" to entry.remainingDeadlineNanos,
                                            )
                                    },
                                "counters" to receipt.counters,
                                "terminations" to receipt.terminations,
                                "outcome" to outcome,
                                "unexpectedFailures" to receipt.unexpectedFailures,
                            )
                        )
            )
    }
