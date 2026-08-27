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

internal enum class Kvp040ProofFailure {
    PACKET_REJECTED,
    DEPENDENCY_REJECTED,
    CASE_REJECTED,
    NEGATIVE_REJECTED,
    EVIDENCE_REJECTED,
    LEGAL_PATH_REJECTED,
    OUTPUT_PATH_REJECTED,
}

internal data class Kvp040ProofContext(
    val version: TaskProofProgramVersion,
    val packet: AdmittedTaskPacketFile,
    val dependency: AdmittedKvp040Dependency,
    val cases: Kvp040Cases,
    val evidence: Kvp040ReviewEvidence,
    val reportRaw: String,
)

internal sealed interface Kvp040Preparation {
    data class Complete(val context: Kvp040ProofContext) : Kvp040Preparation
    data class Rejected(val failure: Kvp040ProofFailure) : Kvp040Preparation
}

@UntrackedTask(because = "Projects the current canonical KVP-040 graph packet")
abstract class GenerateKvp040TaskPacketTask : DefaultTask() {
    @get:OutputFile abstract val packetFile: RegularFileProperty

    @TaskAction fun generate() {
        val (packet, version) = canonicalKvp040Packet()
        val raw = encodeTaskPacket(packet, version)
        writeTextAtomically(packetFile.path040(), raw)
        if (admitTaskPacket(raw, packet, version) !is TaskPacketFileAdmission.Complete) {
            throw GradleException("KVP-040 generated packet rejected")
        }
        logger.lifecycle(
            "KVP-040 task packet admitted with definition digest {}",
            packet.taskDefinitionDigest.value,
        )
    }
}

@UntrackedTask(because = "Exercises the graph-named stale exact-head review misuse")
abstract class Kvp040NegativeTask : DefaultTask() {
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun prove() {
        val cases = admittedKvp040Cases()
        val head = DeliveryGeneration("a".repeat(40))
        val fixture = Kvp040ReviewDocument(
            1, "KVP-040", Kvp040Outcome.COMPLETE, head.value, "b".repeat(40),
            "c".repeat(64), 1,
            Kvp040CoverageArea.entries.map {
                Kvp040CoverageDocument(it, "fixture/${it.name}", "d".repeat(64))
            },
            emptyList(), 0,
        )
        if (admitKvp040Review(
                fixture,
                DeliveryGeneration("e".repeat(40)),
            ) !is Kvp040ReviewAdmission.Rejected
        ) throw GradleException("KVP-040 stale-head misuse was not rejected")
        val raw = encodeKvp040Negative(cases)
        writeTextAtomically(reportFile.path040(), raw)
        if (admitKvp040Negative(raw, cases) !is Kvp040NegativeAdmission.Complete) {
            throw GradleException("KVP-040 negative evidence rejected")
        }
        logger.lifecycle("KVP-040 misuse REJECTED: {}", cases.misuseName)
    }
}

@UntrackedTask(because = "Reviews the exact-head diff and admitted hosted evidence")
abstract class Kvp040ReviewTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val kvp039ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp039ReportFile: RegularFileProperty
    @get:OutputFile abstract val structuredReviewFile: RegularFileProperty
    @get:OutputFile abstract val renderedReviewFile: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun review() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val (packet, version) = canonicalKvp040Packet()
        val packetRaw = encodeTaskPacket(packet, version)
        val dependency = when (val admitted = admitKvp040Dependency(
            packet, head, kvp039ReceiptFile.path040(), kvp039ReportFile.path040(),
        )) {
            is Kvp040DependencyAdmission.Complete -> admitted.dependency
            Kvp040DependencyAdmission.Rejected -> throw GradleException(
                "KVP-040 predecessor rejected",
            )
        }
        val evidence = when (val collected = collectKvp040Evidence(
            execOperations, root, head, sha256(packetRaw).value, dependency,
        )) {
            is Kvp040EvidenceAdmission.Complete -> collected.evidence
            is Kvp040EvidenceAdmission.Rejected -> throw GradleException(
                "KVP-040 review evidence rejected: ${collected.failure}",
            )
        }
        val raw = encodeKvp040Review(evidence.review)
        writeTextAtomically(structuredReviewFile.path040(), raw)
        writeTextAtomically(renderedReviewFile.path040(), renderKvp040Review(evidence.review))
        writeTextAtomically(reportFile.path040(), raw)
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-040 legal path COMPLETE: {} files, {} findings",
            evidence.review.changedFileCount,
            evidence.review.findings.size,
        )
    }
}

@UntrackedTask(because = "Runs KVP-040's exact-diff review and emits one content receipt")
abstract class ProveKvp040Task : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFile abstract val packetFile: RegularFileProperty
    @get:InputFile abstract val kvp039ReceiptFile: RegularFileProperty
    @get:InputFile abstract val kvp039ReportFile: RegularFileProperty
    @get:InputFile abstract val negativeReportFile: RegularFileProperty
    @get:InputFile abstract val structuredReviewFile: RegularFileProperty
    @get:InputFile abstract val renderedReviewFile: RegularFileProperty
    @get:InputFile abstract val reviewReportFile: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction fun prove() {
        val root = Path.of(repositoryRootPath.get()).toAbsolutePath().normalize()
        val head = DeliveryGeneration(observeExactHead(root).value)
        val context = when (val prepared = prepareKvp040Context(
            execOperations, root, head, packetFile.path040(), kvp039ReceiptFile.path040(),
            kvp039ReportFile.path040(), negativeReportFile.path040(),
            structuredReviewFile.path040(), renderedReviewFile.path040(),
            reviewReportFile.path040(),
        )) {
            is Kvp040Preparation.Complete -> prepared.context
            is Kvp040Preparation.Rejected -> throw GradleException(
                "KVP-040 preparation rejected: ${prepared.failure}",
            )
        }
        val expected = context.packet.packet.task.outputs.single().path
        val observed = root.relativize(reviewReportFile.path040().toAbsolutePath().normalize())
            .toString()
        if (expected != observed) throw GradleException(
            "KVP-040 output rejected: ${Kvp040ProofFailure.OUTPUT_PATH_REJECTED}",
        )
        val receipt = issueTaskProofReceiptAtBoundary(
            root, head, context.receiptExpectation(), receiptFile.path040(),
        )
        revalidateExactHead(root, AuthorityGitRevision(head.value))
        logger.lifecycle(
            "KVP-040 COMPLETE (EXECUTED): misuse=REJECTED, legal=COMPLETE, receipt={}",
            receipt.digest.value,
        )
    }
}

/**
 * Proof transition: raw packet, predecessor, misuse, and review files -> `Kvp040Preparation`.
 *
 * Establishes one canonical exact-head review capability with complete authority coverage and
 * digest-bound output. Packet, dependency, case, evidence, and output failures remain closed
 * [Kvp040ProofFailure]; raw bytes are extracted only at this Gradle proof boundary.
 */
internal fun prepareKvp040Context(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packetPath: Path,
    dependencyReceiptPath: Path,
    dependencyReportPath: Path,
    negativePath: Path,
    structuredPath: Path,
    renderedPath: Path,
    reportPath: Path,
): Kvp040Preparation {
    val (expected, version) = canonicalKvp040Packet()
    val packetRaw = when (val read = read040(packetPath, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return rejected040(Kvp040ProofFailure.PACKET_REJECTED)
    }
    val packet = when (val admitted = admitTaskPacket(packetRaw, expected, version)) {
        is TaskPacketFileAdmission.Complete -> admitted.admitted
        is TaskPacketFileAdmission.Rejected -> return rejected040(Kvp040ProofFailure.PACKET_REJECTED)
    }
    val dependency = when (val admitted = admitKvp040Dependency(
        packet.packet, head, dependencyReceiptPath, dependencyReportPath,
    )) {
        is Kvp040DependencyAdmission.Complete -> admitted.dependency
        Kvp040DependencyAdmission.Rejected ->
            return rejected040(Kvp040ProofFailure.DEPENDENCY_REJECTED)
    }
    val cases = when (val admitted = admitKvp040Cases(packet.packet)) {
        is Kvp040CaseAdmission.Complete -> admitted.cases
        Kvp040CaseAdmission.Rejected -> return rejected040(Kvp040ProofFailure.CASE_REJECTED)
    }
    val negative = when (val read = read040(negativePath, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return rejected040(Kvp040ProofFailure.NEGATIVE_REJECTED)
    }
    if (admitKvp040Negative(negative, cases) !is Kvp040NegativeAdmission.Complete) {
        return rejected040(Kvp040ProofFailure.NEGATIVE_REJECTED)
    }
    val evidence = when (val collected = collectKvp040Evidence(
        exec, root, head, packet.documentDigest.value, dependency,
    )) {
        is Kvp040EvidenceAdmission.Complete -> collected.evidence
        is Kvp040EvidenceAdmission.Rejected -> return rejected040(Kvp040ProofFailure.EVIDENCE_REJECTED)
    }
    val reportRaw = when (val read = read040(reportPath, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return rejected040(Kvp040ProofFailure.LEGAL_PATH_REJECTED)
    }
    val structuredRaw = when (val read = read040(structuredPath, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return rejected040(Kvp040ProofFailure.LEGAL_PATH_REJECTED)
    }
    val renderedRaw = when (val read = read040(renderedPath, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return rejected040(Kvp040ProofFailure.LEGAL_PATH_REJECTED)
    }
    if (
        reportRaw != structuredRaw || reportRaw != encodeKvp040Review(evidence.review) ||
        renderedRaw != renderKvp040Review(evidence.review) ||
        decodeKvp040Review(reportRaw) != Kvp040ReviewDecoding.Complete(evidence.review) ||
        admitKvp040Review(evidence.review, head) !is Kvp040ReviewAdmission.Complete
    ) return rejected040(Kvp040ProofFailure.LEGAL_PATH_REJECTED)
    return Kvp040Preparation.Complete(Kvp040ProofContext(
        version, packet, dependency, cases, evidence, reportRaw,
    ))
}

/** Validated canonical graph -> KVP-040 packet and stable proof-program version. */
internal fun canonicalKvp040Packet(): Pair<TaskPacket, TaskProofProgramVersion> {
    val packet = when (val admitted = KastVfsPassiveReusedIndexProgram.validated.packet(
        TaskId("KVP-040"),
    )) {
        is TaskPacketAdmission.Complete -> admitted.packet
        is TaskPacketAdmission.Rejected -> throw GradleException(
            "canonical KVP-040 packet rejected: ${admitted.failure}",
        )
    }
    return packet to TaskProofProgramVersion(TASK_PROOF_PROGRAM_VERSION)
}

fun canonicalKvp040TaskPacket(): TaskPacket = canonicalKvp040Packet().first

/**
 * Proof transition: `Kvp040ProofContext -> TaskProofReceiptExpectation`.
 *
 * Preserves graph, predecessor, relevant-input, command, toolchain, observed review, and output
 * evidence. Refinement failure is rendered only at issuance; callers receive the capability.
 */
internal fun Kvp040ProofContext.receiptExpectation(): TaskProofReceiptExpectation {
    val output = packet.packet.task.outputs.single().path
    val observations = linkedMapOf(
        "misuseOutcome" to "REJECTED",
        "legalPathOutcome" to "COMPLETE",
        "repositoryHead" to evidence.review.repositoryHead,
        "baseHead" to evidence.review.baseHead,
        "diffDigest" to evidence.review.diffDigest,
        "changedFileCount" to evidence.review.changedFileCount.toString(),
        "coverageAreaCount" to evidence.review.coverage.size.toString(),
        "findingCount" to evidence.review.findings.size.toString(),
        "unresolvedValidFindingCount" to evidence.review.unresolvedValidFindingCount.toString(),
        "predecessorReceiptDigest" to dependency.receiptDigest,
        "forbiddenWorkEnforcementCount" to cases.forbiddenWork.size.toString(),
    )
    return when (val refined = TaskProofReceiptExpectation.refine(
        version.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        mapOf("KVP-039-COMPLETE" to dependency.receiptDigest),
        evidence.relevantInputDigest.value,
        packet.packet.kvp040CommandDigest().value,
        currentKvp040ToolchainDigest().value,
        observations,
        mapOf(output to sha256(reportRaw).value),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-040 receipt expectation rejected: ${refined.failure}",
        )
    }
}

internal fun renderKvp040Review(review: Kvp040ReviewDocument): String = buildString {
    appendLine("# KVP-040 exact-head review")
    appendLine()
    appendLine("- Outcome: ${review.outcome}")
    appendLine("- Head: `${review.repositoryHead}`")
    appendLine("- Base: `${review.baseHead}`")
    appendLine("- Diff digest: `${review.diffDigest}`")
    appendLine("- Changed files: ${review.changedFileCount}")
    appendLine("- Valid unresolved findings: ${review.unresolvedValidFindingCount}")
    appendLine()
    appendLine("## Coverage")
    review.coverage.forEach { appendLine("- ${it.area}: `${it.evidenceDigest}` (${it.authority})") }
    appendLine()
    appendLine("## Findings")
    if (review.findings.isEmpty()) appendLine("No valid or invalid findings were recorded.")
    review.findings.forEach { appendLine("- ${it.id}: ${it.validity} at ${it.location}") }
}

private fun admittedKvp040Cases(): Kvp040Cases = when (
    val admitted = admitKvp040Cases(canonicalKvp040TaskPacket())
) {
    is Kvp040CaseAdmission.Complete -> admitted.cases
    Kvp040CaseAdmission.Rejected -> throw GradleException("KVP-040 cases rejected")
}

private fun rejected040(failure: Kvp040ProofFailure) = Kvp040Preparation.Rejected(failure)
private fun RegularFileProperty.path040() = get().asFile.toPath()
