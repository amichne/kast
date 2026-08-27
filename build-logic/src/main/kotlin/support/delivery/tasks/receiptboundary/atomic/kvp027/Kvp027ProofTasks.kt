package support.delivery

import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import javax.inject.Inject

@UntrackedTask(because = "Projects the current canonical KVP-027 graph packet")
abstract class GenerateKvp027TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp027Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        when (val admitted = admitTaskPacket(raw, packet, version)) {
            is TaskPacketFileAdmission.Complete -> logger.lifecycle(
                "KVP-027 task packet admitted with definition digest {}",
                admitted.admitted.packet.taskDefinitionDigest.value,
            )
            is TaskPacketFileAdmission.Rejected -> throw GradleException(
                "KVP-027 task packet rejected: ${admitted.failure}",
            )
        }
    }
}

@UntrackedTask(because = "Admits KVP-027 proof content and emits or reuses one v2 receipt")
abstract class ProveKvp027Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp026ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp026ReportFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val testEvidenceFile: RegularFileProperty
    @get:InputFile abstract val decisionFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val observedHead = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp027ProofContext(
            execOperations,
            root,
            observedHead,
            packetFile.get().asFile.toPath(),
            kvp026ReceiptFile.get().asFile.toPath(),
            kvp026ReportFile.get().asFile.toPath(),
        )) {
            is Kvp027ProofContextPreparation.Complete -> prepared.context
            is Kvp027ProofContextPreparation.Rejected -> reject("context", prepared.failure)
        }
        val decision = when (readRequiredKvp027File(decisionFile.get().asFile.toPath())) {
            "${Kvp027ProofDecision.REUSE.name}\n" -> Kvp027ProofDecision.REUSE
            "${Kvp027ProofDecision.EXECUTE.name}\n" -> Kvp027ProofDecision.EXECUTE
            else -> reject("proof decision", "malformed")
        }
        if (decision == Kvp027ProofDecision.REUSE) {
            val existing = admitKvp027ReportAndReceipt(
                read(proofReportFile.get().asFile.toPath()),
                read(receiptFile.get().asFile.toPath()),
                context,
                observedHead,
            )
            if (existing !is Kvp027ExistingProofAdmission.Complete) {
                reject("reuse", "content closure changed after preparation")
            }
            revalidateExactHead(root, AuthorityGitRevision(observedHead.value))
            logComplete(existing.report, existing.receipt, "REUSED")
            return
        }
        val evidence = when (val admitted = admitKvp027TestEvidence(
            read(testEvidenceFile.get().asFile.toPath()),
            context.packet.packet,
        )) {
            is Kvp027TestEvidenceAdmission.Complete -> admitted.evidence
            is Kvp027TestEvidenceAdmission.Rejected -> reject("test evidence", admitted.failure)
        }
        if (evidence != context.cases) reject("test evidence", "case mismatch")
        val rawReport = canonicalKvp027ProofReport(context)
        writeTextAtomically(proofReportFile.get().asFile.toPath(), rawReport)
        val report = when (val admitted = admitKvp027ProofReport(
            read(proofReportFile.get().asFile.toPath()),
            context,
        )) {
            is Kvp027ProofReportAdmission.Complete -> admitted.report
            is Kvp027ProofReportAdmission.Rejected -> reject("proof report", admitted.failure)
        }
        val expectedOutput = context.packet.packet.task.outputs.single().path
        val observedOutput = root.relativize(
            proofReportFile.get().asFile.toPath().toAbsolutePath().normalize(),
        ).toString()
        if (expectedOutput != observedOutput) reject("proof output path", observedOutput)
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            observedHead,
            context.receiptExpectation(),
            receiptFile.get().asFile.toPath(),
        )
        revalidateExactHead(root, AuthorityGitRevision(observedHead.value))
        logComplete(report, receipt, "EXECUTED")
    }

    private fun logComplete(
        report: AdmittedKvp027ProofReport,
        receipt: AdmittedTaskProofReceipt,
        disposition: String,
    ) {
        logger.lifecycle(
            "KVP-027 COMPLETE ({}): misuse={}, legal={}, output={}, receipt={}",
            disposition,
            report.observations.getValue("misuseOutcome"),
            report.observations.getValue("legalPathOutcome"),
            report.outputDigest.value,
            receipt.digest.value,
        )
    }

    private fun read(path: Path): String = readRequiredKvp027File(path)
        ?: reject("bounded file read", path)

    private fun reject(owner: String, failure: Any): Nothing =
        throw GradleException("KVP-027 $owner rejected: $failure")
}

internal fun canonicalKvp027Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-027"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-027 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

/** Returns the admitted graph-owned KVP-027 packet for Gradle test-task configuration. */
fun canonicalKvp027TaskPacket(): TaskPacket = canonicalKvp027Packet().first
