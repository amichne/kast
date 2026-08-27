package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class TaskProofReceiptJsonDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val receiptId: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val completeObservations: Map<String, String>,
    val outputDigests: Map<String, String>,
    val headPolicy: String,
    val observedRepositoryHead: String,
    val recordedAtUtc: String,
    val receiptDigest: String,
)

private val taskProofReceiptJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

internal sealed interface TaskProofReceiptDocumentRefinement {
    data class Complete(val document: TaskProofReceiptDocument) :
        TaskProofReceiptDocumentRefinement
    data class Rejected(val failure: TaskProofReceiptFailure) :
        TaskProofReceiptDocumentRefinement
}

/**
 * Proof transition: task-proof receipt JSON -> `TaskProofReceiptDocumentRefinement`.
 *
 * The generated serializer establishes the closed v2 JSON shape; domain refinement establishes
 * every identity, digest, observation, path, head, time, and policy invariant. Expected malformed
 * input returns finite [TaskProofReceiptFailure]. Raw JSON exists only at this boundary.
 */
internal fun decodeTaskProofReceipt(raw: String): TaskProofReceiptDocumentRefinement {
    val document = try {
        taskProofReceiptJson.decodeFromString(TaskProofReceiptJsonDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejected(TaskProofReceiptFailure.MALFORMED_DOCUMENT)
    } catch (_: IllegalArgumentException) {
        return rejected(TaskProofReceiptFailure.MALFORMED_DOCUMENT)
    }
    if (document.schemaVersion != TASK_PROOF_RECEIPT_SCHEMA_VERSION) {
        return rejected(TaskProofReceiptFailure.UNSUPPORTED_SCHEMA)
    }
    val expectation = when (val refined = TaskProofReceiptExpectation.refine(
        document.programVersion,
        document.receiptId,
        document.taskId,
        document.taskDefinitionDigest,
        document.dependencyReceiptDigests,
        document.relevantInputDigest,
        document.commandDigest,
        document.toolchainDigest,
        document.completeObservations,
        document.outputDigests,
        document.headPolicy,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> return rejected(refined.failure)
    }
    val head = when (val refined = refineDeliveryGeneration(document.observedRepositoryHead)) {
        is DeliveryRefinement.Complete -> refined.value
        is DeliveryRefinement.Rejected -> return rejected(
            TaskProofReceiptFailure.MALFORMED_OBSERVED_HEAD,
        )
    }
    if (!document.recordedAtUtc.isCanonicalTaskProofInstant()) {
        return rejected(TaskProofReceiptFailure.MALFORMED_RECORDED_AT)
    }
    if (!document.receiptDigest.matches(Regex("[0-9a-f]{64}"))) {
        return rejected(TaskProofReceiptFailure.MALFORMED_DIGEST)
    }
    return TaskProofReceiptDocumentRefinement.Complete(
        TaskProofReceiptDocument(
            document.schemaVersion,
            expectation.programVersion,
            expectation.receiptId,
            expectation.taskId,
            expectation.taskDefinitionDigest,
            expectation.dependencyReceiptDigests,
            expectation.relevantInputDigest,
            expectation.commandDigest,
            expectation.toolchainDigest,
            expectation.completeObservations,
            expectation.outputDigests,
            expectation.headPolicy,
            head,
            TaskProofRecordedAt(document.recordedAtUtc),
            TaskProofReceiptDigest(document.receiptDigest),
        ),
    )
}

/**
 * Proof transition: task-proof receipt JSON plus admitted expectation/current head ->
 * `TaskProofReceiptAdmission`.
 *
 * Preserves generated parsing and all content-scoped or exact-head admission invariants. Expected
 * malformed or mismatched bytes remain finite typed failure. Raw JSON exists only at this boundary.
 */
internal fun admitTaskProofReceipt(
    raw: String,
    expectation: TaskProofReceiptExpectation,
    currentHead: DeliveryGeneration,
): TaskProofReceiptAdmission = when (val decoded = decodeTaskProofReceipt(raw)) {
    is TaskProofReceiptDocumentRefinement.Complete ->
        admitTaskProofReceipt(decoded.document, expectation, currentHead)
    is TaskProofReceiptDocumentRefinement.Rejected ->
        TaskProofReceiptAdmission.Rejected(decoded.failure)
}

internal fun encodeTaskProofReceipt(document: TaskProofReceiptDocument): String =
    taskProofReceiptJson.encodeToString(
        TaskProofReceiptJsonDocument.serializer(),
        TaskProofReceiptJsonDocument(
            document.schemaVersion,
            document.programVersion.value,
            document.receiptId.value,
            document.taskId.value,
            document.taskDefinitionDigest.value,
            document.dependencyReceiptDigests.mapKeys { it.key.value }
                .mapValues { it.value.value }.toSortedMap(),
            document.relevantInputDigest.value,
            document.commandDigest.value,
            document.toolchainDigest.value,
            document.completeObservations.mapKeys { it.key.value }
                .mapValues { it.value.value }.toSortedMap(),
            document.outputDigests.mapKeys { it.key.value }
                .mapValues { it.value.value }.toSortedMap(),
            document.headPolicy.name,
            document.observedRepositoryHead.value,
            document.recordedAtUtc.value,
            document.receiptDigest.value,
        ),
    ) + "\n"

private fun rejected(failure: TaskProofReceiptFailure) =
    TaskProofReceiptDocumentRefinement.Rejected(failure)
