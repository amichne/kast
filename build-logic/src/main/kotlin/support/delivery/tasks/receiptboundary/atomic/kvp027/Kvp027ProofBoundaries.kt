package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp027BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
}

internal data class Kvp027ImplementationCommit(
    val revision: DeliveryGeneration,
    val changedPaths: List<String>,
)

internal class AdmittedKvp027ImplementationScope internal constructor(
    val commits: List<Kvp027ImplementationCommit>,
)

internal sealed interface Kvp027ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp027ImplementationScope) :
        Kvp027ImplementationScopeAdmission
    data class Rejected(val failure: Kvp027BoundaryFailure) :
        Kvp027ImplementationScopeAdmission
}

internal sealed interface Kvp027RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp027RelevantInputAdmission
    data class Rejected(val failure: Kvp027BoundaryFailure) : Kvp027RelevantInputAdmission
}

/**
 * Proof transition: admitted KVP-026 baseline/current head and graph-declared write roots ->
 * `Kvp027ImplementationScopeAdmission`.
 *
 * Establishes a nonempty, ordered KVP-027 commit delta after observing every checkpoint without a
 * pathspec. Mixed delivery checkpoints must lie in the dependency-closed companion/task write
 * union until the first KVP-028-exclusive checkpoint. That checkpoint closes the delta so later
 * shared convention paths cannot be absorbed. Git or scope failure remains finite rejection.
 */
internal fun admitKvp027ImplementationScope(
    exec: ExecOperations,
    repositoryRoot: Path,
    predecessorHead: DeliveryGeneration,
    currentHead: DeliveryGeneration,
    allowedWrites: List<String>,
    companionWrites: List<String>,
    successorWrites: List<String>,
): Kvp027ImplementationScopeAdmission {
    if (git(exec, repositoryRoot, listOf(
            "merge-base", "--is-ancestor", predecessorHead.value, currentHead.value,
        )).exitCode != 0
    ) return scopeRejected(Kvp027BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    val revisions = git(
        exec,
        repositoryRoot,
        listOf("rev-list", "--reverse", "${predecessorHead.value}..${currentHead.value}"),
    )
    if (revisions.exitCode != 0) {
        return scopeRejected(Kvp027BoundaryFailure.GIT_COMMAND_REJECTED)
    }
    val commits = mutableListOf<Kvp027ImplementationCommit>()
    var successorStarted = false
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git(
            exec,
            repositoryRoot,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.exitCode != 0) {
            return scopeRejected(Kvp027BoundaryFailure.GIT_COMMAND_REJECTED)
        }
        val observedPaths = changed.text.lineSequence().filter(String::isNotBlank).sorted().toList()
        val dependencyClosedBatchWrites = allowedWrites + companionWrites
        if (observedPaths.any { path ->
                successorWrites.any { scope -> path.inScope(scope) } &&
                    dependencyClosedBatchWrites.none { scope -> path.inScope(scope) }
            }
        ) successorStarted = true
        if (successorStarted) return@forEach
        val taskPaths = observedPaths.filter { path ->
            allowedWrites.any { scope -> path.inScope(scope) }
        }
        if (taskPaths.isEmpty()) return@forEach
        if (observedPaths.any { path ->
                dependencyClosedBatchWrites.none { scope -> path.inScope(scope) }
            }
        ) return scopeRejected(Kvp027BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        commits += Kvp027ImplementationCommit(DeliveryGeneration(revision), taskPaths)
    }
    return if (commits.isEmpty()) {
        scopeRejected(Kvp027BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    } else {
        Kvp027ImplementationScopeAdmission.Complete(
            AdmittedKvp027ImplementationScope(commits),
        )
    }
}

/**
 * Proof transition: graph-declared read roots plus packet/dependency evidence ->
 * `Kvp027RelevantInputAdmission`.
 *
 * Establishes a deterministic digest over clean tracked files in only the declared roots, the
 * canonical packet, and every admitted predecessor digest. This boundary performs no repository
 * walk. Dirty, empty, unreadable, or Git-rejected closure is finite rejection.
 */
internal fun admitKvp027RelevantInputs(
    exec: ExecOperations,
    repositoryRoot: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp027Dependencies,
): Kvp027RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git(
        exec,
        repositoryRoot,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.exitCode != 0) return inputRejected(
        Kvp027BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    if (status.bytes.isNotEmpty()) return inputRejected(
        Kvp027BoundaryFailure.DIRTY_RELEVANT_INPUT,
    )
    val listed = git(exec, repositoryRoot, listOf("ls-files", "-z", "--") + roots)
    if (listed.exitCode != 0) return inputRejected(
        Kvp027BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp027BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inScope(it) }) return inputRejected(
            Kvp027BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
        )
        when (val read = readBoundaryFile(repositoryRoot.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp027BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    val closure = linkedMapOf<String, Any?>(
        "packetDigest" to packet.documentDigest.value,
        "dependencyReceiptDigests" to dependencies.digests,
        "trackedInputDigests" to digests,
    )
    return Kvp027RelevantInputAdmission.Complete(
        RelevantInputDigest(sha256(canonicalJson(closure)).value),
    )
}

private data class Kvp027GitObservation(val exitCode: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git(
    exec: ExecOperations,
    root: Path,
    arguments: List<String>,
): Kvp027GitObservation {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        args(arguments)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Kvp027GitObservation(result.exitValue, output.toByteArray())
}

private fun String.inScope(scope: String) = this == scope || startsWith("$scope/")

private fun scopeRejected(failure: Kvp027BoundaryFailure) =
    Kvp027ImplementationScopeAdmission.Rejected(failure)

private fun inputRejected(failure: Kvp027BoundaryFailure) =
    Kvp027RelevantInputAdmission.Rejected(failure)
