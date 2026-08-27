package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.gradle.process.ExecOperations

internal enum class Kvp040InputFailure {
    GIT_REJECTED,
    DIRTY_WORKTREE,
    EMPTY_DIFF,
    DIFF_TOO_LARGE,
    DEPENDENCY_REJECTED,
    EVIDENCE_REJECTED,
}

internal class AdmittedKvp040Dependency internal constructor(val receiptDigest: String)

internal sealed interface Kvp040DependencyAdmission {
    data class Complete(val dependency: AdmittedKvp040Dependency) : Kvp040DependencyAdmission
    data object Rejected : Kvp040DependencyAdmission
}

internal sealed interface Kvp040TextRead {
    data class Complete(val raw: String) : Kvp040TextRead
    data object Rejected : Kvp040TextRead
}

private sealed interface Kvp040DigestAdmission {
    data class Complete(val digest: String) : Kvp040DigestAdmission
    data object Rejected : Kvp040DigestAdmission
}

internal data class Kvp040ReviewEvidence(
    val review: Kvp040ReviewDocument,
    val relevantInputDigest: RelevantInputDigest,
)

internal sealed interface Kvp040EvidenceAdmission {
    data class Complete(val evidence: Kvp040ReviewEvidence) : Kvp040EvidenceAdmission
    data class Rejected(val failure: Kvp040InputFailure) : Kvp040EvidenceAdmission
}

@Serializable
private data class Kvp040StaticEvidenceEntry(
    val authority: String,
    val sha256: String,
    val observationCount: Int,
    val outcome: String,
)

@Serializable
private data class Kvp040StaticSafetyInput(
    val schemaVersion: Int,
    val taskId: String,
    val publicInterface: String,
    val outcome: String,
    val hostedModuleCount: Int,
    val forbiddenAuthorityCount: Int,
    val sourceFileCount: Int,
    val sourceSetSha256: String,
    val scannedClassCount: Int,
    val transitiveArtifactCount: Int,
    val violationCount: Int,
    val evidence: List<Kvp040StaticEvidenceEntry>,
)

/**
 * Proof transition: `(TaskPacket, DeliveryGeneration, Path, Path) ->
 * Kvp040DependencyAdmission`.
 *
 * Establishes canonical KVP-039 receipt/report identity at the observed head. Missing, stale,
 * malformed, or mismatched evidence remains closed rejection; raw bytes are extracted only here.
 */
internal fun admitKvp040Dependency(
    packet: TaskPacket,
    head: DeliveryGeneration,
    receiptPath: Path,
    reportPath: Path,
): Kvp040DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value } != listOf("KVP-039-COMPLETE")) {
        return Kvp040DependencyAdmission.Rejected
    }
    val receiptRaw = when (val read = read040(receiptPath, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return Kvp040DependencyAdmission.Rejected
    }
    val receipt = when (val decoded = decodeTaskProofReceipt(receiptRaw)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return Kvp040DependencyAdmission.Rejected
    }
    val reportRaw = when (val read = read040(reportPath, MAX_RECEIPT_EVIDENCE_BYTES)) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return Kvp040DependencyAdmission.Rejected
    }
    val report = try {
        kvp040Json.decodeFromString(Kvp039ExactHeadCiDocument.serializer(), reportRaw)
    } catch (_: SerializationException) {
        return Kvp040DependencyAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp040DependencyAdmission.Rejected
    }
    val (expected, version) = canonicalKvp039Packet()
    val output = expected.task.outputs.single().path
    val complete = receipt.receiptId == expected.receipt.receiptId &&
        receipt.taskId == expected.task.id && receipt.programVersion == version &&
        receipt.taskDefinitionDigest.value == expected.taskDefinitionDigest.value &&
        receipt.commandDigest == expected.kvp039CommandDigest() &&
        receipt.dependencyReceiptDigests.keys == expected.receipt.dependencies &&
        receipt.headPolicy == TaskProofHeadPolicy.CONTENT_SCOPED &&
        receipt.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value } ==
        mapOf(output to sha256(reportRaw).value) &&
        receipt.receiptDigest == receipt.derivedDigest() &&
        encodeTaskProofReceipt(receipt) == receiptRaw && encodeKvp039Report(report) == reportRaw &&
        receipt.observedRepositoryHead == head && report.repositoryHead == head.value &&
        report.outcome == Kvp039Outcome.COMPLETE
    return if (complete) Kvp040DependencyAdmission.Complete(
        AdmittedKvp040Dependency(receipt.receiptDigest.value),
    ) else Kvp040DependencyAdmission.Rejected
}

/**
 * Proof transition: exact-head Git state plus admitted reports -> `Kvp040EvidenceAdmission`.
 *
 * Establishes a clean merge-base-to-head diff and one digest-identified authority for every
 * graph-required review area. Dirty, missing, stale, oversized, malformed, qualified, or
 * unsupported evidence remains finite [Kvp040InputFailure]; raw Git and file bytes are extracted
 * only at this review boundary.
 */
internal fun collectKvp040Evidence(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packetDigest: String,
    dependency: AdmittedKvp040Dependency,
): Kvp040EvidenceAdmission {
    val status = git040(exec, root, listOf("status", "--porcelain=v1", "--untracked-files=all"))
    if (status.code != 0) return rejected040Input(Kvp040InputFailure.GIT_REJECTED)
    if (status.bytes.isNotEmpty()) return rejected040Input(Kvp040InputFailure.DIRTY_WORKTREE)
    val base = git040(exec, root, listOf("merge-base", head.value, "origin/main"))
    if (base.code != 0 || !base.text.matches(Regex("[0-9a-f]{40}"))) {
        return rejected040Input(Kvp040InputFailure.GIT_REJECTED)
    }
    val names = git040(exec, root, listOf(
        "diff", "--name-only", "-z", "--diff-filter=ACMRT", "${base.text}..${head.value}", "--",
    ))
    val diff = git040(exec, root, listOf(
        "diff", "--no-ext-diff", "--binary", "--full-index", "${base.text}..${head.value}", "--",
    ))
    if (names.code != 0 || diff.code != 0) {
        return rejected040Input(Kvp040InputFailure.GIT_REJECTED)
    }
    if (diff.bytes.size > KVP040_MAX_DIFF_BYTES) {
        return rejected040Input(Kvp040InputFailure.DIFF_TOO_LARGE)
    }
    val changed = names.bytes.toString(Charsets.UTF_8).split('\u0000').filter(String::isNotEmpty)
    if (changed.isEmpty()) return rejected040Input(Kvp040InputFailure.EMPTY_DIFF)

    val program = when (val digest = evidence040(root, KVP040_PROGRAM_PROJECTION)) {
        is Kvp040DigestAdmission.Complete -> digest.digest
        Kvp040DigestAdmission.Rejected -> return evidence040Rejected()
    }
    val requirements = when (val digest = evidence040(root, KVP040_REQUIREMENT_PROJECTION)) {
        is Kvp040DigestAdmission.Complete -> digest.digest
        Kvp040DigestAdmission.Rejected -> return evidence040Rejected()
    }
    val staticRaw = when (
        val read = read040(root.resolve(KVP040_STATIC_REPORT), MAX_RECEIPT_EVIDENCE_BYTES)
    ) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return evidence040Rejected()
    }
    val static = try {
        kvp040Json.decodeFromString(Kvp040StaticSafetyInput.serializer(), staticRaw)
    } catch (_: SerializationException) {
        return evidence040Rejected()
    } catch (_: IllegalArgumentException) {
        return evidence040Rejected()
    }
    if (
        static.schemaVersion != 1 || static.taskId != "KVP-032" ||
        static.publicInterface != "VfsPassiveStaticProof" || static.outcome != "COMPLETE" ||
        static.violationCount != 0 || static.hostedModuleCount <= 0 ||
        static.scannedClassCount <= 0 || static.transitiveArtifactCount <= 0 ||
        static.evidence.any { it.outcome != "COMPLETE" || !it.sha256.digest040() }
    ) return evidence040Rejected()
    val installedRaw = when (
        val read = read040(root.resolve(KVP040_INSTALLED_REPORT), MAX_RECEIPT_EVIDENCE_BYTES)
    ) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return evidence040Rejected()
    }
    val installed = when (val admitted = admitKvp034Report(
        installedRaw,
        KastVfsPassiveReusedIndexProgram.validated.program.installedMetrics,
        head,
    )) {
        is Kvp034ReportAdmission.Complete -> admitted.report
        is Kvp034ReportAdmission.Qualified, Kvp034ReportAdmission.Rejected ->
            return evidence040Rejected()
    }
    if (installed.operations.size != 4 || installed.metrics.size != 26) {
        return evidence040Rejected()
    }
    val cleanRaw = when (
        val read = read040(root.resolve(KVP040_CLEAN_REPORT), MAX_RECEIPT_EVIDENCE_BYTES)
    ) {
        is Kvp040TextRead.Complete -> read.raw
        Kvp040TextRead.Rejected -> return evidence040Rejected()
    }
    val clean = try {
        kvp040Json.decodeFromString(Kvp038CleanCheckoutDocument.serializer(), cleanRaw)
    } catch (_: SerializationException) {
        return evidence040Rejected()
    } catch (_: IllegalArgumentException) {
        return evidence040Rejected()
    }
    if (
        clean.outcome != Kvp038Outcome.COMPLETE || clean.repositoryHead != head.value ||
        clean.detachedHead != head.value || !clean.projectionDiffClean ||
        !clean.structuralGatesPassed || !clean.installedAcceptancePassed
    ) return evidence040Rejected()

    val diffDigest = sha256Bytes(diff.bytes)
    val staticDigest = sha256(staticRaw).value
    val installedDigest = sha256(installedRaw).value
    val cleanDigest = sha256(cleanRaw).value
    val coverage = listOf(
        Kvp040CoverageDocument(
            Kvp040CoverageArea.ACTUAL_EXACT_HEAD_DIFF,
            "git diff ${base.text}..${head.value}",
            diffDigest,
        ),
        Kvp040CoverageDocument(
            Kvp040CoverageArea.GENERATED_PROJECTIONS,
            KVP040_PROGRAM_PROJECTION,
            program,
        ),
        Kvp040CoverageDocument(
            Kvp040CoverageArea.SCHEMAS,
            KVP040_REQUIREMENT_PROJECTION,
            requirements,
        ),
        Kvp040CoverageDocument(Kvp040CoverageArea.MODULE_EDGES, KVP040_STATIC_REPORT, staticDigest),
        Kvp040CoverageDocument(
            Kvp040CoverageArea.FORBIDDEN_EFFECTS,
            KVP040_STATIC_REPORT,
            staticDigest,
        ),
        Kvp040CoverageDocument(
            Kvp040CoverageArea.PUBLIC_BEHAVIOR,
            KVP040_INSTALLED_REPORT,
            installedDigest,
        ),
        Kvp040CoverageDocument(
            Kvp040CoverageArea.INSTALLED_EVIDENCE,
            KVP040_CLEAN_REPORT,
            cleanDigest,
        ),
    )
    val review = Kvp040ReviewDocument(
        1, "KVP-040", Kvp040Outcome.COMPLETE, head.value, base.text, diffDigest,
        changed.size, coverage, emptyList(), 0,
    )
    if (admitKvp040Review(review, head) !is Kvp040ReviewAdmission.Complete) {
        return evidence040Rejected()
    }
    val relevant = sha256(canonicalJson(linkedMapOf(
        "packetDigest" to packetDigest,
        "dependencyReceiptDigest" to dependency.receiptDigest,
        "baseHead" to base.text,
        "diffDigest" to diffDigest,
        "changedPathsDigest" to sha256(canonicalJson(changed.sorted())).value,
        "coverageDigests" to coverage.associate { it.area.name to it.evidenceDigest },
    ))).value
    return Kvp040EvidenceAdmission.Complete(Kvp040ReviewEvidence(
        review,
        RelevantInputDigest(relevant),
    ))
}

private data class Git040(val code: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git040(exec: ExecOperations, root: Path, args: List<String>): Git040 {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        this.args(args)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Git040(result.exitValue, output.toByteArray())
}

private fun evidence040(root: Path, path: String): Kvp040DigestAdmission = when (
    val read = read040(root.resolve(path), MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is Kvp040TextRead.Complete -> Kvp040DigestAdmission.Complete(sha256(read.raw).value)
    Kvp040TextRead.Rejected -> Kvp040DigestAdmission.Rejected
}

/**
 * Proof transition: `(Path, Long) -> Kvp040TextRead`.
 *
 * Establishes a bounded regular-file read. Missing, non-regular, or oversized files remain closed
 * rejection; raw text is exposed only to the KVP-040 codec and evidence boundaries.
 */
internal fun read040(path: Path, maximum: Long): Kvp040TextRead = when (
    val read = readBoundaryFile(path, maximum)
) {
    is BoundaryFileRead.Complete -> Kvp040TextRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp040TextRead.Rejected
}

private fun String.digest040() = matches(Regex("[0-9a-f]{64}"))
private fun evidence040Rejected() = rejected040Input(Kvp040InputFailure.EVIDENCE_REJECTED)
private fun rejected040Input(failure: Kvp040InputFailure) = Kvp040EvidenceAdmission.Rejected(failure)
private const val KVP040_MAX_DIFF_BYTES = 32L * 1024 * 1024
internal const val KVP040_PROGRAM_PROJECTION =
    "gradle/delivery/kast-vfs-passive-reused-index-program.json"
internal const val KVP040_REQUIREMENT_PROJECTION =
    "gradle/delivery/kast-vfs-passive-requirements.json"
internal const val KVP040_STATIC_REPORT = "build/reports/ide-hosted/KVP-032-static-safety.json"
internal const val KVP040_INSTALLED_REPORT = "build/reports/ide-hosted/KVP-034-installed.json"
internal const val KVP040_CLEAN_REPORT = "build/reports/ide-hosted/KVP-038-clean-checkout.json"
