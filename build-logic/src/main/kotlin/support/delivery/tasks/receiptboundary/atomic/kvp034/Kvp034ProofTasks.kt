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
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

internal enum class Kvp034ProofFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    RELEVANT_INPUT_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    CASE_REJECTED,
    NEGATIVE_EVIDENCE_REJECTED,
    INSTALLED_REPORT_REJECTED,
    OUTPUT_PATH_REJECTED,
}

internal sealed interface Kvp034ContextPreparation {
    data class Complete(val context: Kvp034ProofContext) : Kvp034ContextPreparation
    data class Rejected(val failure: Kvp034ProofFailure) : Kvp034ContextPreparation
}

private sealed interface Kvp034TextRead {
    data class Complete(val raw: String) : Kvp034TextRead
    data object Rejected : Kvp034TextRead
}

@UntrackedTask(because = "Projects the current canonical KVP-034 graph packet")
abstract class GenerateKvp034TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp034Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        if (admitTaskPacket(raw, packet, version) !is TaskPacketFileAdmission.Complete) {
            throw GradleException("KVP-034 generated packet rejected")
        }
        logger.lifecycle(
            "KVP-034 task packet admitted with definition digest {}",
            packet.taskDefinitionDigest.value,
        )
    }
}

@UntrackedTask(because = "Runs KVP-034's exact-head proof and emits one receipt")
abstract class ProveKvp034Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp033ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp033ReportFile: RegularFileProperty
    @get:InputFile abstract val negativeEvidenceFile: RegularFileProperty
    @get:InputFile abstract val installedReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp034Context(
            execOperations, root, head, packetFile.path(), kvp027ReceiptFile.path(),
            kvp027ReportFile.path(), kvp031ReceiptFile.path(), kvp031ReportFile.path(),
            kvp033ReceiptFile.path(), kvp033ReportFile.path(), negativeEvidenceFile.path(),
            installedReportFile.path(),
        )) {
            is Kvp034ContextPreparation.Complete -> prepared.context
            is Kvp034ContextPreparation.Rejected -> reject("context", prepared.failure)
        }
        val expected = context.packet.packet.task.outputs.single().path
        val observed = root.relativize(installedReportFile.path().toAbsolutePath().normalize()).toString()
        if (expected != observed) reject("output", Kvp034ProofFailure.OUTPUT_PATH_REJECTED)
        val receipt = issueTaskProofReceiptAtBoundary(
            root, head, context.receiptExpectation(), receiptFile.path(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-034 COMPLETE (EXECUTED): misuse=REJECTED, legal=COMPLETE, metrics={}, receipt={}",
            context.report.metrics.size,
            receipt.digest.value,
        )
    }

    private fun RegularFileProperty.path() = get().asFile.toPath()
    private fun reject(owner: String, failure: Any): Nothing =
        throw GradleException("KVP-034 $owner rejected: $failure")
}

/**
 * Proof transition: graph packet, predecessor closure, named proof evidence, and installed report
 * -> `Kvp034ContextPreparation`.
 *
 * Establishes clean graph-owned inputs/writes, exact head, every installed metric, and both named
 * cases. All expected failures remain closed [Kvp034ProofFailure] data; raw files enter only here.
 */
internal fun prepareKvp034Context(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packetPath: Path,
    kvp027Receipt: Path,
    kvp027Report: Path,
    kvp031Receipt: Path,
    kvp031Report: Path,
    kvp033Receipt: Path,
    kvp033Report: Path,
    negativeEvidence: Path,
    installedReport: Path,
): Kvp034ContextPreparation {
    val (expected, version) = canonicalKvp034Packet()
    val packetRaw = when (val read = readKvp034Text(packetPath)) {
        is Kvp034TextRead.Complete -> read.raw
        Kvp034TextRead.Rejected -> return rejected(Kvp034ProofFailure.PACKET_REJECTED)
    }
    val packet = when (val admitted = admitTaskPacket(packetRaw, expected, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return rejected(Kvp034ProofFailure.PACKET_REJECTED)
    }
    val dependencies = when (val admitted = admitKvp034Dependencies(
        packet.packet, head, kvp027Receipt, kvp027Report, kvp031Receipt, kvp031Report,
        kvp033Receipt, kvp033Report,
    )) {
        is Kvp034DependencyAdmission.Complete -> admitted.dependencies
        is Kvp034DependencyAdmission.Rejected -> return rejected(
            Kvp034ProofFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevant = when (val admitted = admitKvp034RelevantInputs(exec, root, packet, dependencies)) {
        is Kvp034RelevantInputAdmission.Complete -> admitted.digest
        is Kvp034RelevantInputAdmission.Rejected -> return rejected(
            Kvp034ProofFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val ownership = when (val admitted = admitKvp034WriteOwnership(
        packet.packet, KastVfsPassiveReusedIndexProgram.validated,
    )) {
        is Kvp034WriteOwnershipAdmission.Complete -> admitted.ownership
        is Kvp034WriteOwnershipAdmission.Rejected -> return rejected(
            Kvp034ProofFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp034ImplementationScope(
        exec, root, dependencies.implementationBaseline, head, ownership,
    )) {
        is Kvp034ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp034ImplementationScopeAdmission.Rejected -> return rejected(
            Kvp034ProofFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val cases = when (val admitted = admitKvp034Cases(packet.packet)) {
        is Kvp034CaseAdmission.Complete -> admitted.cases
        Kvp034CaseAdmission.Rejected -> return rejected(Kvp034ProofFailure.CASE_REJECTED)
    }
    val negativeRaw = when (val read = readKvp034Text(negativeEvidence)) {
        is Kvp034TextRead.Complete -> read.raw
        Kvp034TextRead.Rejected -> return rejected(
            Kvp034ProofFailure.NEGATIVE_EVIDENCE_REJECTED,
        )
    }
    if (admitKvp034NegativeEvidence(negativeRaw, cases.metricCount) !is
        Kvp034NegativeEvidenceAdmission.Complete
    ) return rejected(Kvp034ProofFailure.NEGATIVE_EVIDENCE_REJECTED)
    val reportRaw = when (val read = readKvp034Text(installedReport)) {
        is Kvp034TextRead.Complete -> read.raw
        Kvp034TextRead.Rejected -> return rejected(
            Kvp034ProofFailure.INSTALLED_REPORT_REJECTED,
        )
    }
    val report = when (val admitted = admitKvp034Report(
        reportRaw, KastVfsPassiveReusedIndexProgram.validated.program.installedMetrics, head,
    )) {
        is Kvp034ReportAdmission.Complete -> admitted.report
        is Kvp034ReportAdmission.Qualified,
        Kvp034ReportAdmission.Rejected -> return rejected(Kvp034ProofFailure.INSTALLED_REPORT_REJECTED)
    }
    return Kvp034ContextPreparation.Complete(Kvp034ProofContext(
        version, packet, dependencies, relevant, scope, cases, report, reportRaw, head,
    ))
}

/**
 * Proof transition: validated delivery graph -> KVP-034 `TaskPacket` and program version.
 *
 * Establishes every task/proof field from the sole Kotlin authority. Graph rejection becomes a
 * Gradle registration failure; raw task fields are never accepted from JSON or call sites.
 */
internal fun canonicalKvp034Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-034"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-034 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

fun canonicalKvp034TaskPacket(): TaskPacket = canonicalKvp034Packet().first

/**
 * Proof transition: evidence `Path -> Kvp034TextRead`.
 *
 * Establishes bounded regular non-symlink UTF-8 text or closed rejection. Raw text is extracted
 * only at the KVP-034 Gradle proof boundary.
 */
private fun readKvp034Text(path: Path): Kvp034TextRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp034TextRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp034TextRead.Rejected
}

private fun rejected(failure: Kvp034ProofFailure) = Kvp034ContextPreparation.Rejected(failure)
