package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Executes and binds the exact KVP-013 RED gate")
abstract class RecordKvp013RedReceiptTask : Kvp013ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        afterEndpointGate(endpointRedCommand.get(), Kvp013GateCommand.RED) {
            revalidateExactHead(root, head)
            val contexts = endpointContexts(head)
            issueReceiptAtBoundary(root, head, contexts.redExpectation(), receiptFile.path())
        }
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-013 GREEN gate")
abstract class RecordKvp013GreenReceiptTask : Kvp013ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        afterEndpointGate(endpointGreenCommand.get(), Kvp013GateCommand.GREEN) {
            revalidateExactHead(root, head)
            val contexts = endpointContexts(head)
            val red = contexts.boundary.admit(redReceiptFile.path(), contexts.redExpectation())
            issueReceiptAtBoundary(
                root,
                head,
                contexts.greenExpectation(red, contexts.reportProof()),
                receiptFile.path(),
            )
        }
    }
}

@UntrackedTask(because = "Derives KVP-013 completion from both dependencies and gate receipts")
abstract class DeriveKvp013CompletionReceiptTask : Kvp013ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = endpointContexts(head)
        val red = contexts.boundary.admit(redReceiptFile.path(), contexts.redExpectation())
        val green = contexts.boundary.admit(
            greenReceiptFile.path(),
            contexts.greenExpectation(red, contexts.reportProof()),
        )
        issueReceiptAtBoundary(
            root,
            head,
            contexts.completionExpectation(red, green),
            receiptFile.path(),
        )
    }
}

@UntrackedTask(because = "Re-admits the complete KVP-013 receipt closure at live Git HEAD")
abstract class VerifyKvp013CompletionReceiptTask : Kvp013ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = endpointContexts(head)
        val red = contexts.boundary.admit(redReceiptFile.path(), contexts.redExpectation())
        val green = contexts.boundary.admit(
            greenReceiptFile.path(),
            contexts.greenExpectation(red, contexts.reportProof()),
        )
        val completion = contexts.boundary.admit(
            completionReceiptFile.path(),
            contexts.completionExpectation(red, green),
        )
        revalidateExactHead(root, head)
        logger.lifecycle(
            "KVP-013-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}

private fun RegularFileProperty.path() = get().asFile.toPath()
