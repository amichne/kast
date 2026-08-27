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

internal enum class Kvp028ProofDecision { REUSE, EXECUTE }

internal enum class Kvp028ProofPreparationFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    RELEVANT_INPUT_REJECTED,
    CASE_EXPECTATION_REJECTED,
}

internal sealed interface Kvp028ProofContextPreparation {
    data class Complete(val context: Kvp028ProofContext) : Kvp028ProofContextPreparation
    data class Rejected(val failure: Kvp028ProofPreparationFailure) :
        Kvp028ProofContextPreparation
}

internal sealed interface Kvp028ExistingProofAdmission {
    data class Complete(
        val report: AdmittedKvp028ProofReport,
        val receipt: AdmittedTaskProofReceipt,
    ) : Kvp028ExistingProofAdmission
    data object Rejected : Kvp028ExistingProofAdmission
}

@UntrackedTask(because = "Revalidates KVP-028's content closure before deciding reuse")
abstract class PrepareKvp028ProofTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp023ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp026ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp026ReportFile: RegularFileProperty
    @get:Internal abstract val proofReportFile: RegularFileProperty
    @get:Internal abstract val receiptFile: RegularFileProperty
    @get:OutputFile abstract val decisionFile: RegularFileProperty

    @TaskAction fun prepare() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp028ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp023ReceiptFile.get().asFile.toPath(),
            kvp026ReceiptFile.get().asFile.toPath(),
            kvp026ReportFile.get().asFile.toPath(),
        )) {
            is Kvp028ProofContextPreparation.Complete -> prepared.context
            is Kvp028ProofContextPreparation.Rejected -> rejectPreparation(prepared.failure)
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
            admitKvp028ReportAndReceipt(
                report.bytes.toString(Charsets.UTF_8),
                receipt.bytes.toString(Charsets.UTF_8),
                context,
                head,
            ) is Kvp028ExistingProofAdmission.Complete
        ) Kvp028ProofDecision.REUSE else Kvp028ProofDecision.EXECUTE
        writeTextAtomically(decisionFile.get().asFile.toPath(), "${decision.name}\n")
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle("KVP-028 proof decision: {}", decision)
    }

    private fun rejectPreparation(failure: Kvp028ProofPreparationFailure): Nothing =
        throw GradleException("KVP-028 preparation rejected: $failure")
}

/**
 * Proof transition: graph packet, predecessor evidence, and repository observation ->
 * `Kvp028ProofContextPreparation`.
 *
 * Establishes the full reusable proof context in dependency order. Every expected boundary
 * failure is a closed [Kvp028ProofPreparationFailure].
 */
internal fun prepareKvp028ProofContext(
    exec: ExecOperations,
    root: Path,
    observedHead: DeliveryGeneration,
    packetPath: Path,
    kvp023Path: Path,
    kvp026Path: Path,
    kvp026ReportPath: Path,
): Kvp028ProofContextPreparation {
    val (expectedPacket, version) = canonicalKvp028Packet()
    val packetRaw = readRequiredKvp028File(packetPath) ?: return preparationRejected(
        Kvp028ProofPreparationFailure.PACKET_REJECTED,
    )
    val packet = when (val admitted = admitTaskPacket(packetRaw, expectedPacket, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp028ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp028Dependencies(
        packet.packet,
        kvp023Path,
        kvp026Path,
        kvp026ReportPath,
    )) {
        is Kvp028DependencyAdmission.Complete -> admitted.dependencies
        is Kvp028DependencyAdmission.Rejected -> return preparationRejected(
            Kvp028ProofPreparationFailure.DEPENDENCY_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp028ImplementationScope(
        exec,
        root,
        dependencies.implementationBaseline,
        observedHead,
        packet.packet.task.allowedWrites,
        canonicalKvp025TaskPacket().task.allowedWrites +
            canonicalKvp027TaskPacket().task.allowedWrites,
    )) {
        is Kvp028ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp028ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp028ProofPreparationFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val relevantInputs = when (val admitted = admitKvp028RelevantInputs(
        exec,
        root,
        packet,
        dependencies,
    )) {
        is Kvp028RelevantInputAdmission.Complete -> admitted.digest
        is Kvp028RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp028ProofPreparationFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val cases = when (val admitted = expectedKvp028ProofCases(packet.packet)) {
        is Kvp028TestEvidenceAdmission.Complete -> admitted.evidence
        is Kvp028TestEvidenceAdmission.Rejected -> return preparationRejected(
            Kvp028ProofPreparationFailure.CASE_EXPECTATION_REJECTED,
        )
    }
    return Kvp028ProofContextPreparation.Complete(
        Kvp028ProofContext(
            version,
            packet,
            dependencies,
            cases,
            scope,
            relevantInputs,
            packet.packet.kvp028CommandDigest(),
            currentKvp028ToolchainDigest(),
            observedHead,
        ),
    )
}

internal fun admitKvp028ReportAndReceipt(
    reportRaw: String,
    receiptRaw: String,
    context: Kvp028ProofContext,
    currentHead: DeliveryGeneration,
): Kvp028ExistingProofAdmission {
    val report = when (val admitted = admitKvp028ProofReport(reportRaw, context)) {
        is Kvp028ProofReportAdmission.Complete -> admitted.report
        is Kvp028ProofReportAdmission.Rejected -> return Kvp028ExistingProofAdmission.Rejected
    }
    return when (val admitted = admitTaskProofReceipt(
        receiptRaw,
        context.receiptExpectation(report),
        currentHead,
    )) {
        is TaskProofReceiptAdmission.Complete -> if (
            admitted.receipt.observedRepositoryHead == report.observedRepositoryHead
        ) {
            Kvp028ExistingProofAdmission.Complete(report, admitted.receipt)
        } else {
            Kvp028ExistingProofAdmission.Rejected
        }
        is TaskProofReceiptAdmission.Rejected -> Kvp028ExistingProofAdmission.Rejected
    }
}

internal fun TaskPacket.kvp028CommandDigest() = TaskProofCommandDigest(
    sha256(canonicalJson(listOf(
        proofCommand.command,
        proofCommand.misuse.command,
        proofCommand.legalPath.command,
    ))).value,
)

internal fun currentKvp028ToolchainDigest() = ToolchainDigest(
    sha256(canonicalJson(mapOf(
        "gradle" to GradleVersion.current().version,
        "javaRuntime" to System.getProperty("java.runtime.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
    ))).value,
)

internal fun readRequiredKvp028File(path: Path): String? = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun preparationRejected(failure: Kvp028ProofPreparationFailure) =
    Kvp028ProofContextPreparation.Rejected(failure)
