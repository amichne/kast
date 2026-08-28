package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Executes and binds the exact KVP-010 RED gate")
abstract class RecordKvp010RedReceiptTask : Kvp010ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runStandalonePluginGate(standalonePluginRedCommand.get(), Kvp010GateCommand.RED)
        revalidateExactHead(root, head)
        val contexts = standalonePluginContexts(head)
        issueReceiptAtBoundary(
            root,
            head,
            contexts.redExpectation(contexts.negativeProof()),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-010 GREEN gate")
abstract class RecordKvp010GreenReceiptTask : Kvp010ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runStandalonePluginGate(standalonePluginGreenCommand.get(), Kvp010GateCommand.GREEN)
        revalidateExactHead(root, head)
        val contexts = standalonePluginContexts(head)
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

@UntrackedTask(because = "Derives KVP-010 completion from predecessor and both gate receipts")
abstract class DeriveKvp010CompletionReceiptTask : Kvp010ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = standalonePluginContexts(head)
        val red = contexts.boundary.admit(
            redReceiptFile.get().asFile.toPath(),
            contexts.redExpectation(contexts.negativeProof()),
        )
        val green = contexts.boundary.admit(
            greenReceiptFile.get().asFile.toPath(),
            contexts.greenExpectation(red, contexts.reportProof()),
        )
        issueReceiptAtBoundary(
            root,
            head,
            contexts.completionExpectation(red, green),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Re-admits the complete KVP-010 receipt closure at live Git HEAD")
abstract class VerifyKvp010CompletionReceiptTask : Kvp010ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = standalonePluginContexts(head)
        val red = contexts.boundary.admit(
            redReceiptFile.get().asFile.toPath(),
            contexts.redExpectation(contexts.negativeProof()),
        )
        val green = contexts.boundary.admit(
            greenReceiptFile.get().asFile.toPath(),
            contexts.greenExpectation(red, contexts.reportProof()),
        )
        val completion = contexts.boundary.admit(
            completionReceiptFile.get().asFile.toPath(),
            contexts.completionExpectation(red, green),
        )
        revalidateExactHead(root, head)
        logger.lifecycle(
            "KVP-010-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}
