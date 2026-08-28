package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Executes and binds the exact KVP-008 RED gate")
abstract class RecordKvp008RedReceiptTask : Kvp008ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runDeliveryStateGate(deliveryStateRedCommand.get(), Kvp008GateCommand.RED)
        revalidateExactHead(root, head)
        val contexts = deliveryStateContexts(head)
        issueReceiptAtBoundary(
            root,
            head,
            contexts.redExpectation(contexts.proof()),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-008 GREEN gate")
abstract class RecordKvp008GreenReceiptTask : Kvp008ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runDeliveryStateGate(deliveryStateGreenCommand.get(), Kvp008GateCommand.GREEN)
        revalidateExactHead(root, head)
        val contexts = deliveryStateContexts(head)
        val proof = contexts.proof()
        writeTextAtomically(
            proofReportFile.get().asFile.toPath(),
            encodeKvp008DeliveryStateProof(proof),
        )
        revalidateExactHead(root, head)
        val red = contexts.boundary.admit(
            redReceiptFile.get().asFile.toPath(),
            contexts.redExpectation(proof),
        )
        issueReceiptAtBoundary(
            root,
            head,
            contexts.greenExpectation(red, contexts.reportProof()),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Derives KVP-008 completion from predecessor and gate receipts")
abstract class DeriveKvp008CompletionReceiptTask : Kvp008ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = deliveryStateContexts(head)
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

@UntrackedTask(because = "Re-admits the complete KVP-008 receipt closure at live Git HEAD")
abstract class VerifyKvp008CompletionReceiptTask : Kvp008ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = deliveryStateContexts(head)
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
            "KVP-008-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}
