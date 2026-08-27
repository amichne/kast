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
import org.gradle.util.GradleVersion

internal enum class Kvp026ProofDecision { REUSE, EXECUTE }

internal enum class Kvp026ProofPreparationFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    RELEVANT_INPUT_REJECTED,
    CASE_EXPECTATION_REJECTED,
}

internal sealed interface Kvp026ProofContextPreparation {
    data class Complete(val context: Kvp026ProofContext) : Kvp026ProofContextPreparation
    data class Rejected(val failure: Kvp026ProofPreparationFailure) :
        Kvp026ProofContextPreparation
}

internal sealed interface Kvp026ExistingProofAdmission {
    data class Complete(
        val report: AdmittedKvp026ProofReport,
        val receipt: AdmittedTaskProofReceipt,
    ) : Kvp026ExistingProofAdmission
    data object Rejected : Kvp026ExistingProofAdmission
}

@UntrackedTask(because = "Revalidates KVP-026's content closure before deciding reuse")
abstract class PrepareKvp026ProofTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp013ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp024ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReportFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val receiptFile: RegularFileProperty
    @get:OutputFile abstract val decisionFile: RegularFileProperty

    @TaskAction fun prepare() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp026ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp013ReceiptFile.get().asFile.toPath(),
            kvp024ReceiptFile.get().asFile.toPath(),
            kvp025ReceiptFile.get().asFile.toPath(),
            kvp025ReportFile.get().asFile.toPath(),
        )) {
            is Kvp026ProofContextPreparation.Complete -> prepared.context
            is Kvp026ProofContextPreparation.Rejected -> rejectPreparation(prepared.failure)
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
            admitKvp026ReportAndReceipt(
                report.bytes.toString(Charsets.UTF_8),
                receipt.bytes.toString(Charsets.UTF_8),
                context,
                head,
            ) is Kvp026ExistingProofAdmission.Complete
        ) Kvp026ProofDecision.REUSE else Kvp026ProofDecision.EXECUTE
        writeTextAtomically(decisionFile.get().asFile.toPath(), "${decision.name}\n")
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle("KVP-026 proof decision: {}", decision)
    }

    private fun rejectPreparation(failure: Kvp026ProofPreparationFailure): Nothing =
        throw GradleException("KVP-026 preparation rejected: $failure")
}

/**
 * Proof transition: graph packet, predecessor evidence, and repository observation ->
 * `Kvp026ProofContextPreparation`.
 *
 * Establishes the full reusable proof context in dependency order. Every expected boundary
 * failure is a closed [Kvp026ProofPreparationFailure].
 */
internal fun prepareKvp026ProofContext(
    exec: ExecOperations,
    root: Path,
    observedHead: DeliveryGeneration,
    packetPath: Path,
    kvp013Path: Path,
    kvp024Path: Path,
    kvp025Path: Path,
    kvp025ReportPath: Path,
): Kvp026ProofContextPreparation {
    val (expectedPacket, version) = canonicalKvp026Packet()
    val packetRaw = readRequiredKvp026File(packetPath) ?: return preparationRejected(
        Kvp026ProofPreparationFailure.PACKET_REJECTED,
    )
    val packet = when (val admitted = admitTaskPacket(packetRaw, expectedPacket, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp026ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp026Dependencies(
        packet.packet,
        kvp013Path,
        kvp024Path,
        kvp025Path,
        kvp025ReportPath,
    )) {
        is Kvp026DependencyAdmission.Complete -> admitted.dependencies
        is Kvp026DependencyAdmission.Rejected -> return preparationRejected(
            Kvp026ProofPreparationFailure.DEPENDENCY_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp026ImplementationScope(
        exec,
        root,
        dependencies.implementationBaseline,
        observedHead,
        packet.packet.task.allowedWrites,
    )) {
        is Kvp026ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp026ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp026ProofPreparationFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val relevantInputs = when (val admitted = admitKvp026RelevantInputs(
        exec,
        root,
        packet,
        dependencies,
    )) {
        is Kvp026RelevantInputAdmission.Complete -> admitted.digest
        is Kvp026RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp026ProofPreparationFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val cases = when (val admitted = expectedKvp026ProofCases(packet.packet)) {
        is Kvp026TestEvidenceAdmission.Complete -> admitted.evidence
        is Kvp026TestEvidenceAdmission.Rejected -> return preparationRejected(
            Kvp026ProofPreparationFailure.CASE_EXPECTATION_REJECTED,
        )
    }
    return Kvp026ProofContextPreparation.Complete(
        Kvp026ProofContext(
            version,
            packet,
            dependencies,
            cases,
            scope,
            relevantInputs,
            packet.packet.kvp026CommandDigest(),
            currentKvp026ToolchainDigest(),
            observedHead,
        ),
    )
}

internal fun admitKvp026ReportAndReceipt(
    reportRaw: String,
    receiptRaw: String,
    context: Kvp026ProofContext,
    currentHead: DeliveryGeneration,
): Kvp026ExistingProofAdmission {
    val report = when (val admitted = admitKvp026ProofReport(reportRaw, context)) {
        is Kvp026ProofReportAdmission.Complete -> admitted.report
        is Kvp026ProofReportAdmission.Rejected -> return Kvp026ExistingProofAdmission.Rejected
    }
    return when (val admitted = admitTaskProofReceipt(
        receiptRaw,
        context.receiptExpectation(),
        currentHead,
    )) {
        is TaskProofReceiptAdmission.Complete -> Kvp026ExistingProofAdmission.Complete(
            report,
            admitted.receipt,
        )
        is TaskProofReceiptAdmission.Rejected -> Kvp026ExistingProofAdmission.Rejected
    }
}

internal fun TaskPacket.kvp026CommandDigest() = TaskProofCommandDigest(
    sha256(canonicalJson(listOf(
        proofCommand.command,
        proofCommand.misuse.command,
        proofCommand.legalPath.command,
    ))).value,
)

internal fun currentKvp026ToolchainDigest() = ToolchainDigest(
    sha256(canonicalJson(mapOf(
        "gradle" to GradleVersion.current().version,
        "javaRuntime" to System.getProperty("java.runtime.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
    ))).value,
)

internal fun readRequiredKvp026File(path: Path): String? = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun preparationRejected(failure: Kvp026ProofPreparationFailure) =
    Kvp026ProofContextPreparation.Rejected(failure)
