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

internal enum class Kvp011ProofDecision { REUSE, EXECUTE }

internal enum class Kvp011ProofPreparationFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    RELEVANT_INPUT_REJECTED,
    CASE_EXPECTATION_REJECTED,
}

internal sealed interface Kvp011ProofContextPreparation {
    data class Complete(val context: Kvp011ProofContext) : Kvp011ProofContextPreparation
    data class Rejected(val failure: Kvp011ProofPreparationFailure) :
        Kvp011ProofContextPreparation
}

internal sealed interface Kvp011TextFileRead {
    data class Complete(val text: String) : Kvp011TextFileRead
    data class Rejected(val failure: AuthoritySourceFailure) : Kvp011TextFileRead
}

@UntrackedTask(because = "Projects the current canonical KVP-011 graph packet")
abstract class GenerateKvp011TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp011Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.get().asFile.toPath(), raw)
        when (val admitted = admitTaskPacket(raw, packet, version)) {
            is TaskPacketFileAdmission.Complete -> logger.lifecycle(
                "KVP-011 task packet admitted with definition digest {}",
                admitted.admitted.packet.taskDefinitionDigest.value,
            )
            is TaskPacketFileAdmission.Rejected -> throw GradleException(
                "KVP-011 task packet rejected: ${admitted.failure}",
            )
        }
    }
}

@UntrackedTask(because = "Revalidates KVP-011 content closure before deciding reuse")
abstract class PrepareKvp011ProofTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp010ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReportFile: RegularFileProperty
    @get:Internal abstract val layoutReportFile: RegularFileProperty
    @get:Internal abstract val receiptFile: RegularFileProperty
    @get:OutputFile abstract val decisionFile: RegularFileProperty

    @TaskAction fun prepare() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = context(root, head)
        val layout = readKvp011TextFile(layoutReportFile.get().asFile.toPath())
        val receipt = readKvp011TextFile(receiptFile.get().asFile.toPath())
        val decision = if (
            layout is Kvp011TextFileRead.Complete &&
            receipt is Kvp011TextFileRead.Complete &&
            admitKvp011ExistingProof(layout.text, receipt.text, context, head) is
                Kvp011ExistingProofAdmission.Complete
        ) Kvp011ProofDecision.REUSE else Kvp011ProofDecision.EXECUTE
        writeTextAtomically(decisionFile.get().asFile.toPath(), "${decision.name}\n")
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle("KVP-011 proof decision: {}", decision)
    }

    private fun context(root: Path, head: DeliveryGeneration): Kvp011ProofContext =
        when (val prepared = prepareKvp011ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp010ReceiptFile.get().asFile.toPath(),
            kvp025ReceiptFile.get().asFile.toPath(),
            kvp025ReportFile.get().asFile.toPath(),
            kvp031ReceiptFile.get().asFile.toPath(),
            kvp031ReportFile.get().asFile.toPath(),
        )) {
            is Kvp011ProofContextPreparation.Complete -> prepared.context
            is Kvp011ProofContextPreparation.Rejected -> throw GradleException(
                "KVP-011 preparation rejected: ${prepared.failure}",
            )
        }
}

/**
 * Proof transition: canonical packet/dependency paths plus repository observation ->
 * `Kvp011ProofContextPreparation`.
 *
 * Establishes the generated task definition, complete predecessor closure, clean relevant-input
 * digest, declared implementation-write scope, named cases, command, toolchain, and observed head.
 * Every expected boundary failure is a closed rejection; raw path extraction remains here.
 */
internal fun prepareKvp011ProofContext(
    exec: ExecOperations,
    root: Path,
    observedHead: DeliveryGeneration,
    packetPath: Path,
    kvp010Path: Path,
    kvp025Path: Path,
    kvp025ReportPath: Path,
    kvp031Path: Path,
    kvp031ReportPath: Path,
): Kvp011ProofContextPreparation {
    val (expectedPacket, version) = canonicalKvp011Packet()
    val packetRaw = when (val read = readKvp011TextFile(packetPath)) {
        is Kvp011TextFileRead.Complete -> read.text
        is Kvp011TextFileRead.Rejected -> return preparationRejected(
            Kvp011ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val packet = when (val admitted = admitTaskPacket(packetRaw, expectedPacket, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return preparationRejected(
            Kvp011ProofPreparationFailure.PACKET_REJECTED,
        )
    }
    val dependencies = when (val admitted = admitKvp011Dependencies(
        packet.packet,
        observedHead,
        kvp010Path,
        kvp025Path,
        kvp025ReportPath,
        kvp031Path,
        kvp031ReportPath,
    )) {
        is Kvp011DependencyAdmission.Complete -> admitted.dependencies
        is Kvp011DependencyAdmission.Rejected -> return preparationRejected(
            Kvp011ProofPreparationFailure.DEPENDENCY_REJECTED,
        )
    }
    val relevant = when (val admitted = admitKvp011RelevantInputs(
        exec, root, packet, dependencies,
    )) {
        is Kvp011RelevantInputAdmission.Complete -> admitted.digest
        is Kvp011RelevantInputAdmission.Rejected -> return preparationRejected(
            Kvp011ProofPreparationFailure.RELEVANT_INPUT_REJECTED,
        )
    }
    val scope = when (val admitted = admitKvp011ImplementationScope(
        exec,
        root,
        dependencies.implementationBaseline,
        observedHead,
        packet.packet.task.allowedWrites,
        hostedProductionCompositionOwnedWrites(packet.packet.task.id),
        hostedProductionCompositionCompanionWrites(packet.packet.task.id),
    )) {
        is Kvp011ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp011ImplementationScopeAdmission.Rejected -> return preparationRejected(
            Kvp011ProofPreparationFailure.IMPLEMENTATION_SCOPE_REJECTED,
        )
    }
    val cases = when (val admitted = expectedKvp011ProofCases(packet.packet)) {
        is Kvp011EvidenceAdmission.Complete -> admitted.evidence
        is Kvp011EvidenceAdmission.Rejected -> return preparationRejected(
            Kvp011ProofPreparationFailure.CASE_EXPECTATION_REJECTED,
        )
    }
    return Kvp011ProofContextPreparation.Complete(Kvp011ProofContext(
        version,
        packet,
        dependencies,
        cases,
        scope,
        relevant,
        packet.packet.kvp011CommandDigest(),
        currentKvp011ToolchainDigest(),
        observedHead,
    ))
}

@UntrackedTask(because = "Admits KVP-011 evidence and emits or reuses one v2 receipt")
abstract class ProveKvp011Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp010ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp025ReportFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp031ReportFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val evidenceFile: RegularFileProperty
    @get:InputFile abstract val decisionFile: RegularFileProperty
    @get:InputFile abstract val layoutReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp011ProofContext(
            execOperations,
            root,
            head,
            packetFile.get().asFile.toPath(),
            kvp010ReceiptFile.get().asFile.toPath(),
            kvp025ReceiptFile.get().asFile.toPath(),
            kvp025ReportFile.get().asFile.toPath(),
            kvp031ReceiptFile.get().asFile.toPath(),
            kvp031ReportFile.get().asFile.toPath(),
        )) {
            is Kvp011ProofContextPreparation.Complete -> prepared.context
            is Kvp011ProofContextPreparation.Rejected -> reject("context", prepared.failure)
        }
        val layout = read(layoutReportFile.get().asFile.toPath())
        val decision = when (read(decisionFile.get().asFile.toPath())) {
            "${Kvp011ProofDecision.REUSE.name}\n" -> Kvp011ProofDecision.REUSE
            "${Kvp011ProofDecision.EXECUTE.name}\n" -> Kvp011ProofDecision.EXECUTE
            else -> reject("decision", "malformed")
        }
        if (decision == Kvp011ProofDecision.REUSE) {
            val existing = admitKvp011ExistingProof(
                layout,
                read(receiptFile.get().asFile.toPath()),
                context,
                head,
            )
            if (existing !is Kvp011ExistingProofAdmission.Complete) {
                reject("reuse", "closure changed after preparation")
            }
            revalidateExactHead(root, AuthorityGitRevision(head.value))
            logComplete(existing.receipt, layout, "REUSED")
            return
        }
        when (val admitted = admitKvp011Evidence(
            read(evidenceFile.get().asFile.toPath()),
            context.packet.packet,
            layout,
        )) {
            is Kvp011EvidenceAdmission.Complete -> if (admitted.evidence != context.cases) {
                reject("evidence", "case mismatch")
            }
            is Kvp011EvidenceAdmission.Rejected -> reject("evidence", admitted.failure)
        }
        val expectedOutput = context.packet.packet.task.outputs.single().path
        val observedOutput = root.relativize(
            layoutReportFile.get().asFile.toPath().toAbsolutePath().normalize(),
        ).toString()
        if (expectedOutput != observedOutput) reject("output path", observedOutput)
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            head,
            context.receiptExpectation(layout),
            receiptFile.get().asFile.toPath(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logComplete(receipt, layout, "EXECUTED")
    }

    private fun logComplete(
        receipt: AdmittedTaskProofReceipt,
        layout: String,
        disposition: String,
    ) = logger.lifecycle(
        "KVP-011 COMPLETE ({}): misuse=REJECTED, legal=COMPLETE, output={}, receipt={}",
        disposition,
        sha256(layout).value,
        receipt.digest.value,
    )

    private fun read(path: Path): String = when (val read = readKvp011TextFile(path)) {
        is Kvp011TextFileRead.Complete -> read.text
        is Kvp011TextFileRead.Rejected -> reject("bounded file read", read.failure)
    }
    private fun reject(owner: String, failure: Any): Nothing =
        throw GradleException("KVP-011 $owner rejected: $failure")
}

/**
 * Proof transition: validated canonical delivery graph -> KVP-011 `TaskPacket` and program version.
 *
 * Establishes that all task fields and receipt policy come from the sole graph authority. Graph
 * rejection becomes a Gradle failure only at task registration; raw task fields are not accepted.
 */
internal fun canonicalKvp011Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-011"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-011 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

fun canonicalKvp011TaskPacket(): TaskPacket = canonicalKvp011Packet().first

/**
 * Proof transition: raw evidence `Path -> Kvp011TextFileRead`.
 *
 * Establishes bounded, regular, non-symlink UTF-8 task evidence. The finite filesystem failure is
 * preserved by [Kvp011TextFileRead.Rejected]; raw text may be extracted only by Gradle adapters.
 */
internal fun readKvp011TextFile(path: Path): Kvp011TextFileRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp011TextFileRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp011TextFileRead.Rejected(read.failure)
}

private fun preparationRejected(failure: Kvp011ProofPreparationFailure) =
    Kvp011ProofContextPreparation.Rejected(failure)
