package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp028BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
}

internal data class Kvp028ImplementationCommit(
    val revision: DeliveryGeneration,
    val changedPaths: List<String>,
)

internal class AdmittedKvp028ImplementationScope internal constructor(
    val commits: List<Kvp028ImplementationCommit>,
)

internal sealed interface Kvp028ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp028ImplementationScope) :
        Kvp028ImplementationScopeAdmission
    data class Rejected(val failure: Kvp028BoundaryFailure) :
        Kvp028ImplementationScopeAdmission
}

internal sealed interface Kvp028RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp028RelevantInputAdmission
    data class Rejected(val failure: Kvp028BoundaryFailure) : Kvp028RelevantInputAdmission
}

/**
 * Proof transition: admitted KVP-026 baseline/current head and graph-declared write roots ->
 * `Kvp028ImplementationScopeAdmission`.
 *
 * Establishes a nonempty, ordered KVP-028 commit delta after observing every checkpoint without a
 * pathspec. Mixed delivery checkpoints must lie in the dependency-closed companion/task write
 * union, while the preserved delta contains only KVP-028-owned paths. Git or scope failure remains
 * finite rejection. Raw Git output exists only here.
 */
internal fun admitKvp028ImplementationScope(
    exec: ExecOperations,
    repositoryRoot: Path,
    predecessorHead: DeliveryGeneration,
    currentHead: DeliveryGeneration,
    allowedWrites: List<String>,
    companionWrites: List<String>,
): Kvp028ImplementationScopeAdmission {
    if (git(exec, repositoryRoot, listOf(
            "merge-base", "--is-ancestor", predecessorHead.value, currentHead.value,
        )).exitCode != 0
    ) return scopeRejected(Kvp028BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    val revisions = git(
        exec,
        repositoryRoot,
        listOf("rev-list", "--reverse", "${predecessorHead.value}..${currentHead.value}"),
    )
    if (revisions.exitCode != 0) {
        return scopeRejected(Kvp028BoundaryFailure.GIT_COMMAND_REJECTED)
    }
    val commits = mutableListOf<Kvp028ImplementationCommit>()
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git(
            exec,
            repositoryRoot,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.exitCode != 0) {
            return scopeRejected(Kvp028BoundaryFailure.GIT_COMMAND_REJECTED)
        }
        val observedPaths = changed.text.lineSequence().filter(String::isNotBlank).sorted().toList()
        val taskPaths = observedPaths.filter { path ->
            allowedWrites.any { scope -> path.inScope(scope) }
        }
        if (taskPaths.isEmpty()) return@forEach
        val dependencyClosedBatchWrites = allowedWrites + companionWrites
        if (observedPaths.any { path ->
                dependencyClosedBatchWrites.none { scope -> path.inScope(scope) }
            }
        ) return scopeRejected(Kvp028BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        commits += Kvp028ImplementationCommit(DeliveryGeneration(revision), taskPaths)
    }
    return if (commits.isEmpty()) {
        scopeRejected(Kvp028BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    } else {
        Kvp028ImplementationScopeAdmission.Complete(
            AdmittedKvp028ImplementationScope(commits),
        )
    }
}

/**
 * Proof transition: graph-declared read roots plus packet/dependency evidence ->
 * `Kvp028RelevantInputAdmission`.
 *
 * Establishes a deterministic digest over clean tracked files in only the declared roots, the
 * canonical packet, and every admitted predecessor digest. This boundary performs no repository
 * walk. Dirty, empty, unreadable, or Git-rejected closure is finite rejection.
 */
internal fun admitKvp028RelevantInputs(
    exec: ExecOperations,
    repositoryRoot: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp028Dependencies,
): Kvp028RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git(
        exec,
        repositoryRoot,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.exitCode != 0) return inputRejected(
        Kvp028BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    if (status.bytes.isNotEmpty()) return inputRejected(
        Kvp028BoundaryFailure.DIRTY_RELEVANT_INPUT,
    )
    val listed = git(exec, repositoryRoot, listOf("ls-files", "-z", "--") + roots)
    if (listed.exitCode != 0) return inputRejected(
        Kvp028BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp028BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inScope(it) }) return inputRejected(
            Kvp028BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
        )
        when (val read = readBoundaryFile(repositoryRoot.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp028BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    val closure = linkedMapOf<String, Any?>(
        "packetDigest" to packet.documentDigest.value,
        "dependencyReceiptDigests" to dependencies.digests,
        "trackedInputDigests" to digests,
    )
    return Kvp028RelevantInputAdmission.Complete(
        RelevantInputDigest(sha256(canonicalJson(closure)).value),
    )
}

private data class Kvp028GitObservation(val exitCode: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git(
    exec: ExecOperations,
    root: Path,
    arguments: List<String>,
): Kvp028GitObservation {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        args(arguments)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Kvp028GitObservation(result.exitValue, output.toByteArray())
}

private fun String.inScope(scope: String) = this == scope || startsWith("$scope/")

private fun scopeRejected(failure: Kvp028BoundaryFailure) =
    Kvp028ImplementationScopeAdmission.Rejected(failure)

private fun inputRejected(failure: Kvp028BoundaryFailure) =
    Kvp028RelevantInputAdmission.Rejected(failure)
