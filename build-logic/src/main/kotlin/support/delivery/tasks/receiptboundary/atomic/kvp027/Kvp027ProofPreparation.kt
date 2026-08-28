package support.delivery

import java.nio.file.Path
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import org.gradle.util.GradleVersion

internal enum class Kvp027ProofDecision { REUSE, EXECUTE }

internal enum class Kvp027ProofPreparationFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    RELEVANT_INPUT_REJECTED,
    CASE_EXPECTATION_REJECTED,
}

internal sealed interface Kvp027ProofContextPreparation {
    data class Complete(val context: Kvp027ProofContext) : Kvp027ProofContextPreparation
    data class Rejected(val failure: Kvp027ProofPreparationFailure) :
        Kvp027ProofContextPreparation
}

internal sealed interface Kvp027ExistingProofAdmission {
    data class Complete(
        val report: AdmittedKvp027ProofReport,
        val receipt: AdmittedTaskProofReceipt,
    ) : Kvp027ExistingProofAdmission
    data object Rejected : Kvp027ExistingProofAdmission
}

@UntrackedTask(because = "Revalidates KVP-027's content closure before deciding reuse")
abstract class PrepareKvp027ProofTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp026ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp026ReportFile: RegularFileProperty
    @get:Internal abstract val proofReportFile: RegularFileProperty
    @get:Internal abstract val receiptFile: RegularFileProperty
    @get:OutputFile abstract val decisionFile: RegularFileProperty

    @TaskAction fun prepare() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp027ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp026ReceiptFile.get().asFile.toPath(),
            kvp026ReportFile.get().asFile.toPath(),
        )) {
            is Kvp027ProofContextPreparation.Complete -> prepared.context
            is Kvp027ProofContextPreparation.Rejected -> rejectPreparation(prepared.failure)
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
            admitKvp027ReportAndReceipt(
                report.bytes.toString(Charsets.UTF_8),
                receipt.bytes.toString(Charsets.UTF_8),
                context,
                head,
            ) is Kvp027ExistingProofAdmission.Complete
        ) Kvp027ProofDecision.REUSE else Kvp027ProofDecision.EXECUTE
        writeTextAtomically(decisionFile.get().asFile.toPath(), "${decision.name}\n")
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle("KVP-027 proof decision: {}", decision)
    }

    private fun rejectPreparation(failure: Kvp027ProofPreparationFailure): Nothing =
        throw GradleException("KVP-027 preparation rejected: $failure")
}

/**
 * Proof transition: graph packet, predecessor evidence, and repository observation ->
 * `Kvp027ProofContextPreparation`.
 *
 * Establishes the full reusable proof context in dependency order. Every expected boundary
 * failure is a closed [Kvp027ProofPreparationFailure].
 */
internal fun prepareKvp027ProofContext(
    exec: ExecOperations,
    root: Path,
    observedHead: DeliveryGeneration,
    packetPath: Path,
    kvp026Path: Path,
    kvp026ReportPath: Path,
): Kvp027ProofContextPreparation {
    val (expectedPacket, version) = canonicalKvp027Packet()
    val packetRaw = readRequiredKvp027File(packetPath) ?: return preparationRejected(
        Kvp027ProofPreparationFailure.PACKET_REJECTED,
    )
    val packet = when (val admitted = admitTaskPacket(packetRaw, expectedPacket, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp027ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp027Dependencies(
        packet.packet,
        kvp026Path,
        kvp026ReportPath,
    )) {
        is Kvp027DependencyAdmission.Complete -> admitted.dependencies
        is Kvp027DependencyAdmission.Rejected -> return preparationRejected(
            Kvp027ProofPreparationFailure.DEPENDENCY_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp027ImplementationScope(
        exec,
        root,
        dependencies.implementationBaseline,
        observedHead,
        packet.packet.task.allowedWrites,
        emptyList(),
        canonicalKvp028TaskPacket().task.allowedWrites,
    )) {
        is Kvp027ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp027ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp027ProofPreparationFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val relevantInputs = when (val admitted = admitKvp027RelevantInputs(
        exec,
        root,
        packet,
        dependencies,
    )) {
        is Kvp027RelevantInputAdmission.Complete -> admitted.digest
        is Kvp027RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp027ProofPreparationFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val cases = when (val admitted = expectedKvp027ProofCases(packet.packet)) {
        is Kvp027TestEvidenceAdmission.Complete -> admitted.evidence
        is Kvp027TestEvidenceAdmission.Rejected -> return preparationRejected(
            Kvp027ProofPreparationFailure.CASE_EXPECTATION_REJECTED,
        )
    }
    return Kvp027ProofContextPreparation.Complete(
        Kvp027ProofContext(
            version,
            packet,
            dependencies,
            cases,
            scope,
            relevantInputs,
            packet.packet.kvp027CommandDigest(),
            currentKvp027ToolchainDigest(),
            observedHead,
        ),
    )
}

internal fun admitKvp027ReportAndReceipt(
    reportRaw: String,
    receiptRaw: String,
    context: Kvp027ProofContext,
    currentHead: DeliveryGeneration,
): Kvp027ExistingProofAdmission {
    val report = when (val admitted = admitKvp027ProofReport(reportRaw, context)) {
        is Kvp027ProofReportAdmission.Complete -> admitted.report
        is Kvp027ProofReportAdmission.Rejected -> return Kvp027ExistingProofAdmission.Rejected
    }
    return when (val admitted = admitTaskProofReceipt(
        receiptRaw,
        context.receiptExpectation(report),
        currentHead,
    )) {
        is TaskProofReceiptAdmission.Complete -> if (
            admitted.receipt.observedRepositoryHead == report.observedRepositoryHead
        ) {
            Kvp027ExistingProofAdmission.Complete(report, admitted.receipt)
        } else {
            Kvp027ExistingProofAdmission.Rejected
        }
        is TaskProofReceiptAdmission.Rejected -> Kvp027ExistingProofAdmission.Rejected
    }
}

internal fun TaskPacket.kvp027CommandDigest() = TaskProofCommandDigest(
    sha256(canonicalJson(listOf(
        proofCommand.command,
        proofCommand.misuse.command,
        proofCommand.legalPath.command,
    ))).value,
)

internal fun currentKvp027ToolchainDigest() = ToolchainDigest(
    sha256(canonicalJson(mapOf(
        "gradle" to GradleVersion.current().version,
        "javaRuntime" to System.getProperty("java.runtime.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
    ))).value,
)

internal fun readRequiredKvp027File(path: Path): String? = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun preparationRejected(failure: Kvp027ProofPreparationFailure) =
    Kvp027ProofContextPreparation.Rejected(failure)
