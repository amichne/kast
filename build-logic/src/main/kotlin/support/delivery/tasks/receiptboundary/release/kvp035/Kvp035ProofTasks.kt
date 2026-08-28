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

internal enum class Kvp035ProofFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    RELEVANT_INPUT_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    CASE_REJECTED,
    NEGATIVE_REPORT_REJECTED,
    RELEASE_REPORT_REJECTED,
    OUTPUT_PATH_REJECTED,
}

internal sealed interface Kvp035ContextPreparation {
    data class Complete(val context: Kvp035ProofContext) : Kvp035ContextPreparation
    data class Rejected(val failure: Kvp035ProofFailure) : Kvp035ContextPreparation
}

private sealed interface Kvp035TextRead {
    data class Complete(val raw: String) : Kvp035TextRead
    data object Rejected : Kvp035TextRead
}

@UntrackedTask(because = "Projects the current canonical KVP-035 graph packet")
abstract class GenerateKvp035TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp035Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        if (admitTaskPacket(raw, packet, version) !is TaskPacketFileAdmission.Complete) {
            throw GradleException("KVP-035 generated packet rejected")
        }
        logger.lifecycle(
            "KVP-035 task packet admitted with definition digest {}",
            packet.taskDefinitionDigest.value,
        )
    }
}

@UntrackedTask(because = "Runs KVP-035's content proof and emits one receipt")
abstract class ProveKvp035Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp011ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp011ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp034ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp034ReportFile: RegularFileProperty
    @get:InputFile abstract val negativeReportFile: RegularFileProperty
    @get:InputFile abstract val releaseReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp035Context(
            execOperations,
            root,
            head,
            packetFile.path(),
            kvp011ReceiptFile.path(),
            kvp011ReportFile.path(),
            kvp034ReceiptFile.path(),
            kvp034ReportFile.path(),
            negativeReportFile.path(),
            releaseReportFile.path(),
        )) {
            is Kvp035ContextPreparation.Complete -> prepared.context
            is Kvp035ContextPreparation.Rejected -> reject("context", prepared.failure)
        }
        val expected = context.packet.packet.task.outputs.single().path
        val observed = root.relativize(releaseReportFile.path().toAbsolutePath().normalize()).toString()
        if (expected != observed) reject("output", Kvp035ProofFailure.OUTPUT_PATH_REJECTED)
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            head,
            context.receiptExpectation(),
            receiptFile.path(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-035 COMPLETE (EXECUTED): misuse=REJECTED, legal=COMPLETE, assets={}, bytes={}, receipt={}",
            context.report.assets.size,
            context.report.combinedBytes,
            receipt.digest.value,
        )
    }

    private fun RegularFileProperty.path() = get().asFile.toPath()
    private fun reject(owner: String, failure: Any): Nothing =
        throw GradleException("KVP-035 $owner rejected: $failure")
}

/**
 * Proof transition: packet, predecessor receipts, named reports, and repository observation ->
 * `Kvp035ContextPreparation`.
 *
 * Establishes graph-owned task fields, current predecessor closure, clean relevant inputs,
 * ready-frontier write scope, both named cases, exact release assets, and closed outcomes. All
 * expected boundary failures remain [Kvp035ProofFailure] data; raw files enter only here.
 */
internal fun prepareKvp035Context(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packetPath: Path,
    kvp011Receipt: Path,
    kvp011Report: Path,
    kvp034Receipt: Path,
    kvp034Report: Path,
    negativeReport: Path,
    releaseReport: Path,
): Kvp035ContextPreparation {
    val (expected, version) = canonicalKvp035Packet()
    val packetRaw = when (val read = read035(packetPath)) {
        is Kvp035TextRead.Complete -> read.raw
        Kvp035TextRead.Rejected -> return proofRejected(Kvp035ProofFailure.PACKET_REJECTED)
    }
    val packet = when (val admitted = admitTaskPacket(packetRaw, expected, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return proofRejected(
            Kvp035ProofFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp035Dependencies(
        packet.packet, head, kvp011Receipt, kvp011Report, kvp034Receipt, kvp034Report,
    )) {
        is Kvp035DependencyAdmission.Complete -> admitted.dependencies
        is Kvp035DependencyAdmission.Rejected -> return proofRejected(
            Kvp035ProofFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevant = when (val admitted = admitKvp035RelevantInputs(
        exec, root, packet, dependencies,
    )) {
        is Kvp035RelevantInputAdmission.Complete -> admitted.digest
        is Kvp035RelevantInputAdmission.Rejected -> return proofRejected(
            Kvp035ProofFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp035ImplementationScope(
        exec, root, dependencies.implementationBaseline, head, packet.packet,
    )) {
        is Kvp035ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp035ImplementationScopeAdmission.Rejected -> return proofRejected(
            Kvp035ProofFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val cases = when (val admitted = admitKvp035Cases(packet.packet)) {
        is Kvp035CaseAdmission.Complete -> admitted.cases
        Kvp035CaseAdmission.Rejected -> return proofRejected(Kvp035ProofFailure.CASE_REJECTED)
    }
    val negativeRaw = when (val read = read035(negativeReport)) {
        is Kvp035TextRead.Complete -> read.raw
        Kvp035TextRead.Rejected -> return proofRejected(
            Kvp035ProofFailure.NEGATIVE_REPORT_REJECTED,
        )
    }
    if (admitKvp035Negative(negativeRaw) !is Kvp035NegativeAdmission.Complete) {
        return proofRejected(Kvp035ProofFailure.NEGATIVE_REPORT_REJECTED)
    }
    val reportRaw = when (val read = read035(releaseReport)) {
        is Kvp035TextRead.Complete -> read.raw
        Kvp035TextRead.Rejected -> return proofRejected(
            Kvp035ProofFailure.RELEASE_REPORT_REJECTED,
        )
    }
    val report = when (val admitted = admitKvp035Report(reportRaw)) {
        is Kvp035ReportAdmission.Complete -> admitted.report
        is Kvp035ReportAdmission.Qualified,
        Kvp035ReportAdmission.Rejected -> return proofRejected(
            Kvp035ProofFailure.RELEASE_REPORT_REJECTED,
        )
    }
    return Kvp035ContextPreparation.Complete(Kvp035ProofContext(
        version, packet, dependencies, relevant, scope, cases, report, reportRaw,
    ))
}

/** Validated canonical graph -> KVP-035 packet and stable proof-program version. */
internal fun canonicalKvp035Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-035"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-035 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

fun canonicalKvp035TaskPacket(): TaskPacket = canonicalKvp035Packet().first

/**
 * Proof transition: evidence `Path -> Kvp035TextRead`.
 *
 * Establishes bounded regular non-symlink UTF-8 text or closed rejection. Raw text is extracted
 * only at the KVP-035 Gradle proof boundary.
 */
private fun read035(path: Path): Kvp035TextRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp035TextRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp035TextRead.Rejected
}

private fun proofRejected(failure: Kvp035ProofFailure) =
    Kvp035ContextPreparation.Rejected(failure)
