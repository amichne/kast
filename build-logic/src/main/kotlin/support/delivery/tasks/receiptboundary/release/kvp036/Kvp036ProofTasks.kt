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

internal enum class Kvp036ProofFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    RELEVANT_INPUT_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    CASE_REJECTED,
    NEGATIVE_REPORT_REJECTED,
    RELEASE_REPORT_REJECTED,
    OUTPUT_PATH_REJECTED,
}

internal sealed interface Kvp036ContextPreparation {
    data class Complete(val context: Kvp036ProofContext) : Kvp036ContextPreparation
    data class Rejected(val failure: Kvp036ProofFailure) : Kvp036ContextPreparation
}

private sealed interface Kvp036TextRead {
    data class Complete(val raw: String) : Kvp036TextRead
    data object Rejected : Kvp036TextRead
}

@UntrackedTask(because = "Projects the current canonical KVP-036 graph packet")
abstract class GenerateKvp036TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp036Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        if (admitTaskPacket(raw, packet, version) !is TaskPacketFileAdmission.Complete) {
            throw GradleException("KVP-036 generated packet rejected")
        }
        logger.lifecycle(
            "KVP-036 task packet admitted with definition digest {}",
            packet.taskDefinitionDigest.value,
        )
    }
}

@UntrackedTask(because = "Runs KVP-036's exact-head proof and emits one receipt")
abstract class ProveKvp036Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp035ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp035ReportFile: RegularFileProperty
    @get:InputFile abstract val negativeReportFile: RegularFileProperty
    @get:InputFile abstract val retirementReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp036Context(
            execOperations,
            root,
            head,
            packetFile.path(),
            kvp027ReceiptFile.path(),
            kvp027ReportFile.path(),
            kvp035ReceiptFile.path(),
            kvp035ReportFile.path(),
            negativeReportFile.path(),
            retirementReportFile.path(),
        )) {
            is Kvp036ContextPreparation.Complete -> prepared.context
            is Kvp036ContextPreparation.Rejected -> reject("context", prepared.failure)
        }
        val expected = context.packet.packet.task.outputs.single().path
        val observed = root.relativize(
            retirementReportFile.path().toAbsolutePath().normalize(),
        ).toString()
        if (expected != observed) reject("output", Kvp036ProofFailure.OUTPUT_PATH_REJECTED)
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            head,
            context.receiptExpectation(),
            receiptFile.path(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-036 COMPLETE (EXECUTED): misuse=REJECTED, legal=COMPLETE, assets={}, retired={}, receipt={}",
            context.report.assetCount,
            context.report.retiredAuthorityCount,
            receipt.digest.value,
        )
    }

    private fun RegularFileProperty.path() = get().asFile.toPath()
    private fun reject(owner: String, failure: Any): Nothing =
        throw GradleException("KVP-036 $owner rejected: $failure")
}

/**
 * Proof transition: packet, predecessor receipts, named reports, and repository observation ->
 * `Kvp036ContextPreparation`.
 *
 * Establishes graph-owned task fields, current predecessor closure, clean relevant inputs,
 * ready-frontier write scope, both named cases, exact retirement evidence, and closed outcomes. All
 * expected boundary failures remain [Kvp036ProofFailure] data; raw files enter only here.
 */
internal fun prepareKvp036Context(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packetPath: Path,
    kvp027Receipt: Path,
    kvp027Report: Path,
    kvp035Receipt: Path,
    kvp035Report: Path,
    negativeReport: Path,
    retirementReport: Path,
): Kvp036ContextPreparation {
    val (expected, version) = canonicalKvp036Packet()
    val packetRaw = when (val read = read036(packetPath)) {
        is Kvp036TextRead.Complete -> read.raw
        Kvp036TextRead.Rejected -> return proofRejected(Kvp036ProofFailure.PACKET_REJECTED)
    }
    val packet = when (val admitted = admitTaskPacket(packetRaw, expected, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return proofRejected(
            Kvp036ProofFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp036Dependencies(
        packet.packet, kvp027Receipt, kvp027Report, kvp035Receipt, kvp035Report,
    )) {
        is Kvp036DependencyAdmission.Complete -> admitted.dependencies
        is Kvp036DependencyAdmission.Rejected -> return proofRejected(
            Kvp036ProofFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevant = when (val admitted = admitKvp036RelevantInputs(
        exec, root, packet, dependencies,
    )) {
        is Kvp036RelevantInputAdmission.Complete -> admitted.digest
        is Kvp036RelevantInputAdmission.Rejected -> return proofRejected(
            Kvp036ProofFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp036ImplementationScope(
        exec, root, dependencies.implementationBaseline, head, packet.packet,
    )) {
        is Kvp036ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp036ImplementationScopeAdmission.Rejected -> return proofRejected(
            Kvp036ProofFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val cases = when (val admitted = admitKvp036Cases(packet.packet)) {
        is Kvp036CaseAdmission.Complete -> admitted.cases
        Kvp036CaseAdmission.Rejected -> return proofRejected(Kvp036ProofFailure.CASE_REJECTED)
    }
    val negativeRaw = when (val read = read036(negativeReport)) {
        is Kvp036TextRead.Complete -> read.raw
        Kvp036TextRead.Rejected -> return proofRejected(
            Kvp036ProofFailure.NEGATIVE_REPORT_REJECTED,
        )
    }
    if (admitKvp036Negative(negativeRaw) !is Kvp036NegativeAdmission.Complete) {
        return proofRejected(Kvp036ProofFailure.NEGATIVE_REPORT_REJECTED)
    }
    val reportRaw = when (val read = read036(retirementReport)) {
        is Kvp036TextRead.Complete -> read.raw
        Kvp036TextRead.Rejected -> return proofRejected(
            Kvp036ProofFailure.RELEASE_REPORT_REJECTED,
        )
    }
    val report = when (val admitted = admitKvp036Report(reportRaw)) {
        is Kvp036ReportAdmission.Complete -> admitted.report
        is Kvp036ReportAdmission.Qualified,
        Kvp036ReportAdmission.Rejected -> return proofRejected(
            Kvp036ProofFailure.RELEASE_REPORT_REJECTED,
        )
    }
    return Kvp036ContextPreparation.Complete(Kvp036ProofContext(
        version, packet, dependencies, relevant, scope, cases, report, reportRaw,
    ))
}

/** Validated canonical graph -> KVP-036 packet and stable proof-program version. */
internal fun canonicalKvp036Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-036"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-036 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

fun canonicalKvp036TaskPacket(): TaskPacket = canonicalKvp036Packet().first

/**
 * Proof transition: evidence `Path -> Kvp036TextRead`.
 *
 * Establishes bounded regular non-symlink UTF-8 text or closed rejection. Raw text is extracted
 * only at the KVP-036 Gradle proof boundary.
 */
private fun read036(path: Path): Kvp036TextRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp036TextRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp036TextRead.Rejected
}

private fun proofRejected(failure: Kvp036ProofFailure) =
    Kvp036ContextPreparation.Rejected(failure)
