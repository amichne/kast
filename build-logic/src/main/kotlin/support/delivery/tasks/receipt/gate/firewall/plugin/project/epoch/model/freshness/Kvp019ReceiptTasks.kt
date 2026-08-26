package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Executes and binds the exact KVP-019 RED gate")
abstract class RecordKvp019RedReceiptTask : Kvp019ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        afterFreshnessGate(freshnessRedCommand.get(), Kvp019GateCommand.RED) {
            revalidateExactHead(root, head)
            val contexts = freshnessContexts(head)
            issueReceiptAtBoundary(root, head, contexts.redExpectation(), receiptFile.path())
        }
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-019 GREEN gate")
abstract class RecordKvp019GreenReceiptTask : Kvp019ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        afterFreshnessGate(freshnessGreenCommand.get(), Kvp019GateCommand.GREEN) {
            revalidateExactHead(root, head)
            val contexts = freshnessContexts(head)
            val red = contexts.boundary.admit(redReceiptFile.path(), contexts.redExpectation())
            issueReceiptAtBoundary(root, head, contexts.greenExpectation(red), receiptFile.path())
        }
    }
}

@UntrackedTask(because = "Derives KVP-019 completion from dependencies and both gate receipts")
abstract class DeriveKvp019CompletionReceiptTask : Kvp019ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = freshnessContexts(head)
        val red = contexts.boundary.admit(redReceiptFile.path(), contexts.redExpectation())
        val green = contexts.boundary.admit(
            greenReceiptFile.path(),
            contexts.greenExpectation(red),
        )
        issueReceiptAtBoundary(
            root,
            head,
            contexts.completionExpectation(red, green),
            receiptFile.path(),
        )
    }
}

@UntrackedTask(because = "Re-admits the complete KVP-019 receipt closure at live Git HEAD")
abstract class VerifyKvp019CompletionReceiptTask : Kvp019ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = freshnessContexts(head)
        val red = contexts.boundary.admit(redReceiptFile.path(), contexts.redExpectation())
        val green = contexts.boundary.admit(
            greenReceiptFile.path(),
            contexts.greenExpectation(red),
        )
        val completion = contexts.boundary.admit(
            completionReceiptFile.path(),
            contexts.completionExpectation(red, green),
        )
        revalidateExactHead(root, head)
        logger.lifecycle(
            "KVP-019-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}

private fun RegularFileProperty.path() = get().asFile.toPath()
