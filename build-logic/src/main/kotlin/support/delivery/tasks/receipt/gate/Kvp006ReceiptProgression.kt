package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Executes and binds the exact KVP-006 RED gate")
abstract class RecordKvp006RedReceiptTask : Kvp006ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runGateGraphGate(gateGraphRedCommand.get(), Kvp006GateCommand.RED)
        revalidateExactHead(root, head)
        val contexts = gateGraphContexts(head)
        issueReceiptAtBoundary(
            root,
            head,
            contexts.redExpectation(contexts.negativeProof()),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-006 GREEN gate")
abstract class RecordKvp006GreenReceiptTask : Kvp006ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runGateGraphGate(gateGraphGreenCommand.get(), Kvp006GateCommand.GREEN)
        revalidateExactHead(root, head)
        val contexts = gateGraphContexts(head)
        val red = contexts.boundary.admit(
            redReceiptFile.get().asFile.toPath(),
            contexts.redExpectation(contexts.negativeProof()),
        )
        issueReceiptAtBoundary(
            root,
            head,
            contexts.greenExpectation(red, contexts.reportProof()),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Derives KVP-006 completion from both dependencies and gate receipts")
abstract class DeriveKvp006CompletionReceiptTask : Kvp006ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = gateGraphContexts(head)
        val negativeProof = contexts.negativeProof()
        val proof = contexts.reportProof()
        val red = contexts.boundary.admit(
            redReceiptFile.get().asFile.toPath(),
            contexts.redExpectation(negativeProof),
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

@UntrackedTask(because = "Re-admits the complete KVP-006 receipt closure at live Git HEAD")
abstract class VerifyKvp006CompletionReceiptTask : Kvp006ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = gateGraphContexts(head)
        val negativeProof = contexts.negativeProof()
        val proof = contexts.reportProof()
        val red = contexts.boundary.admit(
            redReceiptFile.get().asFile.toPath(),
            contexts.redExpectation(negativeProof),
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
            "KVP-006-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}
