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

internal enum class Kvp033ProofFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    RELEVANT_INPUT_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    CASE_REJECTED,
    NEGATIVE_EVIDENCE_REJECTED,
    DYNAMIC_REPORT_REJECTED,
    OUTPUT_PATH_REJECTED,
}

internal sealed interface Kvp033ContextPreparation {
    data class Complete(val context: Kvp033ProofContext) : Kvp033ContextPreparation
    data class Rejected(val failure: Kvp033ProofFailure) : Kvp033ContextPreparation
}

internal sealed interface Kvp033TextFileRead {
    data class Complete(val text: String) : Kvp033TextFileRead
    data class Rejected(val failure: AuthoritySourceFailure) : Kvp033TextFileRead
}

@UntrackedTask(because = "Projects the current canonical KVP-033 graph packet")
abstract class GenerateKvp033TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp033Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        when (val admitted = admitTaskPacket(raw, packet, version)) {
            is TaskPacketFileAdmission.Complete -> logger.lifecycle(
                "KVP-033 task packet admitted with definition digest {}",
                admitted.admitted.packet.taskDefinitionDigest.value,
            )
            is TaskPacketFileAdmission.Rejected -> throw GradleException(
                "KVP-033 task packet rejected: ${admitted.failure}",
            )
        }
    }
}

@UntrackedTask(because = "Runs the non-cacheable KVP-033 proof and emits one v2 receipt")
abstract class ProveKvp033Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp022ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp032ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp032ReportFile: RegularFileProperty
    @get:InputFile abstract val negativeEvidenceFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp033Context(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp022ReceiptFile.get().asFile.toPath(),
            kvp025ReceiptFile.get().asFile.toPath(),
            kvp025ReportFile.get().asFile.toPath(),
            kvp031ReceiptFile.get().asFile.toPath(),
            kvp031ReportFile.get().asFile.toPath(),
            kvp032ReceiptFile.get().asFile.toPath(),
            kvp032ReportFile.get().asFile.toPath(),
            negativeEvidenceFile.get().asFile.toPath(),
            proofReportFile.get().asFile.toPath(),
        )) {
            is Kvp033ContextPreparation.Complete -> prepared.context
            is Kvp033ContextPreparation.Rejected -> reject("context", prepared.failure)
        }
        val expectedOutput = context.packet.packet.task.outputs.single().path
        val observedOutput = root.relativize(
            proofReportFile.get().asFile.toPath().toAbsolutePath().normalize(),
        ).toString()
        if (expectedOutput != observedOutput) reject(
            "output",
            Kvp033ProofFailure.OUTPUT_PATH_REJECTED,
        )
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            head,
            context.receiptExpectation(),
            receiptFile.get().asFile.toPath(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-033 COMPLETE (EXECUTED): misuse=REJECTED, legal=COMPLETE, " +
                "output={}, receipt={}",
            sha256(context.reportRaw).value,
            receipt.digest.value,
        )
    }

    private fun reject(owner: String, failure: Any): Nothing =
        throw GradleException("KVP-033 $owner rejected: $failure")
}

/**
 * Proof transition: graph packet, dependency paths, dynamic evidence, and repository observation ->
 * `Kvp033ContextPreparation`.
 *
 * Establishes the canonical task definition, predecessor receipt/output closure, clean relevant
 * inputs, declared implementation writes, named cases, eight misuse rejections, and canonical
 * zero-effect dynamic report. Every expected failure is closed [Kvp033ProofFailure] data; raw
 * path/text extraction is permitted only in this Gradle proof boundary.
 */
internal fun prepareKvp033Context(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packetPath: Path,
    kvp022Path: Path,
    kvp025Path: Path,
    kvp025ReportPath: Path,
    kvp031Path: Path,
    kvp031ReportPath: Path,
    kvp032Path: Path,
    kvp032ReportPath: Path,
    negativeEvidencePath: Path,
    proofReportPath: Path,
): Kvp033ContextPreparation {
    val (expectedPacket, version) = canonicalKvp033Packet()
    val packetRaw = when (val read = readKvp033TextFile(packetPath)) {
        is Kvp033TextFileRead.Complete -> read.text
        is Kvp033TextFileRead.Rejected -> return contextRejected(
            Kvp033ProofFailure.PACKET_REJECTED,
        )
    }
    val packet = when (val admitted = admitTaskPacket(packetRaw, expectedPacket, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return contextRejected(
            Kvp033ProofFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp033Dependencies(
        packet.packet,
        head,
        kvp022Path,
        kvp025Path,
        kvp025ReportPath,
        kvp031Path,
        kvp031ReportPath,
        kvp032Path,
        kvp032ReportPath,
    )) {
        is Kvp033DependencyAdmission.Complete -> admitted.dependencies
        is Kvp033DependencyAdmission.Rejected -> return contextRejected(
            Kvp033ProofFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevant = when (val admitted = admitKvp033RelevantInputs(
        exec, root, packet, dependencies,
    )) {
        is Kvp033RelevantInputAdmission.Complete -> admitted.digest
        is Kvp033RelevantInputAdmission.Rejected -> return contextRejected(
            Kvp033ProofFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val ownership = when (val admitted = admitKvp033WriteOwnership(
        packet.packet, KastVfsPassiveReusedIndexProgram.validated,
    )) {
        is Kvp033WriteOwnershipAdmission.Complete -> admitted.ownership
        is Kvp033WriteOwnershipAdmission.Rejected -> return contextRejected(
            Kvp033ProofFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp033ImplementationScope(
        exec, root, dependencies.implementationBaseline, head, ownership,
    )) {
        is Kvp033ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp033ImplementationScopeAdmission.Rejected -> return contextRejected(
            Kvp033ProofFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val cases = when (val admitted = admitKvp033Cases(packet.packet)) {
        is Kvp033CaseAdmission.Complete -> admitted.cases
        Kvp033CaseAdmission.Rejected -> return contextRejected(Kvp033ProofFailure.CASE_REJECTED)
    }
    val negativeRaw = when (val read = readKvp033TextFile(negativeEvidencePath)) {
        is Kvp033TextFileRead.Complete -> read.text
        is Kvp033TextFileRead.Rejected -> return contextRejected(
            Kvp033ProofFailure.NEGATIVE_EVIDENCE_REJECTED,
        )
    }
    when (admitKvp033NegativeEvidence(negativeRaw, cases.negativeFixtureCount)) {
        is Kvp033NegativeEvidenceAdmission.Complete -> Unit
        Kvp033NegativeEvidenceAdmission.Rejected -> return contextRejected(
            Kvp033ProofFailure.NEGATIVE_EVIDENCE_REJECTED,
        )
    }
    val reportRaw = when (val read = readKvp033TextFile(proofReportPath)) {
        is Kvp033TextFileRead.Complete -> read.text
        is Kvp033TextFileRead.Rejected -> return contextRejected(
            Kvp033ProofFailure.DYNAMIC_REPORT_REJECTED,
        )
    }
    val report = when (val admitted = admitKvp033Report(reportRaw)) {
        is Kvp033ReportAdmission.Complete -> admitted.document
        Kvp033ReportAdmission.Qualified,
        Kvp033ReportAdmission.Rejected -> return contextRejected(
            Kvp033ProofFailure.DYNAMIC_REPORT_REJECTED,
        )
    }
    return Kvp033ContextPreparation.Complete(Kvp033ProofContext(
        version, packet, dependencies, relevant, scope, cases, report, reportRaw, head,
    ))
}

/**
 * Proof transition: validated delivery graph -> KVP-033 `TaskPacket` and program version.
 *
 * Establishes every task/proof field from the sole Kotlin graph authority. Graph rejection becomes
 * a Gradle registration failure; raw task fields are never accepted from JSON or call sites.
 */
internal fun canonicalKvp033Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-033"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-033 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

/** Admitted graph authority -> canonical KVP-033 `TaskPacket`; no raw field input is accepted. */
fun canonicalKvp033TaskPacket(): TaskPacket = canonicalKvp033Packet().first

/**
 * Proof transition: raw evidence `Path -> Kvp033TextFileRead`.
 *
 * Establishes bounded regular non-symlink UTF-8 evidence or finite filesystem rejection. Raw text
 * may be extracted only by the KVP-033 Gradle proof boundary.
 */
internal fun readKvp033TextFile(path: Path): Kvp033TextFileRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp033TextFileRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp033TextFileRead.Rejected(read.failure)
}

private fun contextRejected(failure: Kvp033ProofFailure) =
    Kvp033ContextPreparation.Rejected(failure)
