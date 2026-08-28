package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Binds live Git HEAD and observed KVP-001 RED evidence")
abstract class RecordKvp001RedReceiptTask : Kvp001ReceiptTaskBase() {
    @get:OutputFile
    abstract val receiptFile: RegularFileProperty

    @TaskAction
    fun recordReceipt() {
        val root = repositoryRoot()
        val exactHead = observeExactHead(root)
        val context = context(exactHead)
        issueReceiptAtBoundary(
            root,
            exactHead,
            context.redExpectation(),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Binds live Git HEAD, admitted RED receipt, and KVP-001 GREEN evidence")
abstract class RecordKvp001GreenReceiptTask : Kvp001ReceiptTaskBase() {
    @get:InputFile
    abstract val redReceiptFile: RegularFileProperty

    @get:OutputFile
    abstract val receiptFile: RegularFileProperty

    @TaskAction
    fun recordReceipt() {
        val root = repositoryRoot()
        val exactHead = observeExactHead(root)
        val context = context(exactHead)
        val redReceipt = context.admit(
            redReceiptFile.get().asFile.toPath(),
            context.redExpectation(),
        )
        issueReceiptAtBoundary(
            root,
            exactHead,
            context.greenExpectation(redReceipt),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Derives KVP-001 completion from live admitted gate receipts")
abstract class DeriveKvp001CompletionReceiptTask : Kvp001ReceiptTaskBase() {
    @get:InputFile
    abstract val redReceiptFile: RegularFileProperty

    @get:InputFile
    abstract val greenReceiptFile: RegularFileProperty

    @get:OutputFile
    abstract val receiptFile: RegularFileProperty

    @TaskAction
    fun deriveCompletion() {
        val root = repositoryRoot()
        val exactHead = observeExactHead(root)
        val context = context(exactHead)
        val redReceipt = context.admit(
            redReceiptFile.get().asFile.toPath(),
            context.redExpectation(),
        )
        val greenReceipt = context.admit(
            greenReceiptFile.get().asFile.toPath(),
            context.greenExpectation(redReceipt),
        )
        issueReceiptAtBoundary(
            root,
            exactHead,
            context.completionExpectation(redReceipt, greenReceipt),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Re-admits the complete KVP-001 receipt closure at live Git HEAD")
abstract class VerifyKvp001CompletionReceiptTask : Kvp001ReceiptTaskBase() {
    @get:InputFile
    abstract val redReceiptFile: RegularFileProperty

    @get:InputFile
    abstract val greenReceiptFile: RegularFileProperty

    @get:InputFile
    abstract val completionReceiptFile: RegularFileProperty

    @TaskAction
    fun verifyCompletion() {
        val root = repositoryRoot()
        val exactHead = observeExactHead(root)
        val context = context(exactHead)
        val redReceipt = context.admit(
            redReceiptFile.get().asFile.toPath(),
            context.redExpectation(),
        )
        val greenReceipt = context.admit(
            greenReceiptFile.get().asFile.toPath(),
            context.greenExpectation(redReceipt),
        )
        val completion = context.admit(
            completionReceiptFile.get().asFile.toPath(),
            context.completionExpectation(redReceipt, greenReceipt),
        )
        revalidateExactHead(root, exactHead)
        logger.lifecycle(
            "KVP-001-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}
