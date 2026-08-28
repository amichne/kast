package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Admits and binds the exact completed KVP-021 RED Test task")
abstract class RecordKvp021RedReceiptTask : Kvp021ReceiptTaskBase() {
    @get:InputFile abstract val redGateEvidenceFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = cancellableContexts(head)
        val gate = contexts.redGateProof()
        issueReceiptAtBoundary(
            root,
            head,
            contexts.redExpectation(gate),
            receiptFile.path(),
        )
    }
}

@UntrackedTask(because = "Admits and binds the exact completed KVP-021 GREEN Test task")
abstract class RecordKvp021GreenReceiptTask : Kvp021ReceiptTaskBase() {
    @get:InputFile abstract val redGateEvidenceFile: RegularFileProperty
    @get:InputFile abstract val greenGateEvidenceFile: RegularFileProperty
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = cancellableContexts(head)
        val red = contexts.boundary.admit(
            redReceiptFile.path(),
            contexts.redExpectation(contexts.redGateProof()),
        )
        issueReceiptAtBoundary(
            root,
            head,
            contexts.greenExpectation(red, contexts.greenGateProof()),
            receiptFile.path(),
        )
    }
}

@UntrackedTask(because = "Derives KVP-021 completion from dependencies and both gate receipts")
abstract class DeriveKvp021CompletionReceiptTask : Kvp021ReceiptTaskBase() {
    @get:InputFile abstract val redGateEvidenceFile: RegularFileProperty
    @get:InputFile abstract val greenGateEvidenceFile: RegularFileProperty
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = cancellableContexts(head)
        val red = contexts.boundary.admit(
            redReceiptFile.path(),
            contexts.redExpectation(contexts.redGateProof()),
        )
        val green = contexts.boundary.admit(
            greenReceiptFile.path(),
            contexts.greenExpectation(red, contexts.greenGateProof()),
        )
        issueReceiptAtBoundary(
            root,
            head,
            contexts.completionExpectation(red, green),
            receiptFile.path(),
        )
    }
}

@UntrackedTask(because = "Re-admits the complete KVP-021 receipt closure at live Git HEAD")
abstract class VerifyKvp021CompletionReceiptTask : Kvp021ReceiptTaskBase() {
    @get:InputFile abstract val redGateEvidenceFile: RegularFileProperty
    @get:InputFile abstract val greenGateEvidenceFile: RegularFileProperty
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = cancellableContexts(head)
        val red = contexts.boundary.admit(
            redReceiptFile.path(),
            contexts.redExpectation(contexts.redGateProof()),
        )
        val green = contexts.boundary.admit(
            greenReceiptFile.path(),
            contexts.greenExpectation(red, contexts.greenGateProof()),
        )
        val completion = contexts.boundary.admit(
            completionReceiptFile.path(),
            contexts.completionExpectation(red, green),
        )
        revalidateExactHead(root, head)
        logger.lifecycle(
            "KVP-021-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}

private fun RegularFileProperty.path() = get().asFile.toPath()
