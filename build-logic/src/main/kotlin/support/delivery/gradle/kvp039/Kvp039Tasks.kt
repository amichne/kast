package support.delivery

import java.nio.file.Path
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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

internal enum class Kvp039ProofFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    RELEVANT_INPUT_REJECTED,
    IMPLEMENTATION_SCOPE_REJECTED,
    CASE_REJECTED,
    NEGATIVE_REJECTED,
    LEGAL_PATH_REJECTED,
    OUTPUT_PATH_REJECTED,
}

internal data class Kvp039ProofContext(
    val version: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependency: AdmittedKvp039Dependency,
    val relevantInputDigest: RelevantInputDigest,
    val scope: AdmittedKvp039ImplementationScope,
    val cases: Kvp039Cases,
    val report: Kvp039ExactHeadCiDocument,
    val reportRaw: String,
)

internal sealed interface Kvp039Preparation {
    data class Complete(val context: Kvp039ProofContext) : Kvp039Preparation
    data class Rejected(val failure: Kvp039ProofFailure) : Kvp039Preparation
}

private sealed interface Kvp039TaskTextRead {
    data class Complete(val raw: String) : Kvp039TaskTextRead
    data object Rejected : Kvp039TaskTextRead
}

@UntrackedTask(because = "Projects the current canonical KVP-039 graph packet")
abstract class GenerateKvp039TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp039Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.path039(), raw)
        if (admitTaskPacket(raw, packet, version) !is TaskPacketFileAdmission.Complete) {
            throw GradleException("KVP-039 generated packet rejected")
        }
        logger.lifecycle(
            "KVP-039 task packet admitted with definition digest {}",
            packet.taskDefinitionDigest.value,
        )
    }
}

@UntrackedTask(because = "Exercises the graph-named stale or merge-head CI misuse")
abstract class Kvp039NegativeTask : DefaultTask() {
    @get:InputFile abstract val workflowFile: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun prove() {
        val cases = when (val admitted = admitKvp039Cases(canonicalKvp039TaskPacket())) {
            is Kvp039CaseAdmission.Complete -> admitted.cases
            Kvp039CaseAdmission.Rejected -> throw GradleException("KVP-039 cases rejected")
        }
        val raw = read039TextOrFail(workflowFile.path039(), "workflow")
        val fixture = raw.replaceFirst(
            "ref: \${{ github.event.pull_request.head.sha || github.sha }}",
            "ref: \${{ github.sha }}",
        )
        if (fixture == raw || admitKvp039Workflow(
                fixture,
                DeliveryGeneration("0".repeat(40)),
                "0".repeat(64),
            ) !is Kvp039WorkflowAdmission.Rejected
        ) throw GradleException("KVP-039 named misuse was not rejected")
        val report = encodeKvp039Negative(cases)
        writeTextAtomically(reportFile.path039(), report)
        if (admitKvp039Negative(report, cases) !is Kvp039NegativeAdmission.Complete) {
            throw GradleException("KVP-039 negative evidence rejected")
        }
        logger.lifecycle("KVP-039 misuse REJECTED: {}", cases.misuseName)
    }
}

@UntrackedTask(because = "Refines the pull-request-head CI workflow and predecessor closure")
abstract class Kvp039ContractTask : DefaultTask() {
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val workflowFile: RegularFileProperty
    @get:InputFile abstract val kvp038ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp038ReportFile: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val packet = canonicalKvp039TaskPacket()
        val dependency = when (val admitted = admitKvp039Dependency(
            packet, head, kvp038ReceiptFile.path039(), kvp038ReportFile.path039(),
        )) {
            is Kvp039DependencyAdmission.Complete -> admitted.dependency
            is Kvp039DependencyAdmission.Rejected -> throw GradleException(
                "KVP-039 predecessor rejected: ${admitted.failure}",
            )
        }
        val workflow = read039TextOrFail(workflowFile.path039(), "workflow")
        val report = when (val admitted = admitKvp039Workflow(
            workflow, head, dependency.receiptDigest,
        )) {
            is Kvp039WorkflowAdmission.Complete -> admitted.report
            is Kvp039WorkflowAdmission.Qualified -> throw GradleException(
                "KVP-039 CI contract qualified instead of completing",
            )
            Kvp039WorkflowAdmission.Rejected -> throw GradleException(
                "KVP-039 CI contract rejected",
            )
        }
        writeTextAtomically(reportFile.path039(), encodeKvp039Report(report))
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-039 legal path COMPLETE at head {} with {} required gates",
            head.value,
            report.requiredGateCount,
        )
    }
}

@UntrackedTask(because = "Runs KVP-039's exact-head CI proof and emits one content receipt")
abstract class ProveKvp039Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp038ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp038ReportFile: RegularFileProperty
    @get:InputFile abstract val negativeReportFile: RegularFileProperty
    @get:InputFile abstract val exactHeadReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp039Context(
            execOperations,
            root,
            head,
            packetFile.path039(),
            kvp038ReceiptFile.path039(),
            kvp038ReportFile.path039(),
            negativeReportFile.path039(),
            exactHeadReportFile.path039(),
        )) {
            is Kvp039Preparation.Complete -> prepared.context
            is Kvp039Preparation.Rejected -> throw GradleException(
                "KVP-039 preparation rejected: ${prepared.failure}",
            )
        }
        val expected = context.packet.packet.task.outputs.single().path
        val observed = root.relativize(exactHeadReportFile.path039().toAbsolutePath().normalize())
            .toString()
        if (expected != observed) throw GradleException(
            "KVP-039 output rejected: ${Kvp039ProofFailure.OUTPUT_PATH_REJECTED}",
        )
        val receipt = issueTaskProofReceiptAtBoundary(
            root,
            head,
            context.receiptExpectation(),
            receiptFile.path039(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-039 COMPLETE (EXECUTED): misuse=REJECTED, legal=COMPLETE, receipt={}",
            receipt.digest.value,
        )
    }
}

/**
 * Proof transition: raw packet, predecessor, misuse, legal, repository, and scope evidence ->
 * `Kvp039Preparation`.
 *
 * Establishes one fully admitted KVP-039 capability carrying all proof required for receipt
 * issuance. Expected packet, dependency, input, scope, case, misuse, and legal failures remain
 * closed [Kvp039ProofFailure]; raw path extraction is permitted only at this Gradle boundary.
 */
internal fun prepareKvp039Context(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packetPath: Path,
    dependencyReceiptPath: Path,
    dependencyReportPath: Path,
    negativePath: Path,
    reportPath: Path,
): Kvp039Preparation {
    val (expected, version) = canonicalKvp039Packet()
    val packetRaw = when (val read = read039Text(packetPath)) {
        is Kvp039TaskTextRead.Complete -> read.raw
        Kvp039TaskTextRead.Rejected -> return rejected039(Kvp039ProofFailure.PACKET_REJECTED)
    }
    val packet = when (val admitted = admitTaskPacket(packetRaw, expected, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return rejected039(Kvp039ProofFailure.PACKET_REJECTED)
    }
    val dependency = when (val admitted = admitKvp039Dependency(
        packet.packet, head, dependencyReceiptPath, dependencyReportPath,
    )) {
        is Kvp039DependencyAdmission.Complete -> admitted.dependency
        is Kvp039DependencyAdmission.Rejected ->
            return rejected039(Kvp039ProofFailure.DEPENDENCY_REJECTED)
    }
    val relevant = when (val admitted = admitKvp039RelevantInputs(exec, root, packet, dependency)) {
        is Kvp039RelevantInputAdmission.Complete -> admitted.digest
        is Kvp039RelevantInputAdmission.Rejected ->
            return rejected039(Kvp039ProofFailure.RELEVANT_INPUT_REJECTED)
    }
    val scope = when (val admitted = admitKvp039ImplementationScope(
        exec, root, head, packet.packet,
    )) {
        is Kvp039ImplementationScopeAdmission.Complete -> admitted.scope
        is Kvp039ImplementationScopeAdmission.Rejected ->
            return rejected039(Kvp039ProofFailure.IMPLEMENTATION_SCOPE_REJECTED)
    }
    val cases = when (val admitted = admitKvp039Cases(packet.packet)) {
        is Kvp039CaseAdmission.Complete -> admitted.cases
        Kvp039CaseAdmission.Rejected -> return rejected039(Kvp039ProofFailure.CASE_REJECTED)
    }
    val negative = when (val read = read039Text(negativePath)) {
        is Kvp039TaskTextRead.Complete -> read.raw
        Kvp039TaskTextRead.Rejected -> return rejected039(Kvp039ProofFailure.NEGATIVE_REJECTED)
    }
    if (admitKvp039Negative(negative, cases) !is Kvp039NegativeAdmission.Complete) {
        return rejected039(Kvp039ProofFailure.NEGATIVE_REJECTED)
    }
    val reportRaw = when (val read = read039Text(reportPath)) {
        is Kvp039TaskTextRead.Complete -> read.raw
        Kvp039TaskTextRead.Rejected -> return rejected039(Kvp039ProofFailure.LEGAL_PATH_REJECTED)
    }
    val report = try {
        Json.decodeFromString(Kvp039ExactHeadCiDocument.serializer(), reportRaw)
    } catch (_: SerializationException) {
        return rejected039(Kvp039ProofFailure.LEGAL_PATH_REJECTED)
    } catch (_: IllegalArgumentException) {
        return rejected039(Kvp039ProofFailure.LEGAL_PATH_REJECTED)
    }
    if (
        encodeKvp039Report(report) != reportRaw || report.outcome != Kvp039Outcome.COMPLETE ||
        report.repositoryHead != head.value ||
        report.predecessorReceiptDigest != dependency.receiptDigest
    ) return rejected039(Kvp039ProofFailure.LEGAL_PATH_REJECTED)
    return Kvp039Preparation.Complete(Kvp039ProofContext(
        version, packet, dependency, relevant, scope, cases, report, reportRaw,
    ))
}

/** Validated canonical graph -> KVP-039 packet and stable proof-program version. */
internal fun canonicalKvp039Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-039"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-039 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

fun canonicalKvp039TaskPacket(): TaskPacket = canonicalKvp039Packet().first

/**
 * Proof transition: `Kvp039ProofContext -> TaskProofReceiptExpectation`.
 *
 * Preserves the admitted packet, exact-head predecessor, relevant inputs, command/toolchain
 * identity, legal observations, and output digest. Refinement failure is rendered only at this
 * issuance boundary; callers receive only the stronger expectation.
 */
internal fun Kvp039ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val output = packet.packet.task.outputs.single().path
    val observations = linkedMapOf(
        "misuseOutcome" to "REJECTED",
        "legalPathOutcome" to "COMPLETE",
        "repositoryHead" to report.repositoryHead,
        "workflowDigest" to report.workflowDigest,
        "predecessorReceiptDigest" to dependency.receiptDigest,
        "exactHeadCheckoutCount" to report.exactHeadCheckoutCount.toString(),
        "requiredGateCount" to report.requiredGateCount.toString(),
        "mergeHeadCheckoutCount" to report.mergeHeadCheckoutCount.toString(),
        "implementationCommitCount" to scope.commitCount.toString(),
        "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    )
    return when (val refined = TaskProofReceiptExpectation.refine(
        version.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        mapOf("KVP-038-COMPLETE" to dependency.receiptDigest),
        relevantInputDigest.value,
        packet.packet.kvp039CommandDigest().value,
        currentKvp039ToolchainDigest().value,
        observations,
        mapOf(output to sha256(reportRaw).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-039 receipt expectation rejected: ${refined.failure}",
        )
    }
}

private fun read039Text(path: Path): Kvp039TaskTextRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp039TaskTextRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp039TaskTextRead.Rejected
}

private fun read039TextOrFail(path: Path, name: String): String = when (val read = read039Text(path)) {
    is Kvp039TaskTextRead.Complete -> read.raw
    Kvp039TaskTextRead.Rejected -> throw GradleException("KVP-039 $name unreadable")
}

private fun rejected039(failure: Kvp039ProofFailure) = Kvp039Preparation.Rejected(failure)
private fun RegularFileProperty.path039() = get().asFile.toPath()
