package support.delivery

import java.nio.file.Path
import javax.inject.Inject
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

internal enum class Kvp025ProofDecision { REUSE, EXECUTE }

internal sealed interface Kvp025ProofDecisionAdmission {
    data class Complete(val decision: Kvp025ProofDecision) : Kvp025ProofDecisionAdmission
    data object Rejected : Kvp025ProofDecisionAdmission
}

@UntrackedTask(because = "Revalidates KVP-025's content closure before deciding reuse")
abstract class PrepareKvp025ProofTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val predecessorReceiptFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val receiptFile: RegularFileProperty
    @get:OutputFile abstract val decisionFile: RegularFileProperty

    @TaskAction fun prepare() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val observedHead = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp025ProofContext(
            execOperations,
            root,
            observedHead,
            packetFile.get().asFile.toPath(),
            predecessorReceiptFile.get().asFile.toPath(),
        )) {
            is Kvp025ProofContextPreparation.Complete -> prepared.context
            is Kvp025ProofContextPreparation.Rejected ->
                rejectKvp025Preparation("context", prepared.failure)
        }
        val report = readBoundaryFile(
            proofReportFile.get().asFile.toPath(),
            MAX_RECEIPT_EVIDENCE_BYTES,
        )
        val receipt = readBoundaryFile(
            receiptFile.get().asFile.toPath(),
            MAX_RECEIPT_EVIDENCE_BYTES,
        )
        val decision = if (
            report is BoundaryFileRead.Complete && receipt is BoundaryFileRead.Complete &&
            admitKvp025ReportAndReceipt(
                report.bytes.toString(Charsets.UTF_8),
                receipt.bytes.toString(Charsets.UTF_8),
                context,
                observedHead,
            ) is Kvp025ExistingProofAdmission.Complete
        ) Kvp025ProofDecision.REUSE else Kvp025ProofDecision.EXECUTE
        writeTextAtomically(decisionFile.get().asFile.toPath(), encodeKvp025ProofDecision(decision))
        revalidateExactHead(root, AuthorityGitRevision(observedHead.value))
        logger.lifecycle("KVP-025 proof decision: {}", decision)
    }
}

internal data class PreparedKvp025ProofContext(
    val packet: AdmittedTaskPacketFile,
    val predecessor: AdmittedLegacyReceiptPrefix,
    val report: Kvp025ProofReportContext,
)

internal enum class Kvp025ProofPreparationFailure {
    PACKET_REJECTED,
    PREDECESSOR_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    RELEVANT_INPUT_REJECTED,
    CASE_EXPECTATION_REJECTED,
}

internal sealed interface Kvp025ProofContextPreparation {
    data class Complete(val context: PreparedKvp025ProofContext) : Kvp025ProofContextPreparation
    data class Rejected(val failure: Kvp025ProofPreparationFailure) :
        Kvp025ProofContextPreparation
}

internal sealed interface Kvp025ExistingProofAdmission {
    data class Complete(
        val report: AdmittedKvp025ProofReport,
        val receipt: AdmittedTaskProofReceipt,
    ) : Kvp025ExistingProofAdmission
    data object Rejected : Kvp025ExistingProofAdmission
}

/**
 * Proof transition: KVP-025 packet/predecessor paths plus repository observation ->
 * `Kvp025ProofContextPreparation`.
 *
 * Establishes the admitted graph packet, pinned predecessor, task-scoped implementation delta,
 * complete relevant inputs, case expectation, command, and toolchain. Every expected rejection is
 * finite [Kvp025ProofPreparationFailure]; raw Git and filesystem values remain at their boundaries.
 */
internal fun prepareKvp025ProofContext(
    exec: ExecOperations,
    root: Path,
    observedHead: DeliveryGeneration,
    packetPath: Path,
    predecessorPath: Path,
): Kvp025ProofContextPreparation {
    val (expectedPacket, programVersion) = canonicalKvp025Packet()
    val rawPacket = when (val read = readBoundaryFile(
        packetPath,
        MAX_RECEIPT_EVIDENCE_BYTES,
    )) {
        is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
        is BoundaryFileRead.Rejected -> return rejectedPreparation(
            Kvp025ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val packet = when (val admitted = admitTaskPacket(
        rawPacket,
        expectedPacket,
        programVersion,
    )) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return rejectedPreparation(
            Kvp025ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val predecessor = when (val admitted = admitLegacyKvp024Prefix(predecessorPath)) {
        is LegacyReceiptPrefixFileAdmission.Complete -> admitted.prefix
        is LegacyReceiptPrefixFileAdmission.Rejected -> return rejectedPreparation(
            Kvp025ProofPreparationFailure.PREDECESSOR_REJECTED,
        )
    }
    val implementation = when (val admitted = admitKvp025ImplementationScope(
        exec,
        root,
        predecessor.observedRepositoryHead,
        observedHead,
        packet.packet.task.allowedWrites,
        canonicalKvp026TaskPacket().task.allowedWrites +
            canonicalKvp027TaskPacket().task.allowedWrites,
        canonicalKvp026TaskPacket().task.allowedWrites,
    )) {
        is Kvp025ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp025ImplementationScopeAdmission.Rejected -> return rejectedPreparation(
            Kvp025ProofPreparationFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val relevantInputs = when (val admitted = admitKvp025RelevantInputs(
        exec,
        root,
        packet,
        predecessor,
    )) {
        is Kvp025RelevantInputAdmission.Complete -> admitted.digest
        is Kvp025RelevantInputAdmission.Rejected -> return rejectedPreparation(
            Kvp025ProofPreparationFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val cases = when (val admitted = expectedKvp025ProofCases(packet.packet)) {
        is Kvp025ProofCaseExpectationAdmission.Complete -> admitted.expectation
        is Kvp025ProofCaseExpectationAdmission.Rejected -> return rejectedPreparation(
            Kvp025ProofPreparationFailure.CASE_EXPECTATION_REJECTED,
        )
    }
    return Kvp025ProofContextPreparation.Complete(PreparedKvp025ProofContext(
        packet,
        predecessor,
        Kvp025ProofReportContext(
            programVersion,
            packet,
            predecessor,
            cases,
            implementation,
            relevantInputs,
            packet.packet.commandDigest(),
            currentTaskProofToolchainDigest(),
            observedHead,
        ),
    ))
}

/**
 * Proof transition: stored report/receipt bytes plus current prepared closure ->
 * `Kvp025ExistingProofAdmission`.
 *
 * Establishes report equality, complete receipt closure, self integrity, and the selected head
 * policy. Any malformed or mismatched stored proof is the closed Rejected state.
 */
internal fun admitKvp025ReportAndReceipt(
    reportRaw: String,
    receiptRaw: String,
    context: PreparedKvp025ProofContext,
    currentHead: DeliveryGeneration,
): Kvp025ExistingProofAdmission {
    val report = when (val admitted = admitKvp025ProofReport(reportRaw, context.report)) {
        is Kvp025ProofReportAdmission.Complete -> admitted.report
        is Kvp025ProofReportAdmission.Rejected -> return Kvp025ExistingProofAdmission.Rejected
    }
    return when (val admitted = admitTaskProofReceipt(
        receiptRaw,
        context.report.receiptExpectation(),
        currentHead,
    )) {
        is TaskProofReceiptAdmission.Complete ->
            Kvp025ExistingProofAdmission.Complete(report, admitted.receipt)
        is TaskProofReceiptAdmission.Rejected -> Kvp025ExistingProofAdmission.Rejected
    }
}

internal fun encodeKvp025ProofDecision(decision: Kvp025ProofDecision) = "${decision.name}\n"

internal fun admitKvp025ProofDecision(raw: String): Kvp025ProofDecisionAdmission =
    Kvp025ProofDecision.entries.singleOrNull { encodeKvp025ProofDecision(it) == raw }
        ?.let(Kvp025ProofDecisionAdmission::Complete)
        ?: Kvp025ProofDecisionAdmission.Rejected

internal fun readRequiredKvp025File(path: Path): String = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> rejectKvp025Preparation("bounded file read", read.failure)
}

private fun rejectKvp025Preparation(owner: String, failure: Any): Nothing =
    throw GradleException("KVP-025 $owner rejected: $failure")

private fun rejectedPreparation(failure: Kvp025ProofPreparationFailure) =
    Kvp025ProofContextPreparation.Rejected(failure)
