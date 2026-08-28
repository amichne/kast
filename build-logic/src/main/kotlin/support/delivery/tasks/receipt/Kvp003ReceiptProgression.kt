package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

internal data class Kvp003ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val predecessor: AdmittedProofReceipt,
    val taskId: String,
    val redGateId: String,
    val greenGateId: String,
    val completionGateId: String,
    val redReceiptId: String,
    val greenReceiptId: String,
    val completionReceiptId: String,
    val redCommand: String,
    val greenCommand: String,
    val completionCommand: String,
    val taskInputDigest: String,
    val completionInputDigest: String,
    val proofReportPath: String,
) {
    fun proof(): Kvp003GraphProof = when (val result = deriveKvp003GraphProof()) {
        is Kvp003GraphProofResult.Complete -> result.proof
        is Kvp003GraphProofResult.Rejected -> rejectReceipt(
            "KVP-003 graph proof",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun reportProof(): Kvp003GraphProof = when (
        val result = decodeKvp003GraphProof(boundary.readText(proofReportPath))
    ) {
        is Kvp003GraphProofResult.Complete -> result.proof
        is Kvp003GraphProofResult.Rejected -> rejectReceipt(
            "KVP-003 graph report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redExpectation(proof: Kvp003GraphProof) = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        mapOf(predecessor.receiptId.value to predecessor.digest.value),
        mapOf(
            "outcome" to "COMPLETE",
            "rejectedCases" to proof.rejectedCases.map { it.name }.sorted().joinToString(","),
        ),
        emptyMap(),
        taskId,
    )

    fun greenExpectation(red: AdmittedProofReceipt, proof: Kvp003GraphProof) =
        boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            mapOf(
                predecessor.receiptId.value to predecessor.digest.value,
                red.receiptId.value to red.digest.value,
            ),
            mapOf(
                "outcome" to "COMPLETE",
                "taskOrder" to proof.graph.order.joinToString(",") { it.value },
                "waveCount" to (proof.graph.waves.values.max() + 1).toString(),
            ),
            boundary.artifactDigests(listOf(proofReportPath)),
            taskId,
        )

    fun completionExpectation(red: AdmittedProofReceipt, green: AdmittedProofReceipt) =
        boundary.expectation(
            completionReceiptId,
            completionGateId,
            completionCommand,
            completionInputDigest,
            mapOf(
                predecessor.receiptId.value to predecessor.digest.value,
                red.receiptId.value to red.digest.value,
                green.receiptId.value to green.digest.value,
            ),
            mapOf("admittedGateReceiptCount" to "2", "outcome" to "COMPLETE"),
            emptyMap(),
            taskId,
        )
}

@UntrackedTask(because = "Executes and binds the exact KVP-003 RED gate")
abstract class RecordKvp003RedReceiptTask : Kvp003ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runGraphGate(graphRedCommand.get(), "*DeliveryGraphNegativeTest")
        revalidateExactHead(root, head)
        val contexts = graphContexts(head)
        issueReceiptAtBoundary(
            root,
            head,
            contexts.redExpectation(contexts.proof()),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-003 GREEN gate")
abstract class RecordKvp003GreenReceiptTask : Kvp003ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runGraphGate(graphGreenCommand.get(), "*DeliveryGraphTest")
        revalidateExactHead(root, head)
        val contexts = graphContexts(head)
        writeTextAtomically(
            proofReportFile.get().asFile.toPath(),
            encodeKvp003GraphProof(contexts.proof()),
        )
        revalidateExactHead(root, head)
        val red = contexts.boundary.admit(
            redReceiptFile.get().asFile.toPath(),
            contexts.redExpectation(contexts.proof()),
        )
        issueReceiptAtBoundary(
            root,
            head,
            contexts.greenExpectation(red, contexts.reportProof()),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Derives KVP-003 completion from admitted dependency and gate receipts")
abstract class DeriveKvp003CompletionReceiptTask : Kvp003ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = graphContexts(head)
        val proof = contexts.reportProof()
        val red = contexts.boundary.admit(
            redReceiptFile.get().asFile.toPath(),
            contexts.redExpectation(proof),
        )
        val green = contexts.boundary.admit(
            greenReceiptFile.get().asFile.toPath(),
            contexts.greenExpectation(red, proof),
        )
        issueReceiptAtBoundary(
            root,
            head,
            contexts.completionExpectation(red, green),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Re-admits the complete KVP-003 receipt closure at live Git HEAD")
abstract class VerifyKvp003CompletionReceiptTask : Kvp003ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = graphContexts(head)
        val proof = contexts.reportProof()
        val red = contexts.boundary.admit(
            redReceiptFile.get().asFile.toPath(),
            contexts.redExpectation(proof),
        )
        val green = contexts.boundary.admit(
            greenReceiptFile.get().asFile.toPath(),
            contexts.greenExpectation(red, proof),
        )
        val completion = contexts.boundary.admit(
            completionReceiptFile.get().asFile.toPath(),
            contexts.completionExpectation(red, green),
        )
        revalidateExactHead(root, head)
        logger.lifecycle(
            "KVP-003-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}
