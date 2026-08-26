package support.delivery

internal sealed interface Kvp022ReportMutationFailure {
    data class MutationAdmitted(
        val index: Int,
        val expected: Kvp022EpochRevalidationReportFailure,
    ) : Kvp022ReportMutationFailure

    data class WrongFailure(
        val index: Int,
        val expected: Kvp022EpochRevalidationReportFailure,
        val observed: Kvp022EpochRevalidationReportFailure,
    ) : Kvp022ReportMutationFailure
}

internal sealed interface Kvp022ReportMutationVerification {
    data object Complete : Kvp022ReportMutationVerification
    data class Rejected(val failure: Kvp022ReportMutationFailure) :
        Kvp022ReportMutationVerification
}

/**
 * Proof transition: canonical KVP-022 report `String -> Kvp022ReportMutationVerification`.
 *
 * Establishes that every fixed corruption maps to its exact finite report failure.
 */
internal fun verifyKvp022ReportMutations(
    canonical: String,
    predecessor: Kvp022ReportPredecessor,
): Kvp022ReportMutationVerification {
    kvp022ReportMutations(canonical).forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp022EpochRevalidationReport.admit(
            mutation.raw,
            predecessor,
        )) {
            is Kvp022EpochRevalidationReportAdmission.Admitted ->
                return Kvp022ReportMutationVerification.Rejected(
                    Kvp022ReportMutationFailure.MutationAdmitted(index, mutation.expected),
                )
            is Kvp022EpochRevalidationReportAdmission.Rejected ->
                if (admission.failure != mutation.expected) {
                    return Kvp022ReportMutationVerification.Rejected(
                        Kvp022ReportMutationFailure.WrongFailure(
                            index,
                            mutation.expected,
                            admission.failure,
                        ),
                    )
                }
        }
    }
    return Kvp022ReportMutationVerification.Complete
}

internal sealed interface Kvp022PredecessorMutationVerification {
    data object Complete : Kvp022PredecessorMutationVerification
    data object MutationAdmitted : Kvp022PredecessorMutationVerification
    data class WrongFailure(val observed: Kvp022EpochRevalidationReportFailure) :
        Kvp022PredecessorMutationVerification
}

/** Proves that a forged KVP-021 receipt digest cannot become KVP-022 predecessor evidence. */
internal fun verifyKvp022PredecessorMutation(
    canonicalReceipt: String,
): Kvp022PredecessorMutationVerification {
    val mutation = canonicalReceipt.replaceFirst(
        Regex("\"receiptDigest\"\\s*:\\s*\"[0-9a-f]{64}\""),
        "\"receiptDigest\": \"${"0".repeat(64)}\"",
    )
    return when (val result = Kvp022ReportPredecessor.decode(
        Kvp022PredecessorReceiptId.KVP_021_COMPLETE,
        mutation,
    )) {
        is Kvp022ReportPredecessorRefinement.Admitted ->
            Kvp022PredecessorMutationVerification.MutationAdmitted
        is Kvp022ReportPredecessorRefinement.Rejected -> if (
            result.failure == Kvp022EpochRevalidationReportFailure.PREDECESSOR_RECEIPT_REJECTED
        ) Kvp022PredecessorMutationVerification.Complete
        else Kvp022PredecessorMutationVerification.WrongFailure(result.failure)
    }
}

internal sealed interface Kvp022GateMutationFailure {
    data class MutationAdmitted(
        val index: Int,
        val expected: Kvp022GateExecutionFailure,
    ) : Kvp022GateMutationFailure

    data class WrongFailure(
        val index: Int,
        val expected: Kvp022GateExecutionFailure,
        val observed: Kvp022GateExecutionFailure,
    ) : Kvp022GateMutationFailure
}

internal sealed interface Kvp022GateMutationVerification {
    data object Complete : Kvp022GateMutationVerification
    data class Rejected(val failure: Kvp022GateMutationFailure) :
        Kvp022GateMutationVerification
}

/**
 * Proof transition: canonical KVP-022 gate evidence plus exact expectations ->
 * `Kvp022GateMutationVerification`.
 *
 * Establishes that every fixed gate corruption maps to its exact finite failure.
 */
internal fun verifyKvp022GateMutations(
    canonical: String,
    command: Kvp022GateCommand,
    head: AuthorityGitRevision,
): Kvp022GateMutationVerification {
    kvp022GateMutations(canonical).forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp022GateExecution.admit(
            mutation.raw,
            command,
            head,
            Kvp022GateExecutionPhase.COMPLETE,
        )) {
            is Kvp022GateExecutionAdmission.Admitted ->
                return Kvp022GateMutationVerification.Rejected(
                    Kvp022GateMutationFailure.MutationAdmitted(index, mutation.expected),
                )
            is Kvp022GateExecutionAdmission.Rejected ->
                if (admission.failure != mutation.expected) {
                    return Kvp022GateMutationVerification.Rejected(
                        Kvp022GateMutationFailure.WrongFailure(
                            index,
                            mutation.expected,
                            admission.failure,
                        ),
                    )
                }
        }
    }
    return Kvp022GateMutationVerification.Complete
}

private data class Kvp022ReportMutation(
    val raw: String,
    val expected: Kvp022EpochRevalidationReportFailure,
)

private data class Kvp022GateMutation(
    val raw: String,
    val expected: Kvp022GateExecutionFailure,
)

private fun reportMutation(raw: String, expected: Kvp022EpochRevalidationReportFailure) =
    Kvp022ReportMutation(raw, expected)

private fun gateMutation(raw: String, expected: Kvp022GateExecutionFailure) =
    Kvp022GateMutation(raw, expected)

private fun kvp022ReportMutations(canonical: String) = listOf(
    reportMutation(
        canonical.replaceFirst("{", "["),
        Kvp022EpochRevalidationReportFailure.MALFORMED_DOCUMENT,
    ),
    reportMutation(
        canonical.dropLast(1),
        Kvp022EpochRevalidationReportFailure.NON_CANONICAL_DOCUMENT,
    ),
    reportMutation(
        canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        Kvp022EpochRevalidationReportFailure.SCHEMA_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"taskId\": \"KVP-022\"", "\"taskId\": \"KVP-021\""),
        Kvp022EpochRevalidationReportFailure.IDENTITY_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"READ_EPOCH\"", "\"READ_RUNTIME\""),
        Kvp022EpochRevalidationReportFailure.AUTHORITY_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"BEFORE\"", "\"AFTER\""),
        Kvp022EpochRevalidationReportFailure.EPOCH_OBSERVATION_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"decision\": \"COMPLETE\"", "\"decision\": \"WORKSPACE_MOVED\""),
        Kvp022EpochRevalidationReportFailure.RELATION_DECISION_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"BEFORE_EPOCH_OBSERVATION_REJECTED\"",
            "\"AFTER_EPOCH_OBSERVATION_REJECTED\"",
        ),
        Kvp022EpochRevalidationReportFailure.PHASE_FAILURE_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"epochObservationCountPerCompletedRead\": 2",
            "\"epochObservationCountPerCompletedRead\": 1",
        ),
        Kvp022EpochRevalidationReportFailure.EPOCH_OBSERVATION_COUNT_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"semanticExecutionLimitPerAttempt\": 1",
            "\"semanticExecutionLimitPerAttempt\": 2",
        ),
        Kvp022EpochRevalidationReportFailure.SEMANTIC_EXECUTION_LIMIT_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"retryCount\": 0", "\"retryCount\": 1"),
        Kvp022EpochRevalidationReportFailure.RETRY_COUNT_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"priorEpochReuseCount\": 0", "\"priorEpochReuseCount\": 1"),
        Kvp022EpochRevalidationReportFailure.PRIOR_EPOCH_REUSE_COUNT_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"observedCount\": 0", "\"observedCount\": 1"),
        Kvp022EpochRevalidationReportFailure.FORBIDDEN_WORK_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            Regex("\"sha256\": \"[0-9a-f]{64}\""),
            "\"sha256\": \"${"0".repeat(64)}\"",
        ),
        Kvp022EpochRevalidationReportFailure.PREDECESSOR_MISMATCH,
    ),
)

private fun kvp022GateMutations(canonical: String) = listOf(
    gateMutation(canonical.replaceFirst("{", "["), Kvp022GateExecutionFailure.MALFORMED_DOCUMENT),
    gateMutation(canonical.dropLast(1), Kvp022GateExecutionFailure.NON_CANONICAL_DOCUMENT),
    gateMutation(
        canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        Kvp022GateExecutionFailure.SCHEMA_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"taskId\": \"KVP-022\"", "\"taskId\": \"KVP-021\""),
        Kvp022GateExecutionFailure.IDENTITY_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"gateId\": \"KVP-022-RED\"", "\"gateId\": \"KVP-022-GREEN\""),
        Kvp022GateExecutionFailure.IDENTITY_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(":runtime:ide-read:test", ":runtime:ide-read:check"),
        Kvp022GateExecutionFailure.COMMAND_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"canonicalTaskPath\": \":runtime:ide-read:test\"",
            "\"canonicalTaskPath\": \":runtime:ide-read:check\"",
        ),
        Kvp022GateExecutionFailure.TASK_PATH_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"dedicatedTaskPath\": \":runtime:ide-read:epochRevalidationNegativeGate\"",
            "\"dedicatedTaskPath\": \":runtime:ide-read:epochRevalidationGate\"",
        ),
        Kvp022GateExecutionFailure.TASK_PATH_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"selectorPattern\": \"*EpochRevalidationNegativeTest\"",
            "\"selectorPattern\": \"*EpochRevalidationTest\"",
        ),
        Kvp022GateExecutionFailure.SELECTOR_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(Regex("[0-9a-f]{40}"), "f".repeat(40)),
        Kvp022GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceLast("0".repeat(40), "f".repeat(40)),
        Kvp022GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"AFTER\"", "\"BEFORE\""),
        Kvp022GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"COMPLETE\"", "\"STARTED\""),
        Kvp022GateExecutionFailure.PHASE_MISMATCH,
    ),
)

private fun String.replaceLast(oldValue: String, newValue: String): String {
    val index = lastIndexOf(oldValue)
    return if (index < 0) this else replaceRange(index, index + oldValue.length, newValue)
}
