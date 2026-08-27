package support.delivery

import java.nio.file.Path
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

@UntrackedTask(because = "Projects the current canonical KVP-025 graph packet")
abstract class GenerateKvp025TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp025Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        when (val admitted = admitTaskPacket(raw, packet, version)) {
            is TaskPacketFileAdmission.Complete -> logger.lifecycle(
                "KVP-025 task packet admitted with definition digest {}",
                admitted.admitted.packet.taskDefinitionDigest.value,
            )
            is TaskPacketFileAdmission.Rejected -> throw GradleException(
                "KVP-025 task packet rejected: ${admitted.failure}",
            )
        }
    }
}

@UntrackedTask(because = "Admits KVP-025 proof content and emits or reuses one v2 receipt")
abstract class ProveKvp025Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val predecessorReceiptFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val testEvidenceFile: RegularFileProperty
    @get:InputFile abstract val decisionFile: RegularFileProperty
    @get:OutputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val observedHead = DeliveryGeneration(observeExactHead(root).value)
        val prepared = when (val result = prepareKvp025ProofContext(
            execOperations,
            root,
            observedHead,
            packetFile.get().asFile.toPath(),
            predecessorReceiptFile.get().asFile.toPath(),
            receiptFile.get().asFile.toPath(),
        )) {
            is Kvp025ProofContextPreparation.Complete -> result.context
            is Kvp025ProofContextPreparation.Rejected -> reject("context", result.failure)
        }
        val decision = when (val admitted = admitKvp025ProofDecision(
            read(decisionFile.get().asFile.toPath()),
        )) {
            is Kvp025ProofDecisionAdmission.Complete -> admitted.decision
            Kvp025ProofDecisionAdmission.Rejected -> reject("proof decision", "malformed")
        }
        if (decision == Kvp025ProofDecision.REUSE) {
            val existing = admitKvp025ReportAndReceipt(
                read(proofReportFile.get().asFile.toPath()),
                read(receiptFile.get().asFile.toPath()),
                prepared,
                observedHead,
            )
            if (existing !is Kvp025ExistingProofAdmission.Complete) {
                reject("reuse", "content closure changed after preparation")
            }
            revalidateExactHead(root, AuthorityGitRevision(observedHead.value))
            logComplete(existing.report, existing.receipt, "REUSED")
            return
        }
        val testEvidence = admitTestEvidence(prepared.packet.packet)
        if (testEvidence.asCaseExpectation() != prepared.report.caseExpectation) {
            reject("test evidence", Kvp025TestEvidenceFailure.CASE_MISMATCH)
        }
        val context = prepared.report
        val rawReport = canonicalKvp025ProofReport(context)
        writeTextAtomically(proofReportFile.get().asFile.toPath(), rawReport)
        val report = when (val admitted = admitKvp025ProofReport(
            read(proofReportFile.get().asFile.toPath()),
            context,
        )) {
            is Kvp025ProofReportAdmission.Complete -> admitted.report
            is Kvp025ProofReportAdmission.Rejected -> reject("proof report", admitted.failure)
        }
        val expectedOutputPath = prepared.packet.packet.task.outputs.single().path
        if (expectedOutputPath != root.relativize(
                proofReportFile.get().asFile.toPath().toAbsolutePath().normalize(),
            ).toString()
        ) reject("proof output path", Kvp025ProofReportFailure.REPORT_MISMATCH)
        val expectation = context.receiptExpectation()
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            observedHead,
            expectation,
            receiptFile.get().asFile.toPath(),
        )
        revalidateExactHead(root, AuthorityGitRevision(observedHead.value))
        logComplete(report, receipt, "EXECUTED")
    }

    private fun logComplete(
        report: AdmittedKvp025ProofReport,
        receipt: AdmittedTaskProofReceipt,
        disposition: String,
    ) {
        logger.lifecycle(
            "KVP-025 COMPLETE ({}): misuse={}, legal={}, output={}, receipt={}",
            disposition,
            report.observations.getValue("misuseOutcome"),
            report.observations.getValue("legalPathOutcome"),
            report.outputDigest.value,
            receipt.digest.value,
        )
    }

    private fun admitTestEvidence(packet: TaskPacket): AdmittedKvp025TestEvidence = when (
        val admitted = admitKvp025TestEvidence(read(testEvidenceFile.get().asFile.toPath()), packet)
    ) {
        is Kvp025TestEvidenceAdmission.Complete -> admitted.evidence
        is Kvp025TestEvidenceAdmission.Rejected -> reject("test evidence", admitted.failure)
    }

    private fun read(path: Path): String = when (
        val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
    ) {
        is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
        is BoundaryFileRead.Rejected -> reject("bounded file read", read.failure)
    }

    private fun reject(owner: String, failure: Any): Nothing =
        throw GradleException("KVP-025 $owner rejected: $failure")
}

internal fun canonicalKvp025Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val validated = KastVfsPassiveReusedIndexProgram.validated
    val packet = when (val admitted = validated.packet(TaskId("KVP-025"))) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-025 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

/** Returns the admitted graph-owned KVP-025 packet for Gradle test-task configuration. */
fun canonicalKvp025TaskPacket(): TaskPacket = canonicalKvp025Packet().first
