package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations
import org.gradle.util.GradleVersion

internal enum class Kvp025BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
}

internal sealed interface Kvp025ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp025ImplementationScope) :
        Kvp025ImplementationScopeAdmission
    data class Rejected(val failure: Kvp025BoundaryFailure) :
        Kvp025ImplementationScopeAdmission
}

internal sealed interface Kvp025RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp025RelevantInputAdmission
    data class Rejected(val failure: Kvp025BoundaryFailure) : Kvp025RelevantInputAdmission
}

internal sealed interface Kvp025ChangedPathsAdmission {
    data class Complete(val paths: List<String>) : Kvp025ChangedPathsAdmission
    data object Rejected : Kvp025ChangedPathsAdmission
}

/**
 * Proof transition: raw checkpoint paths plus graph-declared writes ->
 * `Kvp025ChangedPathsAdmission`.
 *
 * Establishes that the nonempty, sorted checkpoint delta is wholly owned by KVP-025. Empty or
 * out-of-scope deltas are the closed [Kvp025ChangedPathsAdmission.Rejected] state. Raw path text
 * remains at the Git boundary that calls this transition.
 */
internal fun admitKvp025ChangedPaths(
    paths: List<String>,
    allowedWrites: List<String>,
): Kvp025ChangedPathsAdmission {
    val sorted = paths.filter(String::isNotBlank).sorted()
    return if (sorted.isEmpty() || sorted.any { path ->
        allowedWrites.none { path.inScope(it) }
    }) {
        Kvp025ChangedPathsAdmission.Rejected
    } else {
        Kvp025ChangedPathsAdmission.Complete(sorted)
    }
}

/**
 * Proof transition: admitted predecessor/current heads plus graph-declared write roots ->
 * `Kvp025ImplementationScopeAdmission`.
 *
 * Establishes that every path in every checkpoint after the predecessor is owned by KVP-025's
 * graph-declared write scope, then preserves the complete admitted delta. Git/process failures and
 * malformed scoped deltas remain finite [Kvp025BoundaryFailure]. Raw Git output exists only here.
 */
internal fun admitKvp025ImplementationScope(
    exec: ExecOperations,
    repositoryRoot: Path,
    predecessorHead: DeliveryGeneration,
    currentHead: DeliveryGeneration,
    allowedWrites: List<String>,
): Kvp025ImplementationScopeAdmission {
    val ancestor = git(
        exec,
        repositoryRoot,
        listOf("merge-base", "--is-ancestor", predecessorHead.value, currentHead.value),
    )
    if (ancestor.exitCode != 0) {
        return rejectedScope(Kvp025BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    }
    val revisions = git(
        exec,
        repositoryRoot,
        listOf(
            "rev-list",
            "--reverse",
            "${predecessorHead.value}..${currentHead.value}",
        ),
    )
    if (revisions.exitCode != 0) {
        return rejectedScope(Kvp025BoundaryFailure.GIT_COMMAND_REJECTED)
    }
    val commits = mutableListOf<Kvp025ImplementationCommit>()
    for (revision in revisions.text.lineSequence().filter(String::isNotBlank)) {
        val changed = git(
            exec,
            repositoryRoot,
            listOf(
                "diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision,
            ),
        )
        if (changed.exitCode != 0) {
            return rejectedScope(Kvp025BoundaryFailure.GIT_COMMAND_REJECTED)
        }
        val paths = when (val admitted = admitKvp025ChangedPaths(
            changed.text.lineSequence().toList(),
            allowedWrites,
        )) {
            is Kvp025ChangedPathsAdmission.Complete -> admitted.paths
            Kvp025ChangedPathsAdmission.Rejected -> return rejectedScope(
                Kvp025BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE,
            )
        }
        commits += Kvp025ImplementationCommit(DeliveryGeneration(revision), paths)
    }
    return if (commits.isEmpty()) {
        rejectedScope(Kvp025BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    } else {
        Kvp025ImplementationScopeAdmission.Complete(AdmittedKvp025ImplementationScope(commits))
    }
}

/**
 * Proof transition: graph-declared read roots plus admitted packet/predecessor ->
 * `Kvp025RelevantInputAdmission`.
 *
 * Establishes a deterministic SHA-256 closure over only clean tracked files in declared roots,
 * the canonical packet, and predecessor receipt. No repository-wide traversal occurs. Dirty,
 * empty, unreadable, or Git-rejected input remains finite [Kvp025BoundaryFailure].
 */
internal fun admitKvp025RelevantInputs(
    exec: ExecOperations,
    repositoryRoot: Path,
    packet: AdmittedTaskPacketFile,
    predecessor: AdmittedLegacyReceiptPrefix,
): Kvp025RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git(
        exec,
        repositoryRoot,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.exitCode != 0) {
        return rejectedInput(Kvp025BoundaryFailure.GIT_COMMAND_REJECTED)
    }
    if (status.bytes.isNotEmpty()) {
        return rejectedInput(Kvp025BoundaryFailure.DIRTY_RELEVANT_INPUT)
    }
    val listed = git(exec, repositoryRoot, listOf("ls-files", "-z", "--") + roots)
    if (listed.exitCode != 0) {
        return rejectedInput(Kvp025BoundaryFailure.GIT_COMMAND_REJECTED)
    }
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000').filter(String::isNotEmpty)
    if (paths.isEmpty()) return rejectedInput(Kvp025BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val fileDigests = linkedMapOf<String, String>()
    paths.sorted().forEach { path ->
        if (roots.none { path.inScope(it) }) {
            return rejectedInput(Kvp025BoundaryFailure.RELEVANT_INPUT_READ_REJECTED)
        }
        when (val read = readBoundaryFile(repositoryRoot.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> fileDigests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return rejectedInput(
                Kvp025BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    val closure = linkedMapOf<String, Any?>(
        "packetDigest" to packet.documentDigest.value,
        "predecessorReceiptId" to predecessor.frontierReceiptId.value,
        "predecessorReceiptDigest" to predecessor.frontierReceiptDigest.value,
        "trackedInputDigests" to fileDigests,
    )
    return Kvp025RelevantInputAdmission.Complete(
        RelevantInputDigest(sha256(canonicalJson(closure)).value),
    )
}

internal fun Kvp025ProofReportContext.receiptExpectation(): TaskProofReceiptExpectation =
    when (val refined = TaskProofReceiptExpectation.refine(
        programVersion.value,
        packet.packet.receipt.receiptId.value,
        packet.packet.task.id.value,
        packet.packet.taskDefinitionDigest.value,
        mapOf(predecessor.frontierReceiptId.value to predecessor.frontierReceiptDigest.value),
        relevantInputDigest.value,
        commandDigest.value,
        toolchainDigest.value,
        completeObservations(),
        mapOf(
            packet.packet.task.outputs.single().path to proofReportOutputDigest().value,
        ),
        packet.packet.receipt.headPolicy.name,
    )) {
        is TaskProofReceiptExpectationRefinement.Complete -> refined.expectation
        is TaskProofReceiptExpectationRefinement.Rejected -> error(
            "KVP-025 receipt expectation rejected: ${refined.failure}",
        )
    }

internal fun TaskPacket.commandDigest() = TaskProofCommandDigest(
    sha256(
        canonicalJson(
            listOf(
                proofCommand.command,
                proofCommand.misuse.command,
                proofCommand.legalPath.command,
            ),
        ),
    ).value,
)

internal fun currentTaskProofToolchainDigest() = ToolchainDigest(
    sha256(
        canonicalJson(
            mapOf(
                "gradle" to GradleVersion.current().version,
                "javaRuntime" to System.getProperty("java.runtime.version"),
                "javaVendor" to System.getProperty("java.vendor"),
                "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
            ),
        ),
    ).value,
)

private fun Kvp025ProofReportContext.proofReportOutputDigest(): TaskProofOutputDigest =
    when (val admitted = admitKvp025ProofReport(canonicalKvp025ProofReport(this), this)) {
        is Kvp025ProofReportAdmission.Complete -> admitted.report.outputDigest
        is Kvp025ProofReportAdmission.Rejected -> error(
            "canonical KVP-025 proof report rejected: ${admitted.failure}",
        )
    }

private data class GitCommandObservation(
    val exitCode: Int,
    val bytes: ByteArray,
) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git(
    exec: ExecOperations,
    repositoryRoot: Path,
    arguments: List<String>,
): GitCommandObservation {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(repositoryRoot.toFile())
        executable("git")
        args(arguments)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return GitCommandObservation(result.exitValue, output.toByteArray())
}

private fun String.inScope(scope: String) = this == scope || startsWith("$scope/")

private fun rejectedScope(failure: Kvp025BoundaryFailure) =
    Kvp025ImplementationScopeAdmission.Rejected(failure)

private fun rejectedInput(failure: Kvp025BoundaryFailure) =
    Kvp025RelevantInputAdmission.Rejected(failure)
