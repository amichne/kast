package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Executes and binds the exact KVP-009 RED gate")
abstract class RecordKvp009RedReceiptTask : Kvp009ReceiptTaskBase() {
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runFirewallGate(firewallRedCommand.get(), Kvp009GateCommand.RED)
        revalidateExactHead(root, head)
        val contexts = firewallContexts(head)
        issueReceiptAtBoundary(
            root,
            head,
            contexts.redExpectation(contexts.proof()),
            receiptFile.get().asFile.toPath(),
        )
    }
}

@UntrackedTask(because = "Executes and binds the exact KVP-009 GREEN gate")
abstract class RecordKvp009GreenReceiptTask : Kvp009ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun recordReceipt() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        runFirewallGate(firewallGreenCommand.get(), Kvp009GateCommand.GREEN)
        revalidateExactHead(root, head)
        val contexts = firewallContexts(head)
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

@UntrackedTask(because = "Derives KVP-009 completion from both dependencies and gate receipts")
abstract class DeriveKvp009CompletionReceiptTask : Kvp009ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun deriveCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = firewallContexts(head)
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

@UntrackedTask(because = "Re-admits the complete KVP-009 receipt closure at live Git HEAD")
abstract class VerifyKvp009CompletionReceiptTask : Kvp009ReceiptTaskBase() {
    @get:InputFile abstract val redReceiptFile: RegularFileProperty
    @get:InputFile abstract val greenReceiptFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:InputFile abstract val completionReceiptFile: RegularFileProperty

    @TaskAction fun verifyCompletion() {
        val root = repositoryRoot()
        val head = observeExactHead(root)
        val contexts = firewallContexts(head)
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
            "KVP-009-COMPLETE admitted at {} with receipt digest {}",
            completion.exactHead.value,
            completion.digest.value,
        )
    }
}
