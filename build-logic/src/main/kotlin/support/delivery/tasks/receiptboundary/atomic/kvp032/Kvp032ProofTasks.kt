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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

internal enum class Kvp032ProofDecision { REUSE, EXECUTE }

internal enum class Kvp032ProofPreparationFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    RELEVANT_INPUT_REJECTED,
    CASE_EXPECTATION_REJECTED,
}

internal sealed interface Kvp032ProofContextPreparation {
    data class Complete(val context: Kvp032ProofContext) : Kvp032ProofContextPreparation
    data class Rejected(val failure: Kvp032ProofPreparationFailure) :
        Kvp032ProofContextPreparation
}

internal sealed interface Kvp032TextFileRead {
    data class Complete(val text: String) : Kvp032TextFileRead
    data class Rejected(val failure: AuthoritySourceFailure) : Kvp032TextFileRead
}

@UntrackedTask(because = "Projects the current canonical KVP-032 graph packet")
abstract class GenerateKvp032TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp032Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        when (val admitted = admitTaskPacket(raw, packet, version)) {
            is TaskPacketFileAdmission.Complete -> logger.lifecycle(
                "KVP-032 task packet admitted with definition digest {}",
                admitted.admitted.packet.taskDefinitionDigest.value,
            )
            is TaskPacketFileAdmission.Rejected -> throw GradleException(
                "KVP-032 task packet rejected: ${admitted.failure}",
            )
        }
    }
}

@UntrackedTask(because = "Revalidates KVP-032 content closure before deciding reuse")
abstract class PrepareKvp032ProofTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp009ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp011ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp011ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp023ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReportFile: RegularFileProperty
    @get:Internal abstract val proofReportFile: RegularFileProperty
    @get:Internal abstract val receiptFile: RegularFileProperty
    @get:OutputFile abstract val decisionFile: RegularFileProperty

    @TaskAction fun prepare() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = context(root, head)
        val report = readKvp032TextFile(proofReportFile.get().asFile.toPath())
        val receipt = readKvp032TextFile(receiptFile.get().asFile.toPath())
        val decision = if (
            report is Kvp032TextFileRead.Complete &&
            receipt is Kvp032TextFileRead.Complete &&
            admitKvp032ExistingProof(report.text, receipt.text, context, head) is
                Kvp032ExistingProofAdmission.Complete
        ) Kvp032ProofDecision.REUSE else Kvp032ProofDecision.EXECUTE
        writeTextAtomically(decisionFile.get().asFile.toPath(), "${decision.name}\n")
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle("KVP-032 proof decision: {}", decision)
    }

    private fun context(root: Path, head: DeliveryGeneration): Kvp032ProofContext =
        when (val prepared = prepareKvp032ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp009ReceiptFile.get().asFile.toPath(),
            kvp011ReceiptFile.get().asFile.toPath(),
            kvp011ReportFile.get().asFile.toPath(),
            kvp023ReceiptFile.get().asFile.toPath(),
            kvp027ReceiptFile.get().asFile.toPath(),
            kvp027ReportFile.get().asFile.toPath(),
            kvp031ReceiptFile.get().asFile.toPath(),
            kvp031ReportFile.get().asFile.toPath(),
        )) {
            is Kvp032ProofContextPreparation.Complete -> prepared.context
            is Kvp032ProofContextPreparation.Rejected -> throw GradleException(
                "KVP-032 preparation rejected: ${prepared.failure}",
            )
        }
}

/**
 * Proof transition: canonical packet/dependency paths plus repository observation ->
 * `Kvp032ProofContextPreparation`.
 *
 * Establishes the generated task definition, complete predecessor closure, clean relevant-input
 * digest, declared implementation-write scope, named cases, command, toolchain, and observed head.
 * Every expected boundary failure is a closed rejection; raw path extraction remains here.
 */
internal fun prepareKvp032ProofContext(
    exec: ExecOperations,
    root: Path,
    observedHead: DeliveryGeneration,
    packetPath: Path,
    kvp009Path: Path,
    kvp011Path: Path,
    kvp011ReportPath: Path,
    kvp023Path: Path,
    kvp027Path: Path,
    kvp027ReportPath: Path,
    kvp031Path: Path,
    kvp031ReportPath: Path,
): Kvp032ProofContextPreparation {
    val (expectedPacket, version) = canonicalKvp032Packet()
    val packetRaw = when (val read = readKvp032TextFile(packetPath)) {
        is Kvp032TextFileRead.Complete -> read.text
        is Kvp032TextFileRead.Rejected -> return preparationRejected(
            Kvp032ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val packet = when (val admitted = admitTaskPacket(packetRaw, expectedPacket, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp032ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp032Dependencies(
        packet.packet,
        observedHead,
        kvp009Path,
        kvp011Path,
        kvp011ReportPath,
        kvp023Path,
        kvp027Path,
        kvp027ReportPath,
        kvp031Path,
        kvp031ReportPath,
    )) {
        is Kvp032DependencyAdmission.Complete -> admitted.dependencies
        is Kvp032DependencyAdmission.Rejected -> return preparationRejected(
            Kvp032ProofPreparationFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevant = when (val admitted = admitKvp032RelevantInputs(
        exec, root, packet, dependencies,
    )) {
        is Kvp032RelevantInputAdmission.Complete -> admitted.digest
        is Kvp032RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp032ProofPreparationFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp032ImplementationScope(
        exec,
        root,
        dependencies.implementationBaseline,
        observedHead,
        packet.packet.task.allowedWrites,
    )) {
        is Kvp032ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp032ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp032ProofPreparationFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val cases = when (val admitted = expectedKvp032ProofCases(packet.packet)) {
        is Kvp032EvidenceAdmission.Complete -> admitted.evidence
        is Kvp032EvidenceAdmission.Rejected -> return preparationRejected(
            Kvp032ProofPreparationFailure.CASE_EXPECTATION_REJECTED,
        )
    }
    return Kvp032ProofContextPreparation.Complete(Kvp032ProofContext(
        version,
        packet,
        dependencies,
        cases,
        scope,
        relevant,
        packet.packet.kvp032CommandDigest(),
        currentKvp032ToolchainDigest(),
        observedHead,
    ))
}

@UntrackedTask(because = "Admits KVP-032 evidence and emits or reuses one v2 receipt")
abstract class ProveKvp032Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp009ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp011ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp011ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp023ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp027ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReportFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val evidenceFile: RegularFileProperty
    @get:InputFile abstract val decisionFile: RegularFileProperty
    @get:InputFile abstract val proofReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp032ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp009ReceiptFile.get().asFile.toPath(),
            kvp011ReceiptFile.get().asFile.toPath(),
            kvp011ReportFile.get().asFile.toPath(),
            kvp023ReceiptFile.get().asFile.toPath(),
            kvp027ReceiptFile.get().asFile.toPath(),
            kvp027ReportFile.get().asFile.toPath(),
            kvp031ReceiptFile.get().asFile.toPath(),
            kvp031ReportFile.get().asFile.toPath(),
        )) {
            is Kvp032ProofContextPreparation.Complete -> prepared.context
            is Kvp032ProofContextPreparation.Rejected -> reject("context", prepared.failure)
        }
        val report = read(proofReportFile.get().asFile.toPath())
        val decision = when (read(decisionFile.get().asFile.toPath())) {
            "${Kvp032ProofDecision.REUSE.name}\n" -> Kvp032ProofDecision.REUSE
            "${Kvp032ProofDecision.EXECUTE.name}\n" -> Kvp032ProofDecision.EXECUTE
            else -> reject("decision", "malformed")
        }
        if (decision == Kvp032ProofDecision.REUSE) {
            val existing = admitKvp032ExistingProof(
                report,
                read(receiptFile.get().asFile.toPath()),
                context,
                head,
            )
            if (existing !is Kvp032ExistingProofAdmission.Complete) {
                reject("reuse", "closure changed after preparation")
            }
            revalidateExactHead(root, AuthorityGitRevision(head.value))
            logComplete(existing.receipt, report, "REUSED")
            return
        }
        when (val admitted = admitKvp032Evidence(
            read(evidenceFile.get().asFile.toPath()),
            context.packet.packet,
            report,
        )) {
            is Kvp032EvidenceAdmission.Complete -> if (admitted.evidence != context.cases) {
                reject("evidence", "case mismatch")
            }
            is Kvp032EvidenceAdmission.Rejected -> reject("evidence", admitted.failure)
        }
        val expectedOutput = context.packet.packet.task.outputs.single().path
        val observedOutput = root.relativize(
            proofReportFile.get().asFile.toPath().toAbsolutePath().normalize(),
        ).toString()
        if (expectedOutput != observedOutput) reject("output path", observedOutput)
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            head,
            context.receiptExpectation(report),
            receiptFile.get().asFile.toPath(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logComplete(receipt, report, "EXECUTED")
    }

    private fun logComplete(
        receipt: AdmittedTaskProofReceipt,
        report: String,
        disposition: String,
    ) = logger.lifecycle(
        "KVP-032 COMPLETE ({}): misuse=REJECTED, legal=COMPLETE, output={}, receipt={}",
        disposition,
        sha256(report).value,
        receipt.digest.value,
    )

    private fun read(path: Path): String = when (val read = readKvp032TextFile(path)) {
        is Kvp032TextFileRead.Complete -> read.text
        is Kvp032TextFileRead.Rejected -> reject("bounded file read", read.failure)
    }
    private fun reject(owner: String, failure: Any): Nothing =
        throw GradleException("KVP-032 $owner rejected: $failure")
}

/**
 * Proof transition: validated canonical delivery graph -> KVP-032 `TaskPacket` and program version.
 *
 * Establishes that all task fields and receipt policy come from the sole graph authority. Graph
 * rejection becomes a Gradle failure only at task registration; raw task fields are not accepted.
 */
internal fun canonicalKvp032Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-032"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-032 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

fun canonicalKvp032TaskPacket(): TaskPacket = canonicalKvp032Packet().first

/**
 * Proof transition: raw evidence `Path -> Kvp032TextFileRead`.
 *
 * Establishes bounded, regular, non-symlink UTF-8 task evidence. The finite filesystem failure is
 * preserved by [Kvp032TextFileRead.Rejected]; raw text may be extracted only by Gradle adapters.
 */
internal fun readKvp032TextFile(path: Path): Kvp032TextFileRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp032TextFileRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp032TextFileRead.Rejected(read.failure)
}

private fun preparationRejected(failure: Kvp032ProofPreparationFailure) =
    Kvp032ProofContextPreparation.Rejected(failure)
