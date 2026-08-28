package support.delivery

import java.time.Instant
import java.time.format.DateTimeParseException

@JvmInline internal value class TaskProofProgramVersion internal constructor(val value: String)
@JvmInline internal value class TaskDefinitionDigest internal constructor(val value: String)
@JvmInline internal value class TaskProofReceiptDigest internal constructor(val value: String)
@JvmInline internal value class TaskProofDependencyDigest internal constructor(val value: String)
@JvmInline internal value class RelevantInputDigest internal constructor(val value: String)
@JvmInline internal value class TaskProofCommandDigest internal constructor(val value: String)
@JvmInline internal value class ToolchainDigest internal constructor(val value: String)
@JvmInline internal value class TaskProofObservationName internal constructor(val value: String)
@JvmInline internal value class TaskProofObservationValue internal constructor(val value: String)
@JvmInline internal value class TaskProofOutputPath internal constructor(val value: String)
@JvmInline internal value class TaskProofOutputDigest internal constructor(val value: String)
@JvmInline internal value class TaskProofRecordedAt internal constructor(val value: String)

enum class TaskProofHeadPolicy { CONTENT_SCOPED, EXACT_HEAD }

internal enum class TaskProofExecutionFailure {
    PREDECESSOR_REJECTED,
    WRITE_SCOPE_REJECTED,
    FORBIDDEN_EFFECT_OBSERVED,
    MISUSE_NOT_REJECTED,
    LEGAL_PATH_NOT_COMPLETE,
    OUTPUT_REJECTED,
}

internal sealed interface TaskProofOutcome {
    @ConsistentCopyVisibility
    data class Complete internal constructor(
        val observations: Map<TaskProofObservationName, TaskProofObservationValue>,
    ) : TaskProofOutcome

    @ConsistentCopyVisibility
    data class Qualified internal constructor(
        val observations: Map<TaskProofObservationName, TaskProofObservationValue>,
        val limitations: NonEmptyLimitations,
    ) : TaskProofOutcome

    data class Rejected(val failure: TaskProofExecutionFailure) : TaskProofOutcome
}

internal enum class TaskProofReceiptFailure {
    MALFORMED_DOCUMENT,
    UNSUPPORTED_SCHEMA,
    MALFORMED_PROGRAM_VERSION,
    MALFORMED_RECEIPT_ID,
    MALFORMED_TASK_ID,
    RECEIPT_TASK_MISMATCH,
    MALFORMED_DIGEST,
    MALFORMED_DEPENDENCY_RECEIPTS,
    MALFORMED_OBSERVATION,
    MALFORMED_OUTPUT,
    MALFORMED_HEAD_POLICY,
    MALFORMED_OBSERVED_HEAD,
    MALFORMED_RECORDED_AT,
    PROGRAM_VERSION_MISMATCH,
    RECEIPT_ID_MISMATCH,
    TASK_ID_MISMATCH,
    TASK_DEFINITION_MISMATCH,
    DEPENDENCY_RECEIPTS_MISMATCH,
    RELEVANT_INPUT_MISMATCH,
    COMMAND_MISMATCH,
    TOOLCHAIN_MISMATCH,
    OBSERVATION_MISMATCH,
    OUTPUT_MISMATCH,
    HEAD_POLICY_MISMATCH,
    EXACT_HEAD_REQUIRED,
    RECEIPT_DIGEST_MISMATCH,
}

internal class TaskProofReceiptExpectation private constructor(
    val programVersion: TaskProofProgramVersion,
    val receiptId: ReceiptId,
    val taskId: TaskId,
    val taskDefinitionDigest: TaskDefinitionDigest,
    val dependencyReceiptDigests: Map<ReceiptId, TaskProofDependencyDigest>,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val outcome: TaskProofOutcome.Complete,
    val outputDigests: Map<TaskProofOutputPath, TaskProofOutputDigest>,
    val headPolicy: TaskProofHeadPolicy,
) {
    val completeObservations get() = outcome.observations

    companion object {
        /**
         * Proof transition: raw receipt fields -> `TaskProofReceiptExpectation`.
         *
         * Establishes canonical task/receipt identity, program version, complete relevant-input
         * closure, command/toolchain identity, nonempty complete observations, output identity,
         * and closed head policy. Expected malformed input returns finite
         * [TaskProofReceiptFailure]. Raw values may exit only at Gradle and JSON boundaries.
         */
        fun refine(
            programVersion: String,
            receiptId: String,
            taskId: String,
            taskDefinitionDigest: String,
            dependencyReceiptDigests: Map<String, String>,
            relevantInputDigest: String,
            commandDigest: String,
            toolchainDigest: String,
            completeObservations: Map<String, String>,
            outputDigests: Map<String, String>,
            headPolicy: String,
        ): TaskProofReceiptExpectationRefinement = refineTaskProofReceiptExpectation(
            programVersion,
            receiptId,
            taskId,
            taskDefinitionDigest,
            dependencyReceiptDigests,
            relevantInputDigest,
            commandDigest,
            toolchainDigest,
            completeObservations,
            outputDigests,
            headPolicy,
        )

        internal fun admitted(
            programVersion: TaskProofProgramVersion,
            receiptId: ReceiptId,
            taskId: TaskId,
            taskDefinitionDigest: TaskDefinitionDigest,
            dependencyReceiptDigests: Map<ReceiptId, TaskProofDependencyDigest>,
            relevantInputDigest: RelevantInputDigest,
            commandDigest: TaskProofCommandDigest,
            toolchainDigest: ToolchainDigest,
            outcome: TaskProofOutcome.Complete,
            outputDigests: Map<TaskProofOutputPath, TaskProofOutputDigest>,
            headPolicy: TaskProofHeadPolicy,
        ) = TaskProofReceiptExpectation(
            programVersion,
            receiptId,
            taskId,
            taskDefinitionDigest,
            dependencyReceiptDigests,
            relevantInputDigest,
            commandDigest,
            toolchainDigest,
            outcome,
            outputDigests,
            headPolicy,
        )
    }
}

internal sealed interface TaskProofReceiptExpectationRefinement {
    data class Complete(val expectation: TaskProofReceiptExpectation) :
        TaskProofReceiptExpectationRefinement
    data class Rejected(val failure: TaskProofReceiptFailure) :
        TaskProofReceiptExpectationRefinement
}

@ConsistentCopyVisibility
internal data class TaskProofReceiptDocument internal constructor(
    val schemaVersion: Int,
    val programVersion: TaskProofProgramVersion,
    val receiptId: ReceiptId,
    val taskId: TaskId,
    val taskDefinitionDigest: TaskDefinitionDigest,
    val dependencyReceiptDigests: Map<ReceiptId, TaskProofDependencyDigest>,
    val relevantInputDigest: RelevantInputDigest,
    val commandDigest: TaskProofCommandDigest,
    val toolchainDigest: ToolchainDigest,
    val completeObservations: Map<TaskProofObservationName, TaskProofObservationValue>,
    val outputDigests: Map<TaskProofOutputPath, TaskProofOutputDigest>,
    val headPolicy: TaskProofHeadPolicy,
    val observedRepositoryHead: DeliveryGeneration,
    val recordedAtUtc: TaskProofRecordedAt,
    val receiptDigest: TaskProofReceiptDigest,
)

internal class AdmittedTaskProofReceipt internal constructor(
    val receiptId: ReceiptId,
    val taskId: TaskId,
    val digest: TaskProofReceiptDigest,
    val observedRepositoryHead: DeliveryGeneration,
)

internal sealed interface TaskProofReceiptAdmission {
    data class Complete(val receipt: AdmittedTaskProofReceipt) : TaskProofReceiptAdmission
    data class Rejected(val failure: TaskProofReceiptFailure) : TaskProofReceiptAdmission
}

/**
 * Proof transition: `(TaskProofReceiptExpectation, DeliveryGeneration, Instant)` ->
 * `TaskProofReceiptDocument`.
 *
 * Preserves the complete content-scoped expectation, records the observed repository head, and
 * derives a canonical self digest. Raw time and head text may exit only at the JSON boundary.
 */
internal fun issueTaskProofReceipt(
    expectation: TaskProofReceiptExpectation,
    observedHead: DeliveryGeneration,
    recordedAt: Instant,
): TaskProofReceiptDocument {
    val unsigned = expectation.document(
        observedHead,
        TaskProofRecordedAt(recordedAt.toString()),
        TaskProofReceiptDigest(TASK_PROOF_ZERO_DIGEST),
    )
    return unsigned.copy(receiptDigest = unsigned.derivedDigest())
}

/**
 * Proof transition: `(TaskProofReceiptDocument, TaskProofReceiptExpectation,
 * DeliveryGeneration) -> TaskProofReceiptAdmission`.
 *
 * Establishes exact content-closure equality and canonical self integrity. Content-scoped proof
 * may retain its observed head; exact-head proof additionally requires that observation to equal
 * the current head. Every expected mismatch returns finite [TaskProofReceiptFailure].
 */
internal fun admitTaskProofReceipt(
    document: TaskProofReceiptDocument,
    expectation: TaskProofReceiptExpectation,
    currentHead: DeliveryGeneration,
): TaskProofReceiptAdmission {
    fun rejected(failure: TaskProofReceiptFailure) = TaskProofReceiptAdmission.Rejected(failure)
    if (document.programVersion != expectation.programVersion) {
        return rejected(TaskProofReceiptFailure.PROGRAM_VERSION_MISMATCH)
    }
    if (document.receiptId != expectation.receiptId) {
        return rejected(TaskProofReceiptFailure.RECEIPT_ID_MISMATCH)
    }
    if (document.taskId != expectation.taskId) {
        return rejected(TaskProofReceiptFailure.TASK_ID_MISMATCH)
    }
    if (document.taskDefinitionDigest != expectation.taskDefinitionDigest) {
        return rejected(TaskProofReceiptFailure.TASK_DEFINITION_MISMATCH)
    }
    if (document.dependencyReceiptDigests != expectation.dependencyReceiptDigests) {
        return rejected(TaskProofReceiptFailure.DEPENDENCY_RECEIPTS_MISMATCH)
    }
    if (document.relevantInputDigest != expectation.relevantInputDigest) {
        return rejected(TaskProofReceiptFailure.RELEVANT_INPUT_MISMATCH)
    }
    if (document.commandDigest != expectation.commandDigest) {
        return rejected(TaskProofReceiptFailure.COMMAND_MISMATCH)
    }
    if (document.toolchainDigest != expectation.toolchainDigest) {
        return rejected(TaskProofReceiptFailure.TOOLCHAIN_MISMATCH)
    }
    if (document.completeObservations != expectation.completeObservations) {
        return rejected(TaskProofReceiptFailure.OBSERVATION_MISMATCH)
    }
    if (document.outputDigests != expectation.outputDigests) {
        return rejected(TaskProofReceiptFailure.OUTPUT_MISMATCH)
    }
    if (document.headPolicy != expectation.headPolicy) {
        return rejected(TaskProofReceiptFailure.HEAD_POLICY_MISMATCH)
    }
    if (document.headPolicy == TaskProofHeadPolicy.EXACT_HEAD &&
        document.observedRepositoryHead != currentHead
    ) return rejected(TaskProofReceiptFailure.EXACT_HEAD_REQUIRED)
    if (document.receiptDigest != document.derivedDigest()) {
        return rejected(TaskProofReceiptFailure.RECEIPT_DIGEST_MISMATCH)
    }
    return TaskProofReceiptAdmission.Complete(
        AdmittedTaskProofReceipt(
            document.receiptId,
            document.taskId,
            document.receiptDigest,
            document.observedRepositoryHead,
        ),
    )
}

internal fun TaskProofReceiptDocument.derivedDigest() = TaskProofReceiptDigest(
    sha256(canonicalJson(canonicalPayload())).value,
)

private fun TaskProofReceiptDocument.canonicalPayload() = linkedMapOf<String, Any?>(
    "schemaVersion" to schemaVersion,
    "programVersion" to programVersion.value,
    "receiptId" to receiptId.value,
    "taskId" to taskId.value,
    "taskDefinitionDigest" to taskDefinitionDigest.value,
    "dependencyReceiptDigests" to dependencyReceiptDigests.mapKeys { it.key.value }
        .mapValues { it.value.value },
    "relevantInputDigest" to relevantInputDigest.value,
    "commandDigest" to commandDigest.value,
    "toolchainDigest" to toolchainDigest.value,
    "completeObservations" to completeObservations.mapKeys { it.key.value }
        .mapValues { it.value.value },
    "outputDigests" to outputDigests.mapKeys { it.key.value }.mapValues { it.value.value },
    "headPolicy" to headPolicy.name,
    "observedRepositoryHead" to observedRepositoryHead.value,
    "recordedAtUtc" to recordedAtUtc.value,
)

private fun TaskProofReceiptExpectation.document(
    observedHead: DeliveryGeneration,
    recordedAt: TaskProofRecordedAt,
    digest: TaskProofReceiptDigest,
) = TaskProofReceiptDocument(
    TASK_PROOF_RECEIPT_SCHEMA_VERSION,
    programVersion,
    receiptId,
    taskId,
    taskDefinitionDigest,
    dependencyReceiptDigests,
    relevantInputDigest,
    commandDigest,
    toolchainDigest,
    completeObservations,
    outputDigests,
    headPolicy,
    observedHead,
    recordedAt,
    digest,
)

internal const val TASK_PROOF_RECEIPT_SCHEMA_VERSION = 2
internal const val TASK_PROOF_PROGRAM_VERSION = "vfs-passive-task-proof-v2"
private const val TASK_PROOF_ZERO_DIGEST =
    "0000000000000000000000000000000000000000000000000000000000000000"

internal fun String.isCanonicalTaskProofInstant(): Boolean = try {
    Instant.parse(this).toString() == this
} catch (_: DateTimeParseException) {
    false
}
