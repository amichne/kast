package support.delivery

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface Kvp024ReportMutationFailure {
    data class MutationAdmitted(
        val index: Int,
        val expected: Kvp024EndpointPublicationReportFailure,
    ) : Kvp024ReportMutationFailure

    data class WrongFailure(
        val index: Int,
        val expected: Kvp024EndpointPublicationReportFailure,
        val observed: Kvp024EndpointPublicationReportFailure,
    ) : Kvp024ReportMutationFailure
}

internal sealed interface Kvp024ReportMutationVerification {
    data object Complete : Kvp024ReportMutationVerification
    data class Rejected(val failure: Kvp024ReportMutationFailure) :
        Kvp024ReportMutationVerification
}

/** Establishes that every fixed KVP-024 report corruption has one exact finite failure. */
internal fun verifyKvp024ReportMutations(
    canonical: String,
    predecessors: Kvp024ReportPredecessors,
): Kvp024ReportMutationVerification {
    kvp024ReportMutations(canonical).forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp024EndpointPublicationReport.admit(
            mutation.raw,
            predecessors,
        )) {
            is Kvp024EndpointPublicationReportAdmission.Admitted ->
                return Kvp024ReportMutationVerification.Rejected(
                    Kvp024ReportMutationFailure.MutationAdmitted(index, mutation.expected),
                )
            is Kvp024EndpointPublicationReportAdmission.Rejected ->
                if (admission.failure != mutation.expected) {
                    return Kvp024ReportMutationVerification.Rejected(
                        Kvp024ReportMutationFailure.WrongFailure(
                            index,
                            mutation.expected,
                            admission.failure,
                        ),
                    )
                }
        }
    }
    return Kvp024ReportMutationVerification.Complete
}

internal sealed interface Kvp024PredecessorMutationVerification {
    data object Complete : Kvp024PredecessorMutationVerification
    data class MutationAdmitted(val id: Kvp024PredecessorReceiptId) :
        Kvp024PredecessorMutationVerification
    data class WrongFailure(
        val id: Kvp024PredecessorReceiptId,
        val observed: Kvp024EndpointPublicationReportFailure,
    ) : Kvp024PredecessorMutationVerification
}

/** Proves that no forged direct predecessor digest becomes KVP-024 evidence. */
internal fun verifyKvp024PredecessorMutations(
    canonicalReceipts: List<String>,
): Kvp024PredecessorMutationVerification {
    if (canonicalReceipts.size != Kvp024PredecessorReceiptId.entries.size) {
        return Kvp024PredecessorMutationVerification.MutationAdmitted(
            Kvp024PredecessorReceiptId.KVP_013_COMPLETE,
        )
    }
    Kvp024PredecessorReceiptId.entries.zip(canonicalReceipts).forEach { (id, canonical) ->
        val mutation = canonical.replaceFirst(
            Regex("\"receiptDigest\"\\s*:\\s*\"[0-9a-f]{64}\""),
            "\"receiptDigest\": \"${"0".repeat(64)}\"",
        )
        when (val result = Kvp024PredecessorReceipt.decode(id, mutation)) {
            is Kvp024PredecessorRefinement.Admitted ->
                return Kvp024PredecessorMutationVerification.MutationAdmitted(id)
            is Kvp024PredecessorRefinement.Rejected -> if (
                result.failure != Kvp024EndpointPublicationReportFailure.PREDECESSOR_RECEIPT_REJECTED
            ) return Kvp024PredecessorMutationVerification.WrongFailure(id, result.failure)
        }
    }
    return Kvp024PredecessorMutationVerification.Complete
}

internal sealed interface Kvp024GateMutationFailure {
    data class MutationAdmitted(val index: Int, val expected: Kvp024GateExecutionFailure) :
        Kvp024GateMutationFailure
    data class WrongFailure(
        val index: Int,
        val expected: Kvp024GateExecutionFailure,
        val observed: Kvp024GateExecutionFailure,
    ) : Kvp024GateMutationFailure
}

internal sealed interface Kvp024GateMutationVerification {
    data object Complete : Kvp024GateMutationVerification
    data class Rejected(val failure: Kvp024GateMutationFailure) :
        Kvp024GateMutationVerification
}

/** Establishes exact finite rejection for every fixed KVP-024 gate-evidence corruption. */
internal fun verifyKvp024GateMutations(
    canonical: String,
    command: Kvp024GateCommand,
    head: AuthorityGitRevision,
): Kvp024GateMutationVerification {
    kvp024GateMutations(canonical, command).forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp024GateExecution.admit(
            mutation.raw,
            command,
            head,
            Kvp024GateExecutionPhase.COMPLETE,
        )) {
            is Kvp024GateExecutionAdmission.Admitted ->
                return Kvp024GateMutationVerification.Rejected(
                    Kvp024GateMutationFailure.MutationAdmitted(index, mutation.expected),
                )
            is Kvp024GateExecutionAdmission.Rejected ->
                if (admission.failure != mutation.expected) {
                    return Kvp024GateMutationVerification.Rejected(
                        Kvp024GateMutationFailure.WrongFailure(
                            index,
                            mutation.expected,
                            admission.failure,
                        ),
                    )
                }
        }
    }
    return Kvp024GateMutationVerification.Complete
}

private data class Kvp024ReportMutation(
    val raw: String,
    val expected: Kvp024EndpointPublicationReportFailure,
)

private data class Kvp024GateMutation(
    val raw: String,
    val expected: Kvp024GateExecutionFailure,
)

private fun kvp024ReportMutations(canonical: String) = listOf(
    reportMutation(
        canonical.replaceFirst("{", "["),
        Kvp024EndpointPublicationReportFailure.MALFORMED_DOCUMENT,
    ),
    reportMutation(
        canonical.dropLast(1),
        Kvp024EndpointPublicationReportFailure.NON_CANONICAL_DOCUMENT,
    ),
    reportMutation(
        canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        Kvp024EndpointPublicationReportFailure.SCHEMA_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"taskId\": \"KVP-024\"", "\"taskId\": \"KVP-022\""),
        Kvp024EndpointPublicationReportFailure.IDENTITY_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"ADMITTED_IDE_PROJECT_EXACT_ROOT\"",
            "\"IDE_READ_RUNTIME_DISPATCH\"",
        ),
        Kvp024EndpointPublicationReportFailure.PREPARATION_INPUT_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"UNPUBLISHED\"", "\"READY\""),
        Kvp024EndpointPublicationReportFailure.SERVICE_STATE_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"from\": \"UNPUBLISHED\",\n      \"to\": \"BOUND\"",
            "\"from\": \"UNPUBLISHED\",\n      \"to\": \"READY\"",
        ),
        Kvp024EndpointPublicationReportFailure.TRANSITION_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"field\": \"SCHEMA\"",
            "\"field\": \"CANONICAL_ROOT\"",
        ),
        Kvp024EndpointPublicationReportFailure.DESCRIPTOR_BINDING_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"ATOMIC_MOVE_REQUIRED\"", "\"NO_MOVE_FALLBACK\""),
        Kvp024EndpointPublicationReportFailure.DESCRIPTOR_RULE_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"endpointLimitPerProject\": 1",
            "\"endpointLimitPerProject\": 2",
        ),
        Kvp024EndpointPublicationReportFailure.PUBLICATION_LIMITS_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"WRONG_ROOT\"", "\"PARTIAL_RUNTIME\""),
        Kvp024EndpointPublicationReportFailure.REJECTION_CASE_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"case\": \"OCCUPIED_DESCRIPTOR_PATH\",\n" +
                "      \"decision\": \"PRESERVE_AND_REJECT\"",
            "\"case\": \"OCCUPIED_DESCRIPTOR_PATH\",\n" +
                "      \"decision\": \"RETIRE_OWNED_AND_REJECT\"",
        ),
        Kvp024EndpointPublicationReportFailure.REJECTION_CASE_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"OWNED_BOUND_SOCKET_NAMESPACE\"",
            "\"OWNED_TEMPORARY_DESCRIPTOR\"",
        ),
        Kvp024EndpointPublicationReportFailure.ROLLBACK_ARTIFACT_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"observedCount\": 0", "\"observedCount\": 1"),
        Kvp024EndpointPublicationReportFailure.FORBIDDEN_WORK_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("KVP-013-COMPLETE", "KVP-023-COMPLETE"),
        Kvp024EndpointPublicationReportFailure.PREDECESSOR_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            Regex("\"sha256\": \"[0-9a-f]{64}\""),
            "\"sha256\": \"${"0".repeat(64)}\"",
        ),
        Kvp024EndpointPublicationReportFailure.PREDECESSOR_SET_MISMATCH,
    ),
)

private fun kvp024GateMutations(
    canonical: String,
    command: Kvp024GateCommand,
) = listOf(
    gateMutation(canonical.replaceFirst("{", "["), Kvp024GateExecutionFailure.MALFORMED_DOCUMENT),
    gateMutation(canonical.dropLast(1), Kvp024GateExecutionFailure.NON_CANONICAL_DOCUMENT),
    gateMutation(
        canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        Kvp024GateExecutionFailure.SCHEMA_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"taskId\": \"KVP-024\"", "\"taskId\": \"KVP-022\""),
        Kvp024GateExecutionFailure.IDENTITY_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"gateId\": \"${command.gateId}\"", "\"gateId\": \"bad\""),
        Kvp024GateExecutionFailure.IDENTITY_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"declaredCommand\": ${Json.encodeToString(command.declaredCommand)}",
            "\"declaredCommand\": ${Json.encodeToString("./gradlew check")}",
        ),
        Kvp024GateExecutionFailure.COMMAND_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"canonicalTaskPaths\": [",
            "\"canonicalTaskPaths\": [\n    \":ide-plugin:check\",",
        ),
        Kvp024GateExecutionFailure.TASK_PATH_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"dedicatedTaskPath\": \"${command.dedicatedTaskPath}\"",
            "\"dedicatedTaskPath\": \":ide-plugin:check\"",
        ),
        Kvp024GateExecutionFailure.TASK_PATH_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"selectorPattern\": \"${command.selectorPattern}\"",
            "\"selectorPattern\": \"*WrongTest\"",
        ),
        Kvp024GateExecutionFailure.SELECTOR_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(Regex("[0-9a-f]{40}"), "f".repeat(40)),
        Kvp024GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceLast("0".repeat(40), "f".repeat(40)),
        Kvp024GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"AFTER\"", "\"BEFORE\""),
        Kvp024GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"COMPLETE\"", "\"STARTED\""),
        Kvp024GateExecutionFailure.PHASE_MISMATCH,
    ),
)

private fun reportMutation(raw: String, expected: Kvp024EndpointPublicationReportFailure) =
    Kvp024ReportMutation(raw, expected)
private fun gateMutation(raw: String, expected: Kvp024GateExecutionFailure) =
    Kvp024GateMutation(raw, expected)

private fun String.replaceLast(oldValue: String, newValue: String): String {
    val index = lastIndexOf(oldValue)
    return if (index < 0) this else replaceRange(index, index + oldValue.length, newValue)
}
