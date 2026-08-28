package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Executes and binds the exact KVP-020 RED gate")
abstract class RecordKvp020RedReceiptTask : Kvp020ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        afterSingleFlightGate(singleFlightRedCommand.get(), Kvp020GateCommand.RED) {
            revalidateExactHead(root, head)
            val contexts = singleFlightContexts(head)
            issueReceiptAtBoundary(root, head, contexts.redExpectation(), receiptFile.path())
        }
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-020 GREEN gate")
abstract class RecordKvp020GreenReceiptTask : Kvp020ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        afterSingleFlightGate(singleFlightGreenCommand.get(), Kvp020GateCommand.GREEN) {
            revalidateExactHead(root, head)
            val contexts = singleFlightContexts(head)
            val red = contexts.boundary.admit(redReceiptFile.path(), contexts.redExpectation())
            issueReceiptAtBoundary(root, head, contexts.greenExpectation(red), receiptFile.path())
        }
    }
}

@UntrackedTask(because = "Derives KVP-020 completion from dependencies and both gate receipts")
abstract class DeriveKvp020CompletionReceiptTask : Kvp020ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = singleFlightContexts(head)
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

@UntrackedTask(because = "Re-admits the complete KVP-020 receipt closure at live Git HEAD")
abstract class VerifyKvp020CompletionReceiptTask : Kvp020ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = singleFlightContexts(head)
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
            "KVP-020-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}

private fun RegularFileProperty.path() = get().asFile.toPath()
