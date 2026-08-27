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

internal enum class Kvp029ProofDecision { REUSE, EXECUTE }

internal enum class Kvp029ProofPreparationFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    RELEVANT_INPUT_REJECTED,
    CASE_EXPECTATION_REJECTED,
}

internal sealed interface Kvp029ProofContextPreparation {
    data class Complete(val context: Kvp029ProofContext) : Kvp029ProofContextPreparation
    data class Rejected(val failure: Kvp029ProofPreparationFailure) :
        Kvp029ProofContextPreparation
}

internal sealed interface Kvp029ExistingProofAdmission {
    data class Complete(
        val report: AdmittedKvp029ProofReport,
        val receipt: AdmittedTaskProofReceipt,
    ) : Kvp029ExistingProofAdmission
    data object Rejected : Kvp029ExistingProofAdmission
}

@UntrackedTask(because = "Revalidates KVP-029's content closure before deciding reuse")
abstract class PrepareKvp029ProofTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp021ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp023ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp028ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp028ReportFile: RegularFileProperty
    @get:Internal abstract val proofReportFile: RegularFileProperty
    @get:Internal abstract val receiptFile: RegularFileProperty
    @get:OutputFile abstract val decisionFile: RegularFileProperty

    @TaskAction fun prepare() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp029ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp021ReceiptFile.get().asFile.toPath(),
            kvp023ReceiptFile.get().asFile.toPath(),
            kvp028ReceiptFile.get().asFile.toPath(),
            kvp028ReportFile.get().asFile.toPath(),
        )) {
            is Kvp029ProofContextPreparation.Complete -> prepared.context
            is Kvp029ProofContextPreparation.Rejected -> rejectPreparation(prepared.failure)
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
            admitKvp029ReportAndReceipt(
                report.bytes.toString(Charsets.UTF_8),
                receipt.bytes.toString(Charsets.UTF_8),
                context,
                head,
            ) is Kvp029ExistingProofAdmission.Complete
        ) Kvp029ProofDecision.REUSE else Kvp029ProofDecision.EXECUTE
        writeTextAtomically(decisionFile.get().asFile.toPath(), "${decision.name}\n")
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle("KVP-029 proof decision: {}", decision)
    }

    private fun rejectPreparation(failure: Kvp029ProofPreparationFailure): Nothing =
        throw GradleException("KVP-029 preparation rejected: $failure")
}

/**
 * Proof transition: graph packet, predecessor evidence, and repository observation ->
 * `Kvp029ProofContextPreparation`.
 *
 * Establishes the full reusable proof context in dependency order. Every expected boundary
 * failure is a closed [Kvp029ProofPreparationFailure].
 */
internal fun prepareKvp029ProofContext(
    exec: ExecOperations,
    root: Path,
    observedHead: DeliveryGeneration,
    packetPath: Path,
    kvp021Path: Path,
    kvp023Path: Path,
    kvp028Path: Path,
    kvp028ReportPath: Path,
): Kvp029ProofContextPreparation {
    val (expectedPacket, version) = canonicalKvp029Packet()
    val packetRaw = readRequiredKvp029File(packetPath) ?: return preparationRejected(
        Kvp029ProofPreparationFailure.PACKET_REJECTED,
    )
    val packet = when (val admitted = admitTaskPacket(packetRaw, expectedPacket, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp029ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp029Dependencies(
        packet.packet,
        kvp021Path,
        kvp023Path,
        kvp028Path,
        kvp028ReportPath,
    )) {
        is Kvp029DependencyAdmission.Complete -> admitted.dependencies
        is Kvp029DependencyAdmission.Rejected -> return preparationRejected(
            Kvp029ProofPreparationFailure.DEPENDENCY_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp029ImplementationScope(
        exec,
        root,
        dependencies.implementationBaseline,
        observedHead,
        packet.packet.task.allowedWrites,
        emptyList(),
    )) {
        is Kvp029ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp029ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp029ProofPreparationFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val relevantInputs = when (val admitted = admitKvp029RelevantInputs(
        exec,
        root,
        packet,
        dependencies,
    )) {
        is Kvp029RelevantInputAdmission.Complete -> admitted.digest
        is Kvp029RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp029ProofPreparationFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val cases = when (val admitted = expectedKvp029ProofCases(packet.packet)) {
        is Kvp029TestEvidenceAdmission.Complete -> admitted.evidence
        is Kvp029TestEvidenceAdmission.Rejected -> return preparationRejected(
            Kvp029ProofPreparationFailure.CASE_EXPECTATION_REJECTED,
        )
    }
    return Kvp029ProofContextPreparation.Complete(
        Kvp029ProofContext(
            version,
            packet,
            dependencies,
            cases,
            scope,
            relevantInputs,
            packet.packet.kvp029CommandDigest(),
            currentKvp029ToolchainDigest(),
            observedHead,
        ),
    )
}

internal fun admitKvp029ReportAndReceipt(
    reportRaw: String,
    receiptRaw: String,
    context: Kvp029ProofContext,
    currentHead: DeliveryGeneration,
): Kvp029ExistingProofAdmission {
    val report = when (val admitted = admitKvp029ProofReport(reportRaw, context)) {
        is Kvp029ProofReportAdmission.Complete -> admitted.report
        is Kvp029ProofReportAdmission.Rejected -> return Kvp029ExistingProofAdmission.Rejected
    }
    return when (val admitted = admitTaskProofReceipt(
        receiptRaw,
        context.receiptExpectation(report),
        currentHead,
    )) {
        is TaskProofReceiptAdmission.Complete -> if (
            admitted.receipt.observedRepositoryHead == report.observedRepositoryHead
        ) {
            Kvp029ExistingProofAdmission.Complete(report, admitted.receipt)
        } else {
            Kvp029ExistingProofAdmission.Rejected
        }
        is TaskProofReceiptAdmission.Rejected -> Kvp029ExistingProofAdmission.Rejected
    }
}

internal fun TaskPacket.kvp029CommandDigest() = TaskProofCommandDigest(
    sha256(canonicalJson(listOf(
        proofCommand.command,
        proofCommand.misuse.command,
        proofCommand.legalPath.command,
    ))).value,
)

internal fun currentKvp029ToolchainDigest() = ToolchainDigest(
    sha256(canonicalJson(mapOf(
        "gradle" to GradleVersion.current().version,
        "javaRuntime" to System.getProperty("java.runtime.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
    ))).value,
)

internal fun readRequiredKvp029File(path: Path): String? = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun preparationRejected(failure: Kvp029ProofPreparationFailure) =
    Kvp029ProofContextPreparation.Rejected(failure)
