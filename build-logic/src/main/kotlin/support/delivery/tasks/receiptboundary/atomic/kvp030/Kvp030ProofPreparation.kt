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

internal enum class Kvp030ProofDecision { REUSE, EXECUTE }

internal enum class Kvp030ProofPreparationFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    RELEVANT_INPUT_REJECTED,
    CASE_EXPECTATION_REJECTED,
}

internal sealed interface Kvp030ProofContextPreparation {
    data class Complete(val context: Kvp030ProofContext) : Kvp030ProofContextPreparation
    data class Rejected(val failure: Kvp030ProofPreparationFailure) :
        Kvp030ProofContextPreparation
}

internal sealed interface Kvp030ExistingProofAdmission {
    data class Complete(
        val report: AdmittedKvp030ProofReport,
        val receipt: AdmittedTaskProofReceipt,
    ) : Kvp030ExistingProofAdmission
    data object Rejected : Kvp030ExistingProofAdmission
}

@UntrackedTask(because = "Revalidates KVP-030's content closure before deciding reuse")
abstract class PrepareKvp030ProofTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp029ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp029ReportFile: RegularFileProperty
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
        val context = when (val prepared = prepareKvp030ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp029ReceiptFile.get().asFile.toPath(),
            kvp029ReportFile.get().asFile.toPath(),
            (report as? BoundaryFileRead.Complete)?.bytes?.toString(Charsets.UTF_8),
        )) {
            is Kvp030ProofContextPreparation.Complete -> prepared.context
            is Kvp030ProofContextPreparation.Rejected -> rejectPreparation(prepared.failure)
        }
        val decision = if (
            report is BoundaryFileRead.Complete && receipt is BoundaryFileRead.Complete &&
            admitKvp030ReportAndReceipt(
                report.bytes.toString(Charsets.UTF_8),
                receipt.bytes.toString(Charsets.UTF_8),
                context,
                head,
            ) is Kvp030ExistingProofAdmission.Complete
        ) Kvp030ProofDecision.REUSE else Kvp030ProofDecision.EXECUTE
        writeTextAtomically(decisionFile.get().asFile.toPath(), "${decision.name}\n")
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle("KVP-030 proof decision: {}", decision)
    }

    private fun rejectPreparation(failure: Kvp030ProofPreparationFailure): Nothing =
        throw GradleException("KVP-030 preparation rejected: $failure")
}

/**
 * Proof transition: graph packet, predecessor evidence, and repository observation ->
 * `Kvp030ProofContextPreparation`.
 *
 * Establishes the full reusable proof context in dependency order. Every expected boundary
 * failure is a closed [Kvp030ProofPreparationFailure].
 */
internal fun prepareKvp030ProofContext(
    exec: ExecOperations,
    root: Path,
    observedHead: DeliveryGeneration,
    packetPath: Path,
    kvp029Path: Path,
    kvp029ReportPath: Path,
    priorReportRaw: String?,
): Kvp030ProofContextPreparation {
    val (expectedPacket, version) = canonicalKvp030Packet()
    val packetRaw = readRequiredKvp030File(packetPath) ?: return preparationRejected(
        Kvp030ProofPreparationFailure.PACKET_REJECTED,
    )
    val packet = when (val admitted = admitTaskPacket(packetRaw, expectedPacket, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp030ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp030Dependencies(
        packet.packet,
        kvp029Path,
        kvp029ReportPath,
    )) {
        is Kvp030DependencyAdmission.Complete -> admitted.dependencies
        is Kvp030DependencyAdmission.Rejected -> return preparationRejected(
            Kvp030ProofPreparationFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevantInputs = when (val admitted = admitKvp030RelevantInputs(
        exec,
        root,
        packet,
        dependencies,
    )) {
        is Kvp030RelevantInputAdmission.Complete -> admitted.digest
        is Kvp030RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp030ProofPreparationFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val cases = when (val admitted = expectedKvp030ProofCases(packet.packet)) {
        is Kvp030TestEvidenceAdmission.Complete -> admitted.evidence
        is Kvp030TestEvidenceAdmission.Rejected -> return preparationRejected(
            Kvp030ProofPreparationFailure.CASE_EXPECTATION_REJECTED,
        )
    }
    val commandDigest = packet.packet.kvp030CommandDigest()
    val toolchainDigest = currentKvp030ToolchainDigest()
    val priorScope = priorReportRaw?.let { raw ->
        admitKvp030PriorProofScope(
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
        is Kvp030PriorProofScopeAdmission.Complete -> admitPriorKvp030ImplementationScope(
            exec,
            root,
            dependencies.implementationBaseline,
            observedHead,
            priorScope.candidate,
            packet.packet.task.allowedWrites,
            emptyList(),
        )
        Kvp030PriorProofScopeAdmission.Rejected,
        null,
            -> admitKvp030ImplementationScope(
                exec,
                root,
                dependencies.implementationBaseline,
                observedHead,
                packet.packet.task.allowedWrites,
                emptyList(),
            )
    }
    val scope = when (scopeAdmission) {
        is Kvp030ImplementationScopeAdmission.Complete -> scopeAdmission.scope
        is Kvp030ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp030ProofPreparationFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    return Kvp030ProofContextPreparation.Complete(
        Kvp030ProofContext(
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

internal fun admitKvp030ReportAndReceipt(
    reportRaw: String,
    receiptRaw: String,
    context: Kvp030ProofContext,
    currentHead: DeliveryGeneration,
): Kvp030ExistingProofAdmission {
    val report = when (val admitted = admitKvp030ProofReport(reportRaw, context)) {
        is Kvp030ProofReportAdmission.Complete -> admitted.report
        is Kvp030ProofReportAdmission.Rejected -> return Kvp030ExistingProofAdmission.Rejected
    }
    return when (val admitted = admitTaskProofReceipt(
        receiptRaw,
        context.receiptExpectation(report),
        currentHead,
    )) {
        is TaskProofReceiptAdmission.Complete -> if (
            admitted.receipt.observedRepositoryHead == report.observedRepositoryHead
        ) {
            Kvp030ExistingProofAdmission.Complete(report, admitted.receipt)
        } else {
            Kvp030ExistingProofAdmission.Rejected
        }
        is TaskProofReceiptAdmission.Rejected -> Kvp030ExistingProofAdmission.Rejected
    }
}

internal fun TaskPacket.kvp030CommandDigest() = TaskProofCommandDigest(
    sha256(canonicalJson(listOf(
        proofCommand.command,
        proofCommand.misuse.command,
        proofCommand.legalPath.command,
    ))).value,
)

internal fun currentKvp030ToolchainDigest() = ToolchainDigest(
    sha256(canonicalJson(mapOf(
        "gradle" to GradleVersion.current().version,
        "javaRuntime" to System.getProperty("java.runtime.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
    ))).value,
)

internal fun readRequiredKvp030File(path: Path): String? = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> read.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun preparationRejected(failure: Kvp030ProofPreparationFailure) =
    Kvp030ProofContextPreparation.Rejected(failure)
