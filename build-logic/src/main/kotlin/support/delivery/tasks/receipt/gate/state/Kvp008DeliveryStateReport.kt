package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal enum class Kvp008TerminalObservation { PENDING, PROVEN }

internal enum class Kvp008DeliveryStateProofFailure {
    DERIVATION_REJECTED,
    EXPECTED_STATE_MISSING,
}

internal enum class Kvp008DeliveryStateReportFailure {
    MALFORMED_DOCUMENT,
    SCHEMA_VERSION_MISMATCH,
    TASK_ID_MISMATCH,
    PROOF_MISMATCH,
    DERIVATION_REJECTED,
}

internal data class Kvp008DeliveryStateProof(
    val initialReadyTaskIds: List<TaskId>,
    val initialBlockedTaskCount: Int,
    val initialTerminal: Kvp008TerminalObservation,
    val staleInvalidation: DeliveryTaskInvalidation,
    val duplicateInvalidation: DeliveryTaskInvalidation,
    val partialProvenTaskCount: Int,
    val partialReadyTaskIds: List<TaskId>,
    val partialTerminal: Kvp008TerminalObservation,
    val completeProvenTaskCount: Int,
    val passedRequirementCount: Int,
    val criticalPath: List<TaskId>,
    val completeTerminalTaskId: TaskId,
)

internal sealed interface Kvp008DeliveryStateProofResult {
    data class Complete(val proof: Kvp008DeliveryStateProof) : Kvp008DeliveryStateProofResult
    data class Rejected(val failure: Kvp008DeliveryStateProofFailure) :
        Kvp008DeliveryStateProofResult
}

internal sealed interface Kvp008DeliveryStateReportResult {
    data class Complete(val proof: Kvp008DeliveryStateProof) : Kvp008DeliveryStateReportResult
    data class Rejected(val failure: Kvp008DeliveryStateReportFailure) :
        Kvp008DeliveryStateReportResult
}

@Serializable
private data class Kvp008DeliveryStateDocument(
    val completeProvenTaskCount: Int,
    val completeTerminal: Kvp008TerminalObservation,
    val completeTerminalTaskId: String,
    val criticalPath: List<String>,
    val duplicateInvalidation: String,
    val initialBlockedTaskCount: Int,
    val initialReadyTaskIds: List<String>,
    val initialTerminal: Kvp008TerminalObservation,
    val partialProvenTaskCount: Int,
    val partialReadyTaskIds: List<String>,
    val partialTerminal: Kvp008TerminalObservation,
    val passedRequirementCount: Int,
    val schemaVersion: Int,
    val staleInvalidation: String,
    val taskId: String,
)

private val kvp008DeliveryStateJson = Json { ignoreUnknownKeys = false; prettyPrint = true }

/**
 * Proof transition: validated program plus exact head -> `Kvp008DeliveryStateProofResult`.
 * Establishes empty, stale, duplicate, partial, and complete receipt-fold observations solely
 * through `DerivedProgramState`. Expected derivation gaps are finite
 * [Kvp008DeliveryStateProofFailure]; synthetic receipts do not leave this proof boundary.
 */
internal fun deriveKvp008DeliveryStateProof(
    validated: ValidatedProgram,
    exactHead: AuthorityGitRevision,
): Kvp008DeliveryStateProofResult {
    val firstTask = validated.order.first()
    val empty = when (val result = deriveProgramState(validated, exactHead, emptyList())) {
        is DerivedProgramStateResult.Complete -> result.state
        is DerivedProgramStateResult.Rejected -> return rejectedKvp008Proof()
    }
    val stale = when (val result = deriveProgramState(
        validated,
        exactHead,
        listOf(kvp008CompletionReceipt(firstTask, AuthorityGitRevision("0".repeat(40)))),
    )) {
        is DerivedProgramStateResult.Complete -> result.state
        is DerivedProgramStateResult.Rejected -> return rejectedKvp008Proof()
    }
    val receipt = kvp008CompletionReceipt(firstTask, exactHead)
    val duplicate = when (
        val result = deriveProgramState(validated, exactHead, listOf(receipt, receipt))
    ) {
        is DerivedProgramStateResult.Complete -> result.state
        is DerivedProgramStateResult.Rejected -> return rejectedKvp008Proof()
    }
    val partial = when (val result = deriveProgramState(
        validated,
        exactHead,
        validated.order.take(7).map { kvp008CompletionReceipt(it, exactHead) },
    )) {
        is DerivedProgramStateResult.Complete -> result.state
        is DerivedProgramStateResult.Rejected -> return rejectedKvp008Proof()
    }
    val complete = when (val result = deriveProgramState(
        validated,
        exactHead,
        validated.order.map { kvp008CompletionReceipt(it, exactHead) },
    )) {
        is DerivedProgramStateResult.Complete -> result.state
        is DerivedProgramStateResult.Rejected -> return rejectedKvp008Proof()
    }
    val staleState = when (val state = stale.taskStates.getValue(firstTask)) {
        is DerivedTaskState.Invalid -> state
        else -> return missingKvp008State()
    }
    val duplicateState = when (val state = duplicate.taskStates.getValue(firstTask)) {
        is DerivedTaskState.Invalid -> state
        else -> return missingKvp008State()
    }
    val terminal = when (val state = complete.terminal) {
        is DerivedTerminalState.Proven -> state
        is DerivedTerminalState.Pending -> return missingKvp008State()
    }
    return Kvp008DeliveryStateProofResult.Complete(
        Kvp008DeliveryStateProof(
            empty.readyTaskIds(),
            empty.taskStates.values.count { it is DerivedTaskState.Blocked },
            empty.terminal.observation(),
            staleState.invalidation,
            duplicateState.invalidation,
            partial.taskStates.values.count { it is DerivedTaskState.Proven },
            partial.readyTaskIds(),
            partial.terminal.observation(),
            complete.taskStates.values.count { it is DerivedTaskState.Proven },
            complete.requirementStates.values.count { it is DerivedRequirementState.Passed },
            complete.criticalPath,
            terminal.completion.terminalReceipt.taskId,
        ),
    )
}

private fun DerivedProgramState.readyTaskIds(): List<TaskId> = taskStates.values
    .filterIsInstance<DerivedTaskState.Ready>()
    .map { it.taskId }

private fun missingKvp008State() = Kvp008DeliveryStateProofResult.Rejected(
    Kvp008DeliveryStateProofFailure.EXPECTED_STATE_MISSING,
)

private fun rejectedKvp008Proof() = Kvp008DeliveryStateProofResult.Rejected(
    Kvp008DeliveryStateProofFailure.DERIVATION_REJECTED,
)

private fun DerivedTerminalState.observation(): Kvp008TerminalObservation = when (this) {
    is DerivedTerminalState.Pending -> Kvp008TerminalObservation.PENDING
    is DerivedTerminalState.Proven -> Kvp008TerminalObservation.PROVEN
}

private fun kvp008CompletionReceipt(taskId: TaskId, exactHead: AuthorityGitRevision) =
    AdmittedProofReceipt(
        ProofReceiptId("${taskId.value}-COMPLETE"),
        ProofReceiptDigest(sha256("KVP-008:${taskId.value}").value),
        exactHead,
        taskId,
        ProofGateId("${taskId.value}-COMPLETE-GATE"),
    )

/**
 * Proof transition: `Kvp008DeliveryStateProof -> String`.
 * Preserves all derived state observations in a generated closed JSON document. No expected
 * failure exists after derivation; raw JSON is emitted only at the Gradle report boundary.
 */
internal fun encodeKvp008DeliveryStateProof(proof: Kvp008DeliveryStateProof): String =
    kvp008DeliveryStateJson.encodeToString(
        Kvp008DeliveryStateDocument.serializer(),
        proof.document(),
    ) + "\n"

/**
 * Proof transition: report JSON `String -> Kvp008DeliveryStateReportResult`.
 * Establishes exact schema, task identity, and equality with an independently derived state proof.
 * Expected malformed or mismatched evidence is finite [Kvp008DeliveryStateReportFailure]; raw JSON
 * stays at this boundary.
 */
internal fun decodeKvp008DeliveryStateProof(
    raw: String,
    validated: ValidatedProgram,
    exactHead: AuthorityGitRevision,
): Kvp008DeliveryStateReportResult {
    val document = try {
        kvp008DeliveryStateJson.decodeFromString(Kvp008DeliveryStateDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejectedKvp008Report(Kvp008DeliveryStateReportFailure.MALFORMED_DOCUMENT)
    }
    val proof = when (val result = deriveKvp008DeliveryStateProof(validated, exactHead)) {
        is Kvp008DeliveryStateProofResult.Complete -> result.proof
        is Kvp008DeliveryStateProofResult.Rejected -> {
            return rejectedKvp008Report(Kvp008DeliveryStateReportFailure.DERIVATION_REJECTED)
        }
    }
    val failure = when {
        document.schemaVersion != 1 -> Kvp008DeliveryStateReportFailure.SCHEMA_VERSION_MISMATCH
        document.taskId != "KVP-008" -> Kvp008DeliveryStateReportFailure.TASK_ID_MISMATCH
        document != proof.document() -> Kvp008DeliveryStateReportFailure.PROOF_MISMATCH
        else -> return Kvp008DeliveryStateReportResult.Complete(proof)
    }
    return rejectedKvp008Report(failure)
}

private fun Kvp008DeliveryStateProof.document() = Kvp008DeliveryStateDocument(
    completeProvenTaskCount,
    Kvp008TerminalObservation.PROVEN,
    completeTerminalTaskId.value,
    criticalPath.map { it.value },
    duplicateInvalidation.name,
    initialBlockedTaskCount,
    initialReadyTaskIds.map { it.value },
    initialTerminal,
    partialProvenTaskCount,
    partialReadyTaskIds.map { it.value },
    partialTerminal,
    passedRequirementCount,
    1,
    staleInvalidation.name,
    "KVP-008",
)

private fun rejectedKvp008Report(failure: Kvp008DeliveryStateReportFailure) =
    Kvp008DeliveryStateReportResult.Rejected(failure)
