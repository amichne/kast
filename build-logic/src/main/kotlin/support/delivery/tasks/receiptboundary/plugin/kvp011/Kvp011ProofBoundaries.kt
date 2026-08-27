package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp011BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
}

internal data class Kvp011ImplementationCommit(
    val revision: DeliveryGeneration,
    val changedPaths: List<String>,
)

internal class AdmittedKvp011ImplementationScope internal constructor(
    val commits: List<Kvp011ImplementationCommit>,
)

internal sealed interface Kvp011ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp011ImplementationScope) :
        Kvp011ImplementationScopeAdmission
    data class Rejected(val failure: Kvp011BoundaryFailure) :
        Kvp011ImplementationScopeAdmission
}

internal sealed interface Kvp011RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp011RelevantInputAdmission
    data class Rejected(val failure: Kvp011BoundaryFailure) : Kvp011RelevantInputAdmission
}

/**
 * Proof transition: admitted KVP-031 baseline/current head plus graph write roots ->
 * `Kvp011ImplementationScopeAdmission`.
 *
 * Establishes a nonempty ordered KVP-011 commit delta after observing every checkpoint without a
 * pathspec. A checkpoint belongs to KVP-011 only when it changes one exclusive physical owner;
 * shared graph/projection paths alone cannot recover KVP-011 implementation ownership. Every
 * admitted checkpoint must remain wholly within declared writes. Git and scope failures remain
 * finite data.
 */
internal fun admitKvp011ImplementationScope(
    exec: ExecOperations,
    repositoryRoot: Path,
    predecessorHead: DeliveryGeneration,
    currentHead: DeliveryGeneration,
    allowedWrites: List<String>,
    ownedWrites: List<String>,
    companionWrites: List<String>,
): Kvp011ImplementationScopeAdmission {
    if (git(exec, repositoryRoot, listOf(
            "merge-base", "--is-ancestor", predecessorHead.value, currentHead.value,
        )).exitCode != 0
    ) return scopeRejected(Kvp011BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    val revisions = git(
        exec,
        repositoryRoot,
        listOf("rev-list", "--reverse", "${predecessorHead.value}^..${currentHead.value}"),
    )
    if (revisions.exitCode != 0) {
        return scopeRejected(Kvp011BoundaryFailure.GIT_COMMAND_REJECTED)
    }
    val commits = mutableListOf<Kvp011ImplementationCommit>()
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git(
            exec,
            repositoryRoot,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.exitCode != 0) {
            return scopeRejected(Kvp011BoundaryFailure.GIT_COMMAND_REJECTED)
        }
        val observed = changed.text.lineSequence().filter(String::isNotBlank).sorted().toList()
        val taskPaths = observed.filter { path ->
            ownedWrites.any { scope -> path.inKvp011Scope(scope) }
        }
        val ownsCheckpoint = observed.any { path ->
            ownedWrites.any { scope -> path.inKvp011Scope(scope) }
        }
        if (!ownsCheckpoint) return@forEach
        if (observed.any { path ->
                (allowedWrites + companionWrites).none { scope -> path.inKvp011Scope(scope) }
            }
        ) return scopeRejected(Kvp011BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        commits += Kvp011ImplementationCommit(DeliveryGeneration(revision), taskPaths)
    }
    return if (commits.isEmpty()) {
        scopeRejected(Kvp011BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    } else {
        Kvp011ImplementationScopeAdmission.Complete(
            AdmittedKvp011ImplementationScope(commits),
        )
    }
}

/**
 * Proof transition: graph read roots plus admitted packet/dependency evidence ->
 * `Kvp011RelevantInputAdmission`.
 *
 * Establishes a deterministic digest over clean tracked files in only the declared roots, the
 * canonical packet, and every predecessor digest. This performs no repository walk. Dirty,
 * empty, unreadable, or Git-rejected closure is finite rejection.
 */
internal fun admitKvp011RelevantInputs(
    exec: ExecOperations,
    repositoryRoot: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp011Dependencies,
): Kvp011RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git(
        exec,
        repositoryRoot,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.exitCode != 0) return inputRejected(
        Kvp011BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    if (status.bytes.isNotEmpty()) return inputRejected(
        Kvp011BoundaryFailure.DIRTY_RELEVANT_INPUT,
    )
    val listed = git(exec, repositoryRoot, listOf("ls-files", "-z", "--") + roots)
    if (listed.exitCode != 0) return inputRejected(
        Kvp011BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp011BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inKvp011Scope(it) }) return inputRejected(
            Kvp011BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
        )
        when (val read = readBoundaryFile(repositoryRoot.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp011BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    val closure = linkedMapOf<String, Any?>(
        "packetDigest" to packet.documentDigest.value,
        "dependencyReceiptDigests" to dependencies.digests,
        "trackedInputDigests" to digests,
    )
    return Kvp011RelevantInputAdmission.Complete(
        RelevantInputDigest(sha256(canonicalJson(closure)).value),
    )
}

private data class Kvp011GitObservation(val exitCode: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git(
    exec: ExecOperations,
    root: Path,
    arguments: List<String>,
): Kvp011GitObservation {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        args(arguments)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Kvp011GitObservation(result.exitValue, output.toByteArray())
}

private fun String.inKvp011Scope(scope: String) = this == scope || startsWith("$scope/")
private fun scopeRejected(failure: Kvp011BoundaryFailure) =
    Kvp011ImplementationScopeAdmission.Rejected(failure)
private fun inputRejected(failure: Kvp011BoundaryFailure) =
    Kvp011RelevantInputAdmission.Rejected(failure)
