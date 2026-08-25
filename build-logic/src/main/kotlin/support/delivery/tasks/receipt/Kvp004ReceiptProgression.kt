package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

internal data class Kvp004ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val predecessors: List<AdmittedProofReceipt>,
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
    private val predecessorDigests = predecessors.associate {
        it.receiptId.value to it.digest.value
    }

    fun proof(): Kvp004ProgramProof = when (val result = deriveKvp004ProgramProof()) {
        is Kvp004ProgramProofResult.Complete -> result.proof
        is Kvp004ProgramProofResult.Rejected -> rejectReceipt(
            "KVP-004 program proof",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun reportProof(): Kvp004ProgramProof = when (
        val result = decodeKvp004ProgramProof(boundary.readText(proofReportPath))
    ) {
        is Kvp004ProgramProofResult.Complete -> result.proof
        is Kvp004ProgramProofResult.Rejected -> rejectReceipt(
            "KVP-004 program report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redExpectation(proof: Kvp004ProgramProof) = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        predecessorDigests,
        mapOf(
            "outcome" to "COMPLETE",
            "rejectedCases" to proof.rejectedCases.map { it.name }.sorted().joinToString(","),
        ),
        emptyMap(),
        taskId,
    )

    fun greenExpectation(red: AdmittedProofReceipt, proof: Kvp004ProgramProof) =
        boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "outcome" to "COMPLETE",
                "taskCount" to proof.program.program.tasks.size.toString(),
                "terminalTaskId" to proof.program.program.terminalTask.value,
                "waveCount" to (proof.program.waves.values.max() + 1).toString(),
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
            predecessorDigests + mapOf(
                red.receiptId.value to red.digest.value,
                green.receiptId.value to green.digest.value,
            ),
            mapOf("admittedGateReceiptCount" to "2", "outcome" to "COMPLETE"),
            emptyMap(),
            taskId,
        )
}

@UntrackedTask(because = "Executes and binds the exact KVP-004 RED gate")
abstract class RecordKvp004RedReceiptTask : Kvp004ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runProgramGate(programRedCommand.get(), "*KastVfsPassiveProgramNegativeTest")
        revalidateExactHead(root, head)
        val contexts = programContexts(head)
        issueReceiptAtBoundary(
            root,
            head,
            contexts.redExpectation(contexts.proof()),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-004 GREEN gate")
abstract class RecordKvp004GreenReceiptTask : Kvp004ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runProgramGate(programGreenCommand.get(), "*KastVfsPassiveReusedIndexProgramTest")
        revalidateExactHead(root, head)
        val contexts = programContexts(head)
        writeTextAtomically(
            proofReportFile.get().asFile.toPath(),
            encodeKvp004ProgramProof(contexts.proof()),
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

@UntrackedTask(because = "Derives KVP-004 completion from both dependencies and gate receipts")
abstract class DeriveKvp004CompletionReceiptTask : Kvp004ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = programContexts(head)
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

@UntrackedTask(because = "Re-admits the complete KVP-004 receipt closure at live Git HEAD")
abstract class VerifyKvp004CompletionReceiptTask : Kvp004ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = programContexts(head)
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
            "KVP-004-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}
