package support.delivery

internal sealed interface Kvp023ReportMutationFailure {
    data class MutationAdmitted(
        val index: Int,
        val expected: Kvp023ReadOnlyGraphReportFailure,
    ) : Kvp023ReportMutationFailure

    data class WrongFailure(
        val index: Int,
        val expected: Kvp023ReadOnlyGraphReportFailure,
        val observed: Kvp023ReadOnlyGraphReportFailure,
    ) : Kvp023ReportMutationFailure
}

internal sealed interface Kvp023ReportMutationVerification {
    data object Complete : Kvp023ReportMutationVerification
    data class Rejected(val failure: Kvp023ReportMutationFailure) :
        Kvp023ReportMutationVerification
}

/** Establishes that every fixed KVP-023 report corruption has one exact finite failure. */
internal fun verifyKvp023ReportMutations(
    canonical: String,
    predecessors: Kvp023ReportPredecessors,
): Kvp023ReportMutationVerification {
    kvp023ReportMutations(canonical).forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp023ReadOnlyGraphReport.admit(
            mutation.raw,
            predecessors,
        )) {
            is Kvp023ReadOnlyGraphReportAdmission.Admitted ->
                return Kvp023ReportMutationVerification.Rejected(
                    Kvp023ReportMutationFailure.MutationAdmitted(index, mutation.expected),
                )
            is Kvp023ReadOnlyGraphReportAdmission.Rejected ->
                if (admission.failure != mutation.expected) {
                    return Kvp023ReportMutationVerification.Rejected(
                        Kvp023ReportMutationFailure.WrongFailure(
                            index,
                            mutation.expected,
                            admission.failure,
                        ),
                    )
                }
        }
    }
    return Kvp023ReportMutationVerification.Complete
}

internal sealed interface Kvp023PredecessorMutationVerification {
    data object Complete : Kvp023PredecessorMutationVerification
    data class MutationAdmitted(val id: Kvp023PredecessorReceiptId) :
        Kvp023PredecessorMutationVerification
    data class WrongFailure(
        val id: Kvp023PredecessorReceiptId,
        val observed: Kvp023ReadOnlyGraphReportFailure,
    ) : Kvp023PredecessorMutationVerification
}

/** Proves that no forged direct predecessor digest becomes KVP-023 evidence. */
internal fun verifyKvp023PredecessorMutations(
    canonicalReceipts: List<String>,
): Kvp023PredecessorMutationVerification {
    if (canonicalReceipts.size != Kvp023PredecessorReceiptId.entries.size) {
        return Kvp023PredecessorMutationVerification.MutationAdmitted(
            Kvp023PredecessorReceiptId.KVP_009_COMPLETE,
        )
    }
    Kvp023PredecessorReceiptId.entries.zip(canonicalReceipts).forEach { (id, canonical) ->
        val mutation = canonical.replaceFirst(
            Regex("\"receiptDigest\"\\s*:\\s*\"[0-9a-f]{64}\""),
            "\"receiptDigest\": \"${"0".repeat(64)}\"",
        )
        when (val result = Kvp023PredecessorReceipt.decode(id, mutation)) {
            is Kvp023PredecessorRefinement.Admitted ->
                return Kvp023PredecessorMutationVerification.MutationAdmitted(id)
            is Kvp023PredecessorRefinement.Rejected -> if (
                result.failure != Kvp023ReadOnlyGraphReportFailure.PREDECESSOR_RECEIPT_REJECTED
            ) return Kvp023PredecessorMutationVerification.WrongFailure(id, result.failure)
        }
    }
    return Kvp023PredecessorMutationVerification.Complete
}

internal sealed interface Kvp023GateMutationFailure {
    data class MutationAdmitted(val index: Int, val expected: Kvp023GateExecutionFailure) :
        Kvp023GateMutationFailure
    data class WrongFailure(
        val index: Int,
        val expected: Kvp023GateExecutionFailure,
        val observed: Kvp023GateExecutionFailure,
    ) : Kvp023GateMutationFailure
}

internal sealed interface Kvp023GateMutationVerification {
    data object Complete : Kvp023GateMutationVerification
    data class Rejected(val failure: Kvp023GateMutationFailure) :
        Kvp023GateMutationVerification
}

/** Establishes exact finite rejection for every fixed KVP-023 gate-evidence corruption. */
internal fun verifyKvp023GateMutations(
    canonical: String,
    command: Kvp023GateCommand,
    head: AuthorityGitRevision,
): Kvp023GateMutationVerification {
    kvp023GateMutations(canonical, command).forEachIndexed { index, mutation ->
        when (val admission = AdmittedKvp023GateExecution.admit(
            mutation.raw,
            command,
            head,
            Kvp023GateExecutionPhase.COMPLETE,
        )) {
            is Kvp023GateExecutionAdmission.Admitted ->
                return Kvp023GateMutationVerification.Rejected(
                    Kvp023GateMutationFailure.MutationAdmitted(index, mutation.expected),
                )
            is Kvp023GateExecutionAdmission.Rejected ->
                if (admission.failure != mutation.expected) {
                    return Kvp023GateMutationVerification.Rejected(
                        Kvp023GateMutationFailure.WrongFailure(
                            index,
                            mutation.expected,
                            admission.failure,
                        ),
                    )
                }
        }
    }
    return Kvp023GateMutationVerification.Complete
}

private data class Kvp023ReportMutation(
    val raw: String,
    val expected: Kvp023ReadOnlyGraphReportFailure,
)

private data class Kvp023GateMutation(
    val raw: String,
    val expected: Kvp023GateExecutionFailure,
)

private fun kvp023ReportMutations(canonical: String) = listOf(
    reportMutation(
        canonical.replaceFirst("{", "["),
        Kvp023ReadOnlyGraphReportFailure.MALFORMED_DOCUMENT,
    ),
    reportMutation(canonical.dropLast(1), Kvp023ReadOnlyGraphReportFailure.NON_CANONICAL_DOCUMENT),
    reportMutation(
        canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        Kvp023ReadOnlyGraphReportFailure.SCHEMA_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"taskId\": \"KVP-023\"", "\"taskId\": \"KVP-022\""),
        Kvp023ReadOnlyGraphReportFailure.IDENTITY_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"OPERATION_REGISTRY\"", "\"READ_RUNTIME\""),
        Kvp023ReadOnlyGraphReportFailure.AUTHORITY_SET_MISMATCH,
    ),
    reportMutation(
        addRelationReadBinding(canonical),
        Kvp023ReadOnlyGraphReportFailure.OPERATION_BINDING_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            "\"operation\": \"RELATION_READ\"",
            "\"operation\": \"TOPOLOGY_BUILD\"",
        ),
        Kvp023ReadOnlyGraphReportFailure.UNSUPPORTED_OPERATION_SET_MISMATCH,
    ),
    reportMutation(
        addRuntimeCompositionDependency(canonical),
        Kvp023ReadOnlyGraphReportFailure.PROJECT_DEPENDENCY_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("\"observedCount\": 0", "\"observedCount\": 1"),
        Kvp023ReadOnlyGraphReportFailure.FORBIDDEN_WORK_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst("KVP-009-COMPLETE", "KVP-016-COMPLETE"),
        Kvp023ReadOnlyGraphReportFailure.PREDECESSOR_SET_MISMATCH,
    ),
    reportMutation(
        canonical.replaceFirst(
            Regex("\"sha256\": \"[0-9a-f]{64}\""),
            "\"sha256\": \"${"0".repeat(64)}\"",
        ),
        Kvp023ReadOnlyGraphReportFailure.PREDECESSOR_SET_MISMATCH,
    ),
)

private fun addRelationReadBinding(canonical: String): String {
    val boundary = """    }
  ],
  "unsupportedOperations""".trimIndent()
    val relation = """    },
    {
      "operation": "RELATION_READ",
      "port": "SymbolResolveReadPort",
      "effect": "INTELLIJ_READ",
      "cost": "BOUNDED_READ"
    }
  ],
  "unsupportedOperations""".trimIndent()
    return canonical.replaceFirst(boundary, relation)
}

private fun addRuntimeCompositionDependency(canonical: String) = canonical.replaceFirst(
    """    "WORKSPACE_INTELLIJ_READ"
  ]""".trimIndent(),
    """    "WORKSPACE_INTELLIJ_READ",
    "RUNTIME_COMPOSITION"
  ]""".trimIndent(),
)

private fun kvp023GateMutations(
    canonical: String,
    command: Kvp023GateCommand,
) = listOf(
    gateMutation(canonical.replaceFirst("{", "["), Kvp023GateExecutionFailure.MALFORMED_DOCUMENT),
    gateMutation(canonical.dropLast(1), Kvp023GateExecutionFailure.NON_CANONICAL_DOCUMENT),
    gateMutation(
        canonical.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        Kvp023GateExecutionFailure.SCHEMA_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"taskId\": \"KVP-023\"", "\"taskId\": \"KVP-022\""),
        Kvp023GateExecutionFailure.IDENTITY_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"gateId\": \"${command.gateId}\"", "\"gateId\": \"bad\""),
        Kvp023GateExecutionFailure.IDENTITY_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"declaredCommand\": \"${command.declaredCommand}\"",
            "\"declaredCommand\": \"./gradlew check\"",
        ),
        Kvp023GateExecutionFailure.COMMAND_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"canonicalTaskPaths\": [",
            "\"canonicalTaskPaths\": [\n    \":runtime:ide-read:check\",",
        ),
        Kvp023GateExecutionFailure.TASK_PATH_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"dedicatedTaskPath\": \"${command.dedicatedTaskPath}\"",
            "\"dedicatedTaskPath\": \":runtime:ide-read:check\"",
        ),
        Kvp023GateExecutionFailure.TASK_PATH_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(
            "\"selectorPattern\": \"${command.selectorPattern}\"",
            "\"selectorPattern\": \"*WrongTest\"",
        ),
        Kvp023GateExecutionFailure.SELECTOR_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst(Regex("[0-9a-f]{40}"), "f".repeat(40)),
        Kvp023GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceLast("0".repeat(40), "f".repeat(40)),
        Kvp023GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"AFTER\"", "\"BEFORE\""),
        Kvp023GateExecutionFailure.HEAD_MISMATCH,
    ),
    gateMutation(
        canonical.replaceFirst("\"COMPLETE\"", "\"STARTED\""),
        Kvp023GateExecutionFailure.PHASE_MISMATCH,
    ),
)

private fun reportMutation(raw: String, expected: Kvp023ReadOnlyGraphReportFailure) =
    Kvp023ReportMutation(raw, expected)
private fun gateMutation(raw: String, expected: Kvp023GateExecutionFailure) =
    Kvp023GateMutation(raw, expected)

private fun String.replaceLast(oldValue: String, newValue: String): String {
    val index = lastIndexOf(oldValue)
    return if (index < 0) this else replaceRange(index, index + oldValue.length, newValue)
}
