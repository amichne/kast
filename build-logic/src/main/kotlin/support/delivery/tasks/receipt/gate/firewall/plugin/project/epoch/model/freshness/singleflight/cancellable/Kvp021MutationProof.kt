package support.delivery

internal sealed interface Kvp021ReportMutationFailure {
    data class MutationAdmitted(
        val index: Int,
        val expected: Kvp021CancellableReadReportFailure,
    ) : Kvp021ReportMutationFailure

    data class WrongFailure(
        val index: Int,
        val expected: Kvp021CancellableReadReportFailure,
        val observed: Kvp021CancellableReadReportFailure,
    ) : Kvp021ReportMutationFailure
}

internal sealed interface Kvp021ReportMutationVerification {
    data object Complete : Kvp021ReportMutationVerification
    data class Rejected(val failure: Kvp021ReportMutationFailure) :
        Kvp021ReportMutationVerification
}

/**
 * Proof transition: canonical KVP-021 report `String -> Kvp021ReportMutationVerification`.
 *
 * Establishes that every fixed corruption maps to its exact finite report failure.
 */
internal fun verifyKvp021ReportMutations(
    canonical: String,
    predecessors: Kvp021ReportPredecessors,
): Kvp021ReportMutationVerification {
    kvp021ReportMutations(canonical).forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp021CancellableReadReport.admit(
            mutation.raw,
            predecessors,
        )) {
            is Kvp021CancellableReadReportAdmission.Admitted ->
                return Kvp021ReportMutationVerification.Rejected(
                    Kvp021ReportMutationFailure.MutationAdmitted(index, mutation.expected),
                )
            is Kvp021CancellableReadReportAdmission.Rejected ->
                if (admission.failure != mutation.expected) {
                    return Kvp021ReportMutationVerification.Rejected(
                        Kvp021ReportMutationFailure.WrongFailure(
                            index,
                            mutation.expected,
                            admission.failure,
                        ),
                    )
                }
        }
    }
    return Kvp021ReportMutationVerification.Complete
}

internal sealed interface Kvp021GateMutationFailure {
    data class MutationAdmitted(
        val index: Int,
        val expected: Kvp021GateExecutionFailure,
    ) : Kvp021GateMutationFailure

    data class WrongFailure(
        val index: Int,
        val expected: Kvp021GateExecutionFailure,
        val observed: Kvp021GateExecutionFailure,
    ) : Kvp021GateMutationFailure
}

internal sealed interface Kvp021GateMutationVerification {
    data object Complete : Kvp021GateMutationVerification
    data class Rejected(val failure: Kvp021GateMutationFailure) :
        Kvp021GateMutationVerification
}

/**
 * Proof transition: canonical KVP-021 gate evidence plus exact expectations ->
 * `Kvp021GateMutationVerification`.
 *
 * Establishes that every fixed gate corruption maps to its exact finite failure.
 */
internal fun verifyKvp021GateMutations(
    canonical: String,
    command: Kvp021GateCommand,
    head: AuthorityGitRevision,
): Kvp021GateMutationVerification {
    kvp021GateMutations(canonical).forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp021GateExecution.admit(
            mutation.raw,
            command,
            head,
            Kvp021GateExecutionPhase.COMPLETE,
        )) {
            is Kvp021GateExecutionAdmission.Admitted ->
                return Kvp021GateMutationVerification.Rejected(
                    Kvp021GateMutationFailure.MutationAdmitted(index, mutation.expected),
                )
            is Kvp021GateExecutionAdmission.Rejected ->
                if (admission.failure != mutation.expected) {
                    return Kvp021GateMutationVerification.Rejected(
                        Kvp021GateMutationFailure.WrongFailure(
                            index,
                            mutation.expected,
                            admission.failure,
                        ),
                    )
                }
        }
    }
    return Kvp021GateMutationVerification.Complete
}

private data class Kvp021ReportMutation(
    val raw: String,
    val expected: Kvp021CancellableReadReportFailure,
)

private data class Kvp021GateMutation(
    val raw: String,
    val expected: Kvp021GateExecutionFailure,
)

private fun reportMutation(raw: String, expected: Kvp021CancellableReadReportFailure) =
    Kvp021ReportMutation(raw, expected)

private fun gateMutation(raw: String, expected: Kvp021GateExecutionFailure) =
    Kvp021GateMutation(raw, expected)

private fun kvp021ReportMutations(canonical: String) = listOf(
    reportMutation(
        canonical.replaceFirst("{", "["),
        Kvp021CancellableReadReportFailure.MALFORMED_DOCUMENT,
    ),
    reportMutation(
        canonical.dropLast(1),
        Kvp021CancellableReadReportFailure.NON_CANONICAL_DOCUMENT,
    ),
    reportMutation(
        canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        Kvp021CancellableReadReportFailure.SCHEMA_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"taskId\": \"KVP-021\"", "\"taskId\": \"KVP-020\""),
        Kvp021CancellableReadReportFailure.IDENTITY_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("ReadAction.computeCancellable", "ReadAction.computeBlocking"),
        Kvp021CancellableReadReportFailure.PLATFORM_PRIMITIVE_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("REJECT_BEFORE_EXECUTION", "ALLOW_NESTED_READ"),
        Kvp021CancellableReadReportFailure.EXISTING_READ_ACCESS_POLICY_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("WRITE_PREEMPTED", "PLATFORM_CANCELLED"),
        Kvp021CancellableReadReportFailure.PLATFORM_CANCELLATION_CAUSE_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "DEFERRED_CANCELLATION_PRESERVED_ACROSS_RETIREMENT",
            "FIRST_RETIREMENT_CAUSE_PRESERVED",
        ),
        Kvp021CancellableReadReportFailure.CANCELLATION_CAUSALITY_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("SAME_PROJECT_EPOCH_SOURCE", "CANONICAL_ROOT_ONLY"),
        Kvp021CancellableReadReportFailure.ADMISSION_EVIDENCE_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"ACTIVE\"", "\"EXECUTING\""),
        Kvp021CancellableReadReportFailure.LIFECYCLE_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"SUCCESS_COMPLETES\"", "\"TIMEOUT_CANCELS_WITHOUT_EMPTY_SUCCESS\""),
        Kvp021CancellableReadReportFailure.EXECUTION_CASE_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"PLATFORM_CANCELLATION_PROMOTION_RETRIEVABLE\"",
            "\"SUCCESS_COMPLETES\"",
        ),
        Kvp021CancellableReadReportFailure.EXECUTION_CASE_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"WRONG_THREAD\"", "\"PROJECT_DISPOSED\""),
        Kvp021CancellableReadReportFailure.FAILURE_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"COMPLETED\"", "\"REJECTED\""),
        Kvp021CancellableReadReportFailure.TERMINAL_OUTCOME_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"RETHROW_AFTER_PERMIT_CANCELLATION\"",
            "\"SWALLOW_PROCESS_CANCELED_EXCEPTION\"",
        ),
        Kvp021CancellableReadReportFailure.CANCELLATION_BEHAVIOR_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"IDE_PROJECT_READ\"", "\"NATIVE_INDEX_READ\""),
        Kvp021CancellableReadReportFailure.EFFECT_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"semanticExecutionLimitPerPermit\": 1",
            "\"semanticExecutionLimitPerPermit\": 2",
        ),
        Kvp021CancellableReadReportFailure.SEMANTIC_EXECUTION_LIMIT_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"permitTerminalizationCountPerExecution\": 1",
            "\"permitTerminalizationCountPerExecution\": 2",
        ),
        Kvp021CancellableReadReportFailure.PERMIT_TERMINALIZATION_COUNT_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"observedCount\": 0", "\"observedCount\": 1"),
        Kvp021CancellableReadReportFailure.FORBIDDEN_WORK_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            Regex("\\\"sha256\\\": \\\"[0-9a-f]{64}\\\""),
            "\"sha256\": \"${"0".repeat(64)}\"",
        ),
        Kvp021CancellableReadReportFailure.PREDECESSOR_SET_MISMATCH,
    ),
)

private fun kvp021GateMutations(canonical: String) = listOf(
    gateMutation(
        canonical.replaceFirst("{", "["),
        Kvp021GateExecutionFailure.MALFORMED_DOCUMENT,
    ),
    gateMutation(
        canonical.dropLast(1),
        Kvp021GateExecutionFailure.NON_CANONICAL_DOCUMENT,
    ),
    gateMutation(
        canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        Kvp021GateExecutionFailure.SCHEMA_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"taskId\": \"KVP-021\"", "\"taskId\": \"KVP-020\""),
        Kvp021GateExecutionFailure.IDENTITY_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"gateId\": \"KVP-021-RED\"", "\"gateId\": \"KVP-021-GREEN\""),
        Kvp021GateExecutionFailure.IDENTITY_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(":runtime:ide-read:test", ":runtime:ide-read:check"),
        Kvp021GateExecutionFailure.COMMAND_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"canonicalTaskPath\": \":runtime:ide-read:test\"",
            "\"canonicalTaskPath\": \":runtime:ide-read:check\"",
        ),
        Kvp021GateExecutionFailure.TASK_PATH_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"dedicatedTaskPath\": \":runtime:ide-read:cancellableReadNegativeGate\"",
            "\"dedicatedTaskPath\": \":runtime:ide-read:cancellableReadGate\"",
        ),
        Kvp021GateExecutionFailure.TASK_PATH_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"selectorPattern\": \"*CancellableReadNegativeTest\"",
            "\"selectorPattern\": \"*CancellableReadTest\"",
        ),
        Kvp021GateExecutionFailure.SELECTOR_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(Regex("[0-9a-f]{40}"), "f".repeat(40)),
        Kvp021GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceLast("0".repeat(40), "f".repeat(40)),
        Kvp021GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"AFTER\"", "\"BEFORE\""),
        Kvp021GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"COMPLETE\"", "\"STARTED\""),
        Kvp021GateExecutionFailure.PHASE_MISMATCH,
    ),
)

private fun String.replaceLast(oldValue: String, newValue: String): String {
    val index = lastIndexOf(oldValue)
    return if (index < 0) this else replaceRange(index, index + oldValue.length, newValue)
}
