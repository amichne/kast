package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

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
        publishAdmission = { admission ->
            Logger.getInstance(HostedReadDiagnostics::class.java)
                .info("kast_read_action " + diagnosticOutcomeJson.encodeToString(admission))
        },
        publishCall = { call ->
            Logger.getInstance(HostedReadDiagnostics::class.java)
                .info("kast_native_call " + diagnosticOutcomeJson.encodeToString(call))
        },
    )

internal fun HostedReadDiagnosticReceipt.encode(): String =
    diagnosticOutcomeJson.encodeToString(
        HostedReadDiagnosticDocument(
            schemaVersion = 10,
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
            nativeCalls = nativeCalls,
            nativeSearches = nativeSearches,
            readActions = readActions,
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
    val nativeCalls: List<HostedReadCallCount>,
    val nativeSearches: List<HostedReadSearchCount>,
    val readActions: List<HostedReadActionCount>,
    val nativeCallVocabulary: List<IntellijReadCall> = IntellijReadCall.entries,
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
