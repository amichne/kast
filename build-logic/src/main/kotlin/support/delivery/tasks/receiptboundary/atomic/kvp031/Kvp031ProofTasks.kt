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

@UntrackedTask(because = "Projects the current canonical KVP-031 graph packet")
abstract class GenerateKvp031TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp031Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        when (val admitted = admitTaskPacket(raw, packet, version)) {
            is TaskPacketFileAdmission.Complete -> logger.lifecycle(
                "KVP-031 task packet admitted with definition digest {}",
                admitted.admitted.packet.taskDefinitionDigest.value,
            )
            is TaskPacketFileAdmission.Rejected -> throw GradleException(
                "KVP-031 task packet rejected: ${admitted.failure}",
            )
        }
    }
}

@UntrackedTask(because = "Admits KVP-031 proof content and emits or reuses one v2 receipt")
abstract class ProveKvp031Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp030ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp030ReportFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val testEvidenceFile: RegularFileProperty
    @get:InputFile abstract val decisionFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val observedHead = DeliveryGeneration(observeExactHead(root).value)
        val priorReport = readRequiredKvp031File(proofReportFile.get().asFile.toPath())
        val context = when (val prepared = prepareKvp031ProofContext(
            execOperations,
            root,
            observedHead,
            packetFile.get().asFile.toPath(),
            kvp030ReceiptFile.get().asFile.toPath(),
            kvp030ReportFile.get().asFile.toPath(),
            priorReport,
        )) {
            is Kvp031ProofContextPreparation.Complete -> prepared.context
            is Kvp031ProofContextPreparation.Rejected -> reject("context", prepared.failure)
        }
        val decision = when (readRequiredKvp031File(decisionFile.get().asFile.toPath())) {
            "${Kvp031ProofDecision.REUSE.name}\n" -> Kvp031ProofDecision.REUSE
            "${Kvp031ProofDecision.EXECUTE.name}\n" -> Kvp031ProofDecision.EXECUTE
            else -> reject("proof decision", "malformed")
        }
        if (decision == Kvp031ProofDecision.REUSE) {
            val existing = admitKvp031ReportAndReceipt(
                read(proofReportFile.get().asFile.toPath()),
                read(receiptFile.get().asFile.toPath()),
                context,
                observedHead,
            )
            if (existing !is Kvp031ExistingProofAdmission.Complete) {
                reject("reuse", "content closure changed after preparation")
            }
            revalidateExactHead(root, AuthorityGitRevision(observedHead.value))
            logComplete(existing.report, existing.receipt, "REUSED")
            return
        }
        val evidence = when (val admitted = admitKvp031TestEvidence(
            read(testEvidenceFile.get().asFile.toPath()),
            context.packet.packet,
        )) {
            is Kvp031TestEvidenceAdmission.Complete -> admitted.evidence
            is Kvp031TestEvidenceAdmission.Rejected -> reject("test evidence", admitted.failure)
        }
        if (evidence != context.cases) reject("test evidence", "case mismatch")
        val rawReport = canonicalKvp031ProofReport(context)
        writeTextAtomically(proofReportFile.get().asFile.toPath(), rawReport)
        val report = when (val admitted = admitKvp031ProofReport(
            read(proofReportFile.get().asFile.toPath()),
            context,
        )) {
            is Kvp031ProofReportAdmission.Complete -> admitted.report
            is Kvp031ProofReportAdmission.Rejected -> reject("proof report", admitted.failure)
        }
        val expectedOutput = context.packet.packet.task.outputs.single().path
        val observedOutput = root.relativize(
            proofReportFile.get().asFile.toPath().toAbsolutePath().normalize(),
        ).toString()
        if (expectedOutput != observedOutput) reject("proof output path", observedOutput)
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            observedHead,
            context.receiptExpectation(report),
            receiptFile.get().asFile.toPath(),
        )
        revalidateExactHead(root, AuthorityGitRevision(observedHead.value))
        logComplete(report, receipt, "EXECUTED")
    }

    private fun logComplete(
        report: AdmittedKvp031ProofReport,
        receipt: AdmittedTaskProofReceipt,
        disposition: String,
    ) {
        logger.lifecycle(
            "KVP-031 COMPLETE ({}): misuse={}, legal={}, output={}, receipt={}",
            disposition,
            report.observations.getValue("misuseOutcome"),
            report.observations.getValue("legalPathOutcome"),
            report.outputDigest.value,
            receipt.digest.value,
        )
    }

    private fun read(path: Path): String = readRequiredKvp031File(path)
        ?: reject("bounded file read", path)

    private fun reject(owner: String, failure: Any): Nothing =
        throw GradleException("KVP-031 $owner rejected: $failure")
}

internal fun canonicalKvp031Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-031"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-031 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

/** Returns the admitted graph-owned KVP-031 packet for Gradle test-task configuration. */
fun canonicalKvp031TaskPacket(): TaskPacket = canonicalKvp031Packet().first
