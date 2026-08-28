package support.delivery

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class TaskProofReceiptTest {
    private val expectation = complete(
        TaskProofReceiptExpectation.refine(
            programVersion = "vfs-passive-task-proof-v2",
            receiptId = "KVP-025-COMPLETE",
            taskId = "KVP-025",
            taskDefinitionDigest = "1".repeat(64),
            dependencyReceiptDigests = mapOf("KVP-024-COMPLETE" to "2".repeat(64)),
            relevantInputDigest = "3".repeat(64),
            commandDigest = "4".repeat(64),
            toolchainDigest = "5".repeat(64),
            completeObservations = mapOf(
                "misuse" to "REJECTED_DESCRIPTOR_IDENTITY_MISMATCH",
                "legalPath" to "RETIRED_EXACTLY_ONCE",
            ),
            outputDigests = mapOf(
                "ide-plugin/build/reports/KVP-025-retirement.json" to "6".repeat(64),
            ),
            headPolicy = "CONTENT_SCOPED",
        ),
    )
    private val observedHead = DeliveryGeneration("7".repeat(40))

    @Test
    fun `raw proof inputs refine through complete receipt to admitted capability`() {
        val document = issueTaskProofReceipt(expectation, observedHead, RECORDED_AT)
        val admitted = assertInstanceOf(
            TaskProofReceiptAdmission.Complete::class.java,
            admitTaskProofReceipt(document, expectation, DeliveryGeneration("8".repeat(40))),
        ).receipt

        assertEquals(ReceiptId("KVP-025-COMPLETE"), admitted.receiptId)
        assertEquals(observedHead, admitted.observedRepositoryHead)
        assertEquals(document.receiptDigest, admitted.digest)
    }

    @Test
    fun `exact head receipt rejects a content-identical observation from another head`() {
        val exactExpectation = expectation.withExactHeadRequired()
        val document = issueTaskProofReceipt(exactExpectation, observedHead, RECORDED_AT)

        assertEquals(
            TaskProofReceiptAdmission.Rejected(TaskProofReceiptFailure.EXACT_HEAD_REQUIRED),
            admitTaskProofReceipt(
                document,
                exactExpectation,
                DeliveryGeneration("8".repeat(40)),
            ),
        )
    }

    @Test
    fun `every content scoped field and the self digest is admitted exactly`() {
        val document = issueTaskProofReceipt(expectation, observedHead, RECORDED_AT)
        val mutations = listOf(
            document.copy(programVersion = TaskProofProgramVersion("changed-version")) to
                TaskProofReceiptFailure.PROGRAM_VERSION_MISMATCH,
            document.copy(receiptId = ReceiptId("KVP-026-COMPLETE")) to
                TaskProofReceiptFailure.RECEIPT_ID_MISMATCH,
            document.copy(taskId = TaskId("KVP-026")) to
                TaskProofReceiptFailure.TASK_ID_MISMATCH,
            document.copy(taskDefinitionDigest = TaskDefinitionDigest("0".repeat(64))) to
                TaskProofReceiptFailure.TASK_DEFINITION_MISMATCH,
            document.copy(dependencyReceiptDigests = emptyMap()) to
                TaskProofReceiptFailure.DEPENDENCY_RECEIPTS_MISMATCH,
            document.copy(relevantInputDigest = RelevantInputDigest("0".repeat(64))) to
                TaskProofReceiptFailure.RELEVANT_INPUT_MISMATCH,
            document.copy(commandDigest = TaskProofCommandDigest("0".repeat(64))) to
                TaskProofReceiptFailure.COMMAND_MISMATCH,
            document.copy(toolchainDigest = ToolchainDigest("0".repeat(64))) to
                TaskProofReceiptFailure.TOOLCHAIN_MISMATCH,
            document.copy(completeObservations = emptyMap()) to
                TaskProofReceiptFailure.OBSERVATION_MISMATCH,
            document.copy(outputDigests = emptyMap()) to
                TaskProofReceiptFailure.OUTPUT_MISMATCH,
            document.copy(headPolicy = TaskProofHeadPolicy.EXACT_HEAD) to
                TaskProofReceiptFailure.HEAD_POLICY_MISMATCH,
            document.copy(receiptDigest = TaskProofReceiptDigest("0".repeat(64))) to
                TaskProofReceiptFailure.RECEIPT_DIGEST_MISMATCH,
        )

        mutations.forEach { (candidate, failure) ->
            assertEquals(
                TaskProofReceiptAdmission.Rejected(failure),
                admitTaskProofReceipt(candidate, expectation, observedHead),
            )
        }
    }

    private fun TaskProofReceiptExpectation.withExactHeadRequired() = complete(
        TaskProofReceiptExpectation.refine(
            programVersion.value,
            receiptId.value,
            taskId.value,
            taskDefinitionDigest.value,
            dependencyReceiptDigests.mapKeys { it.key.value }.mapValues { it.value.value },
            relevantInputDigest.value,
            commandDigest.value,
            toolchainDigest.value,
            completeObservations.mapKeys { it.key.value }.mapValues { it.value.value },
            outputDigests.mapKeys { it.key.value }.mapValues { it.value.value },
            TaskProofHeadPolicy.EXACT_HEAD.name,
        ),
    )

    private fun complete(result: TaskProofReceiptExpectationRefinement) = assertInstanceOf(
        TaskProofReceiptExpectationRefinement.Complete::class.java,
        result,
    ).expectation

    companion object {
        private val RECORDED_AT: Instant = Instant.parse("2026-08-27T04:00:00Z")
    }
}
