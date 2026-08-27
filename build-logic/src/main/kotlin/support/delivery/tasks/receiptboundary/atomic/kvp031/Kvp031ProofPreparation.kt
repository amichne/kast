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

internal enum class Kvp031ProofDecision { REUSE, EXECUTE }

internal enum class Kvp031ProofPreparationFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    RELEVANT_INPUT_REJECTED,
    CASE_EXPECTATION_REJECTED,
}

internal sealed interface Kvp031ProofContextPreparation {
    data class Complete(val context: Kvp031ProofContext) : Kvp031ProofContextPreparation
    data class Rejected(val failure: Kvp031ProofPreparationFailure) :
        Kvp031ProofContextPreparation
}

internal sealed interface Kvp031ExistingProofAdmission {
    data class Complete(
        val report: AdmittedKvp031ProofReport,
        val receipt: AdmittedTaskProofReceipt,
    ) : Kvp031ExistingProofAdmission
    data object Rejected : Kvp031ExistingProofAdmission
}

@UntrackedTask(because = "Revalidates KVP-031's exact-head closure before deciding reuse")
abstract class PrepareKvp031ProofTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp030ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp030ReportFile: RegularFileProperty
    @get:Internal abstract val proofReportFile: RegularFileProperty
    @get:Internal abstract val receiptFile: RegularFileProperty
    @get:OutputFile abstract val decisionFile: RegularFileProperty

    @TaskAction fun prepare() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val report = readBoundaryFile(
            proofReportFile.get().asFile.toPath(),
            MAX_RECEIPT_EVIDENCE_BYTES,
        )
        val receipt = readBoundaryFile(
            receiptFile.get().asFile.toPath(),
            MAX_RECEIPT_EVIDENCE_BYTES,
        )
        val context = when (val prepared = prepareKvp031ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp030ReceiptFile.get().asFile.toPath(),
            kvp030ReportFile.get().asFile.toPath(),
            (report as? BoundaryFileRead.Complete)?.bytes?.toString(Charsets.UTF_8),
        )) {
            is Kvp031ProofContextPreparation.Complete -> prepared.context
            is Kvp031ProofContextPreparation.Rejected -> rejectPreparation(prepared.failure)
        }
        val decision = if (
            report is BoundaryFileRead.Complete && receipt is BoundaryFileRead.Complete &&
            admitKvp031ReportAndReceipt(
                report.bytes.toString(Charsets.UTF_8),
                receipt.bytes.toString(Charsets.UTF_8),
                context,
                head,
            ) is Kvp031ExistingProofAdmission.Complete
        ) Kvp031ProofDecision.REUSE else Kvp031ProofDecision.EXECUTE
        writeTextAtomically(decisionFile.get().asFile.toPath(), "${decision.name}\n")
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle("KVP-031 proof decision: {}", decision)
    }

    private fun rejectPreparation(failure: Kvp031ProofPreparationFailure): Nothing =
        throw GradleException("KVP-031 preparation rejected: $failure")
}

/**
 * Proof transition: graph packet, predecessor evidence, and repository observation ->
 * `Kvp031ProofContextPreparation`.
 *
 * Establishes the full reusable proof context in dependency order. Every expected boundary
 * failure is a closed [Kvp031ProofPreparationFailure].
 */
internal fun prepareKvp031ProofContext(
    exec: ExecOperations,
    root: Path,
    observedHead: DeliveryGeneration,
    packetPath: Path,
    kvp030Path: Path,
    kvp030ReportPath: Path,
    priorReportRaw: String?,
): Kvp031ProofContextPreparation {
    val (expectedPacket, version) = canonicalKvp031Packet()
    val packetRaw = readRequiredKvp031File(packetPath) ?: return preparationRejected(
        Kvp031ProofPreparationFailure.PACKET_REJECTED,
    )
    val packet = when (val admitted = admitTaskPacket(packetRaw, expectedPacket, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp031ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp031Dependencies(
        packet.packet,
        kvp030Path,
        kvp030ReportPath,
    )) {
        is Kvp031DependencyAdmission.Complete -> admitted.dependencies
        is Kvp031DependencyAdmission.Rejected -> return preparationRejected(
            Kvp031ProofPreparationFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevantInputs = when (val admitted = admitKvp031RelevantInputs(
        exec,
        root,
        packet,
        dependencies,
    )) {
        is Kvp031RelevantInputAdmission.Complete -> admitted.digest
        is Kvp031RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp031ProofPreparationFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val cases = when (val admitted = expectedKvp031ProofCases(packet.packet)) {
        is Kvp031TestEvidenceAdmission.Complete -> admitted.evidence
        is Kvp031TestEvidenceAdmission.Rejected -> return preparationRejected(
            Kvp031ProofPreparationFailure.CASE_EXPECTATION_REJECTED,
        )
    }
    val commandDigest = packet.packet.kvp031CommandDigest()
    val toolchainDigest = currentKvp031ToolchainDigest()
    val priorScope = priorReportRaw?.let { raw ->
        admitKvp031PriorProofScope(
            raw,
            version,
            packet,
            dependencies,
            cases,
            relevantInputs,
            commandDigest,
            toolchainDigest,
        )
    }
    val scopeAdmission = when (priorScope) {
        is Kvp031PriorProofScopeAdmission.Complete -> admitPriorKvp031ImplementationScope(
            exec,
            root,
            dependencies.implementationBaseline,
            observedHead,
            priorScope.candidate,
            packet.packet.task.allowedWrites,
            emptyList(),
        )
        Kvp031PriorProofScopeAdmission.Rejected,
        null,
            -> admitKvp031ImplementationScope(
                exec,
                root,
                dependencies.implementationBaseline,
                observedHead,
                packet.packet.task.allowedWrites,
                emptyList(),
            )
    }
    val scope = when (scopeAdmission) {
        is Kvp031ImplementationScopeAdmission.Complete -> scopeAdmission.scope
        is Kvp031ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp031ProofPreparationFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    return Kvp031ProofContextPreparation.Complete(
        Kvp031ProofContext(
            version,
            packet,
            dependencies,
            cases,
            scope,
            relevantInputs,
            commandDigest,
            toolchainDigest,
            observedHead,
        ),
    )
}

internal fun admitKvp031ReportAndReceipt(
    reportRaw: String,
    receiptRaw: String,
    context: Kvp031ProofContext,
    currentHead: DeliveryGeneration,
): Kvp031ExistingProofAdmission {
    val report = when (val admitted = admitKvp031ProofReport(reportRaw, context)) {
        is Kvp031ProofReportAdmission.Complete -> admitted.report
        is Kvp031ProofReportAdmission.Rejected -> return Kvp031ExistingProofAdmission.Rejected
    }
    return when (val admitted = admitTaskProofReceipt(
        receiptRaw,
        context.receiptExpectation(report),
        currentHead,
    )) {
        is TaskProofReceiptAdmission.Complete -> if (
            admitted.receipt.observedRepositoryHead == report.observedRepositoryHead
        ) {
            Kvp031ExistingProofAdmission.Complete(report, admitted.receipt)
        } else {
            Kvp031ExistingProofAdmission.Rejected
        }
        is TaskProofReceiptAdmission.Rejected -> Kvp031ExistingProofAdmission.Rejected
    }
}

internal fun TaskPacket.kvp031CommandDigest() = TaskProofCommandDigest(
    sha256(canonicalJson(listOf(
        proofCommand.command,
        proofCommand.misuse.command,
        proofCommand.legalPath.command,
    ))).value,
)

internal fun currentKvp031ToolchainDigest() = ToolchainDigest(
    sha256(canonicalJson(mapOf(
        "gradle" to GradleVersion.current().version,
        "javaRuntime" to System.getProperty("java.runtime.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
    ))).value,
)

internal fun readRequiredKvp031File(path: Path): String? = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun preparationRejected(failure: Kvp031ProofPreparationFailure) =
    Kvp031ProofContextPreparation.Rejected(failure)
