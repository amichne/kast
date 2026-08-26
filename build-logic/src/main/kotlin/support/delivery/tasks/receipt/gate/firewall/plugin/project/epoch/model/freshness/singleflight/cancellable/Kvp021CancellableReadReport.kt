package support.delivery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class Kvp021CancellableReadDocument(
    val schemaVersion: Int,
    val taskId: String,
    val authority: Kvp021ReportAuthority,
    val publicInterface: Kvp021PublicInterface,
    val platformPrimitive: Kvp021PlatformPrimitive,
    val existingReadAccessPolicy: Kvp021ExistingReadAccessPolicy,
    val platformCancellationCauses: List<Kvp021PlatformCancellationCause>,
    val cancellationCausality: List<Kvp021CancellationCausality>,
    val admissionEvidence: Kvp021AdmissionEvidence,
    val lifecycle: List<Kvp021ExecutionState>,
    val executionCases: List<Kvp021ExecutionCase>,
    val failures: List<Kvp021ExecutionFailure>,
    val terminalOutcomes: List<Kvp021TerminalOutcome>,
    val cancellationBehavior: Kvp021CancellationBehavior,
    val effects: List<Kvp021ReportEffect>,
    val semanticExecutionLimitPerPermit: Int,
    val permitTerminalizationCountPerExecution: Int,
    val forbiddenWork: List<Kvp021ForbiddenWorkDocument>,
    val predecessorReceipts: List<Kvp021PredecessorDocument>,
)

@Serializable
private data class Kvp021ForbiddenWorkDocument(
    val kind: Kvp021ForbiddenWork,
    val observedCount: Int,
)

@Serializable private enum class Kvp021ReportAuthority { READ_RUNTIME }
@Serializable private enum class Kvp021PublicInterface { CancellableProjectReadExecutor }
@Serializable private enum class Kvp021PlatformPrimitive(val value: String) {
    @SerialName("ReadAction.computeCancellable") ReadAction_computeCancellable(
        "ReadAction.computeCancellable",
    ),
    @SerialName("ReadAction.computeBlocking") ReadAction_computeBlocking(
        "ReadAction.computeBlocking",
    ),
}

@Serializable private enum class Kvp021CancellationBehavior {
    RETHROW_AFTER_PERMIT_CANCELLATION,
    SWALLOW_PROCESS_CANCELED_EXCEPTION,
}

@Serializable private enum class Kvp021ExistingReadAccessPolicy {
    REJECT_BEFORE_EXECUTION,
    ALLOW_NESTED_READ,
}

@Serializable private enum class Kvp021PlatformCancellationCause {
    WRITE_PREEMPTED,
    PLATFORM_CANCELLED,
}

@Serializable private enum class Kvp021CancellationCausality {
    DEFERRED_CANCELLATION_PRESERVED_ACROSS_RETIREMENT,
    FIRST_RETIREMENT_CAUSE_PRESERVED,
    PROJECT_DISPOSAL_HAS_NO_CONTRADICTORY_CAUSE,
}

@Serializable private enum class Kvp021AdmissionEvidence {
    SAME_PROJECT_EPOCH_SOURCE,
    CANONICAL_ROOT_ONLY,
}

@Serializable private enum class Kvp021ExecutionState { ACTIVE, EXECUTING, TERMINAL }

@Serializable private enum class Kvp021ExecutionCase {
    SUCCESS_COMPLETES,
    WRITE_PRIORITY_PROPAGATES_PLATFORM_CANCELLATION,
    PLATFORM_CANCELLATION_RETHROWN_AFTER_PERMIT_CANCELLATION,
    PLATFORM_CANCELLATION_PROMOTION_RETRIEVABLE,
    DUMB_MODE_REJECTS_BEFORE_SEMANTIC_WORK,
    PROJECT_DISPOSAL_REJECTS_BEFORE_SEMANTIC_WORK,
    TIMEOUT_CANCELS_WITHOUT_EMPTY_SUCCESS,
}

@Serializable private enum class Kvp021ExecutionFailure {
    WRONG_THREAD,
    EXISTING_READ_ACCESS,
    PROJECT_DISPOSED,
    PROJECT_NOT_OPEN,
    DUMB_MODE,
    PERMIT_NOT_OWNED,
    PERMIT_ALREADY_EXECUTING,
    PERMIT_TERMINAL,
}

@Serializable private enum class Kvp021TerminalOutcome {
    COMPLETED,
    REJECTED,
    PLATFORM_CANCELLED,
    PERMIT_INVALIDATED,
}

@Serializable private enum class Kvp021ReportEffect {
    IDE_PROJECT_READ,
    NATIVE_INDEX_READ,
    SEMANTIC_READ,
}

@Serializable private enum class Kvp021ForbiddenWork {
    READ_ACTION_COMPUTE_BLOCKING,
    WAIT_FOR_SMART_MODE,
    THREAD_SLEEP_POLLING,
    SWALLOWING_PROCESS_CANCELED_EXCEPTION,
    EDT_EXECUTION,
}

internal enum class Kvp021CancellableReadReportFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SCHEMA_MISMATCH,
    IDENTITY_MISMATCH,
    PLATFORM_PRIMITIVE_MISMATCH,
    EXISTING_READ_ACCESS_POLICY_MISMATCH,
    PLATFORM_CANCELLATION_CAUSE_SET_MISMATCH,
    CANCELLATION_CAUSALITY_SET_MISMATCH,
    ADMISSION_EVIDENCE_MISMATCH,
    LIFECYCLE_MISMATCH,
    EXECUTION_CASE_SET_MISMATCH,
    FAILURE_SET_MISMATCH,
    TERMINAL_OUTCOME_SET_MISMATCH,
    CANCELLATION_BEHAVIOR_MISMATCH,
    EFFECT_SET_MISMATCH,
    SEMANTIC_EXECUTION_LIMIT_MISMATCH,
    PERMIT_TERMINALIZATION_COUNT_MISMATCH,
    FORBIDDEN_WORK_MISMATCH,
    PREDECESSOR_SET_MISMATCH,
    PREDECESSOR_RECEIPT_REJECTED,
}

internal class AdmittedKvp021CancellableReadReport private constructor(
    val canonicalDocument: String,
    val authority: String,
    val publicInterface: String,
    val platformPrimitive: String,
    val lifecycleStateCount: Int,
    val executionCaseCount: Int,
    val failureCount: Int,
    val terminalOutcomeCount: Int,
    val effectCount: Int,
    val semanticExecutionLimitPerPermit: Int,
    val permitTerminalizationCountPerExecution: Int,
    val observedForbiddenWorkCount: Int,
) {
    companion object {
        /**
         * Proof transition: `(String, Kvp021ReportPredecessors) ->
         * Kvp021CancellableReadReportAdmission`.
         *
         * Establishes canonical KVP-021 product claims, closed lifecycle and causality sets, zero
         * forbidden work, and the ordered KVP-019/KVP-020 completion digests. Expected failures
         * remain closed [Kvp021CancellableReadReportFailure] data. Raw JSON is extracted only at
         * the outer Gradle report and receipt boundaries.
         */
        fun admit(
            raw: String,
            predecessors: Kvp021ReportPredecessors,
        ): Kvp021CancellableReadReportAdmission {
            val document = try {
                KVP021_REPORT_JSON.decodeFromString(
                    Kvp021CancellableReadDocument.serializer(),
                    raw,
                )
            } catch (_: SerializationException) {
                return rejected(Kvp021CancellableReadReportFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return rejected(Kvp021CancellableReadReportFailure.MALFORMED_DOCUMENT)
            }
            when {
                document.schemaVersion != KVP021_REPORT_SCHEMA_VERSION -> return rejected(
                    Kvp021CancellableReadReportFailure.SCHEMA_MISMATCH,
                )
                document.taskId != "KVP-021" ||
                    document.authority != Kvp021ReportAuthority.READ_RUNTIME ||
                    document.publicInterface !=
                    Kvp021PublicInterface.CancellableProjectReadExecutor -> return rejected(
                    Kvp021CancellableReadReportFailure.IDENTITY_MISMATCH,
                )
                document.platformPrimitive !=
                    Kvp021PlatformPrimitive.ReadAction_computeCancellable -> return rejected(
                    Kvp021CancellableReadReportFailure.PLATFORM_PRIMITIVE_MISMATCH,
                )
                document.existingReadAccessPolicy !=
                    Kvp021ExistingReadAccessPolicy.REJECT_BEFORE_EXECUTION -> return rejected(
                    Kvp021CancellableReadReportFailure.EXISTING_READ_ACCESS_POLICY_MISMATCH,
                )
                document.platformCancellationCauses != Kvp021PlatformCancellationCause.entries ->
                    return rejected(
                        Kvp021CancellableReadReportFailure
                            .PLATFORM_CANCELLATION_CAUSE_SET_MISMATCH,
                    )
                document.cancellationCausality != Kvp021CancellationCausality.entries ->
                    return rejected(
                        Kvp021CancellableReadReportFailure.CANCELLATION_CAUSALITY_SET_MISMATCH,
                    )
                document.admissionEvidence != Kvp021AdmissionEvidence.SAME_PROJECT_EPOCH_SOURCE ->
                    return rejected(
                        Kvp021CancellableReadReportFailure.ADMISSION_EVIDENCE_MISMATCH,
                    )
                document.lifecycle != Kvp021ExecutionState.entries -> return rejected(
                    Kvp021CancellableReadReportFailure.LIFECYCLE_MISMATCH,
                )
                document.executionCases != Kvp021ExecutionCase.entries -> return rejected(
                    Kvp021CancellableReadReportFailure.EXECUTION_CASE_SET_MISMATCH,
                )
                document.failures != Kvp021ExecutionFailure.entries -> return rejected(
                    Kvp021CancellableReadReportFailure.FAILURE_SET_MISMATCH,
                )
                document.terminalOutcomes != Kvp021TerminalOutcome.entries -> return rejected(
                    Kvp021CancellableReadReportFailure.TERMINAL_OUTCOME_SET_MISMATCH,
                )
                document.cancellationBehavior !=
                    Kvp021CancellationBehavior.RETHROW_AFTER_PERMIT_CANCELLATION -> return rejected(
                    Kvp021CancellableReadReportFailure.CANCELLATION_BEHAVIOR_MISMATCH,
                )
                document.effects != Kvp021ReportEffect.entries -> return rejected(
                    Kvp021CancellableReadReportFailure.EFFECT_SET_MISMATCH,
                )
                document.semanticExecutionLimitPerPermit != 1 -> return rejected(
                    Kvp021CancellableReadReportFailure.SEMANTIC_EXECUTION_LIMIT_MISMATCH,
                )
                document.permitTerminalizationCountPerExecution != 1 -> return rejected(
                    Kvp021CancellableReadReportFailure.PERMIT_TERMINALIZATION_COUNT_MISMATCH,
                )
                document.forbiddenWork != canonicalKvp021ForbiddenWork() -> return rejected(
                    Kvp021CancellableReadReportFailure.FORBIDDEN_WORK_MISMATCH,
                )
                document.predecessorReceipts != predecessors.documents() ->
                    return rejected(Kvp021CancellableReadReportFailure.PREDECESSOR_SET_MISMATCH)
            }
            val canonical = encodeKvp021Report(document)
            if (raw != canonical) return rejected(
                Kvp021CancellableReadReportFailure.NON_CANONICAL_DOCUMENT,
            )
            return Kvp021CancellableReadReportAdmission.Admitted(
                AdmittedKvp021CancellableReadReport(
                    canonical,
                    document.authority.name,
                    document.publicInterface.name,
                    document.platformPrimitive.value,
                    document.lifecycle.size,
                    document.executionCases.size,
                    document.failures.size,
                    document.terminalOutcomes.size,
                    document.effects.size,
                    document.semanticExecutionLimitPerPermit,
                    document.permitTerminalizationCountPerExecution,
                    document.forbiddenWork.sumOf(Kvp021ForbiddenWorkDocument::observedCount),
                ),
            )
        }
    }
}

internal sealed interface Kvp021CancellableReadReportAdmission {
    data class Admitted(val report: AdmittedKvp021CancellableReadReport) :
        Kvp021CancellableReadReportAdmission

    data class Rejected(val failure: Kvp021CancellableReadReportFailure) :
        Kvp021CancellableReadReportAdmission
}

internal fun canonicalKvp021CancellableReadReport(
    predecessors: Kvp021ReportPredecessors,
): String = encodeKvp021Report(
    Kvp021CancellableReadDocument(
        schemaVersion = KVP021_REPORT_SCHEMA_VERSION,
        taskId = "KVP-021",
        authority = Kvp021ReportAuthority.READ_RUNTIME,
        publicInterface = Kvp021PublicInterface.CancellableProjectReadExecutor,
        platformPrimitive = Kvp021PlatformPrimitive.ReadAction_computeCancellable,
        existingReadAccessPolicy = Kvp021ExistingReadAccessPolicy.REJECT_BEFORE_EXECUTION,
        platformCancellationCauses = Kvp021PlatformCancellationCause.entries,
        cancellationCausality = Kvp021CancellationCausality.entries,
        admissionEvidence = Kvp021AdmissionEvidence.SAME_PROJECT_EPOCH_SOURCE,
        lifecycle = Kvp021ExecutionState.entries,
        executionCases = Kvp021ExecutionCase.entries,
        failures = Kvp021ExecutionFailure.entries,
        terminalOutcomes = Kvp021TerminalOutcome.entries,
        cancellationBehavior = Kvp021CancellationBehavior.RETHROW_AFTER_PERMIT_CANCELLATION,
        effects = Kvp021ReportEffect.entries,
        semanticExecutionLimitPerPermit = 1,
        permitTerminalizationCountPerExecution = 1,
        forbiddenWork = canonicalKvp021ForbiddenWork(),
        predecessorReceipts = predecessors.documents(),
    ),
)

private fun canonicalKvp021ForbiddenWork() = Kvp021ForbiddenWork.entries.map {
    Kvp021ForbiddenWorkDocument(it, 0)
}

private fun encodeKvp021Report(document: Kvp021CancellableReadDocument) =
    KVP021_REPORT_JSON.encodeToString(Kvp021CancellableReadDocument.serializer(), document) + "\n"

private fun rejected(failure: Kvp021CancellableReadReportFailure) =
    Kvp021CancellableReadReportAdmission.Rejected(failure)

private const val KVP021_REPORT_SCHEMA_VERSION = 1
private val KVP021_REPORT_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
