package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp031BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
}

internal data class Kvp031ImplementationCommit(
    val revision: DeliveryGeneration,
    val changedPaths: List<String>,
)

internal class AdmittedKvp031ImplementationScope internal constructor(
    val commits: List<Kvp031ImplementationCommit>,
)

internal sealed interface Kvp031ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp031ImplementationScope) :
        Kvp031ImplementationScopeAdmission
    data class Rejected(val failure: Kvp031BoundaryFailure) :
        Kvp031ImplementationScopeAdmission
}

internal sealed interface Kvp031RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp031RelevantInputAdmission
    data class Rejected(val failure: Kvp031BoundaryFailure) : Kvp031RelevantInputAdmission
}

/**
 * Proof transition: admitted KVP-030 baseline/current head and graph-declared write roots ->
 * `Kvp031ImplementationScopeAdmission`.
 *
 * Establishes a nonempty, ordered KVP-031 commit delta after observing every checkpoint without a
 * pathspec. Earlier completed-task checkpoints are skipped until the first KVP-031-exclusive path;
 * subsequent mixed checkpoints must lie in the dependency-closed companion/task write union. Git
 * or scope failure remains finite rejection. Raw Git output exists only here.
 */
internal fun admitKvp031ImplementationScope(
    exec: ExecOperations,
    repositoryRoot: Path,
    predecessorHead: DeliveryGeneration,
    currentHead: DeliveryGeneration,
    allowedWrites: List<String>,
    companionWrites: List<String>,
): Kvp031ImplementationScopeAdmission {
    if (git(exec, repositoryRoot, listOf(
            "merge-base", "--is-ancestor", predecessorHead.value, currentHead.value,
        )).exitCode != 0
    ) return scopeRejected(Kvp031BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    val revisions = git(
        exec,
        repositoryRoot,
        listOf("rev-list", "--reverse", "${predecessorHead.value}..${currentHead.value}"),
    )
    if (revisions.exitCode != 0) {
        return scopeRejected(Kvp031BoundaryFailure.GIT_COMMAND_REJECTED)
    }
    val commits = mutableListOf<Kvp031ImplementationCommit>()
    var taskStarted = false
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git(
            exec,
            repositoryRoot,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.exitCode != 0) {
            return scopeRejected(Kvp031BoundaryFailure.GIT_COMMAND_REJECTED)
        }
        val observedPaths = changed.text.lineSequence().filter(String::isNotBlank).sorted().toList()
        if (!taskStarted) {
            taskStarted = observedPaths.any { path ->
                allowedWrites.any { scope -> path.inScope(scope) } &&
                    companionWrites.none { scope -> path.inScope(scope) }
            }
            if (!taskStarted) return@forEach
        }
        val taskPaths = observedPaths.filter { path ->
            allowedWrites.any { scope -> path.inScope(scope) }
        }
        if (taskPaths.isEmpty()) return@forEach
        val dependencyClosedBatchWrites = allowedWrites + companionWrites
        if (observedPaths.any { path ->
                dependencyClosedBatchWrites.none { scope -> path.inScope(scope) }
            }
        ) return scopeRejected(Kvp031BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        commits += Kvp031ImplementationCommit(DeliveryGeneration(revision), taskPaths)
    }
    return if (commits.isEmpty()) {
        scopeRejected(Kvp031BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    } else {
        Kvp031ImplementationScopeAdmission.Complete(
            AdmittedKvp031ImplementationScope(commits),
        )
    }
}

/**
 * Proof transition: structurally admitted prior report scope plus current Git head ->
 * `Kvp031ImplementationScopeAdmission`.
 *
 * Establishes that the prior report head remains an ancestor and that replaying the graph-declared
 * write policy to that exact head yields byte-identical commit evidence. Later unrelated commits
 * are deliberately outside this content-scoped transition.
 */
internal fun admitPriorKvp031ImplementationScope(
    exec: ExecOperations,
    repositoryRoot: Path,
    predecessorHead: DeliveryGeneration,
    currentHead: DeliveryGeneration,
    candidate: Kvp031PriorProofScopeCandidate,
    allowedWrites: List<String>,
    companionWrites: List<String>,
): Kvp031ImplementationScopeAdmission {
    if (git(exec, repositoryRoot, listOf(
            "merge-base", "--is-ancestor", candidate.reportHead.value, currentHead.value,
        )).exitCode != 0
    ) return scopeRejected(Kvp031BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    return when (val replayed = admitKvp031ImplementationScope(
        exec,
        repositoryRoot,
        predecessorHead,
        candidate.reportHead,
        allowedWrites,
        companionWrites,
    )) {
        is Kvp031ImplementationScopeAdmission.Complete -> if (
            replayed.scope.commits == candidate.commits
        ) replayed else scopeRejected(Kvp031BoundaryFailure.RELEVANT_INPUT_READ_REJECTED)
        is Kvp031ImplementationScopeAdmission.Rejected -> replayed
    }
}

/**
 * Proof transition: graph-declared read roots plus packet/dependency evidence ->
 * `Kvp031RelevantInputAdmission`.
 *
 * Establishes a deterministic digest over clean tracked files in only the declared roots, the
 * canonical packet, and every admitted predecessor digest. This boundary performs no repository
 * walk. Dirty, empty, unreadable, or Git-rejected closure is finite rejection.
 */
internal fun admitKvp031RelevantInputs(
    exec: ExecOperations,
    repositoryRoot: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp031Dependencies,
): Kvp031RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git(
        exec,
        repositoryRoot,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.exitCode != 0) return inputRejected(
        Kvp031BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    if (status.bytes.isNotEmpty()) return inputRejected(
        Kvp031BoundaryFailure.DIRTY_RELEVANT_INPUT,
    )
    val listed = git(exec, repositoryRoot, listOf("ls-files", "-z", "--") + roots)
    if (listed.exitCode != 0) return inputRejected(
        Kvp031BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp031BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inScope(it) }) return inputRejected(
            Kvp031BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
        )
        when (val read = readBoundaryFile(repositoryRoot.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp031BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    val closure = linkedMapOf<String, Any?>(
        "packetDigest" to packet.documentDigest.value,
        "dependencyReceiptDigests" to dependencies.digests,
        "trackedInputDigests" to digests,
    )
    return Kvp031RelevantInputAdmission.Complete(
        RelevantInputDigest(sha256(canonicalJson(closure)).value),
    )
}

private data class Kvp031GitObservation(val exitCode: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git(
    exec: ExecOperations,
    root: Path,
    arguments: List<String>,
): Kvp031GitObservation {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        args(arguments)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Kvp031GitObservation(result.exitValue, output.toByteArray())
}

private fun String.inScope(scope: String) = this == scope || startsWith("$scope/")

private fun scopeRejected(failure: Kvp031BoundaryFailure) =
    Kvp031ImplementationScopeAdmission.Rejected(failure)

private fun inputRejected(failure: Kvp031BoundaryFailure) =
    Kvp031RelevantInputAdmission.Rejected(failure)
