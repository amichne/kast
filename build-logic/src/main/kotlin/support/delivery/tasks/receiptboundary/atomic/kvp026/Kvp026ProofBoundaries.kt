package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp026BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
}

internal data class Kvp026ImplementationCommit(
    val revision: DeliveryGeneration,
    val changedPaths: List<String>,
)

internal class AdmittedKvp026ImplementationScope internal constructor(
    val commits: List<Kvp026ImplementationCommit>,
)

internal sealed interface Kvp026ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp026ImplementationScope) :
        Kvp026ImplementationScopeAdmission
    data class Rejected(val failure: Kvp026BoundaryFailure) :
        Kvp026ImplementationScopeAdmission
}

internal sealed interface Kvp026RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp026RelevantInputAdmission
    data class Rejected(val failure: Kvp026BoundaryFailure) : Kvp026RelevantInputAdmission
}

/**
 * Proof transition: admitted KVP-025 baseline/current head and graph-declared write roots ->
 * `Kvp026ImplementationScopeAdmission`.
 *
 * Establishes a nonempty, ordered commit delta in which every observed changed path is owned by
 * KVP-026. Git or scope failure remains finite rejection. Raw Git output exists only here.
 */
internal fun admitKvp026ImplementationScope(
    exec: ExecOperations,
    repositoryRoot: Path,
    predecessorHead: DeliveryGeneration,
    currentHead: DeliveryGeneration,
    allowedWrites: List<String>,
): Kvp026ImplementationScopeAdmission {
    if (git(exec, repositoryRoot, listOf(
            "merge-base", "--is-ancestor", predecessorHead.value, currentHead.value,
        )).exitCode != 0
    ) return scopeRejected(Kvp026BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    val revisions = git(
        exec,
        repositoryRoot,
        listOf("rev-list", "--reverse", "${predecessorHead.value}..${currentHead.value}"),
    )
    if (revisions.exitCode != 0) {
        return scopeRejected(Kvp026BoundaryFailure.GIT_COMMAND_REJECTED)
    }
    val commits = mutableListOf<Kvp026ImplementationCommit>()
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git(
            exec,
            repositoryRoot,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.exitCode != 0) {
            return scopeRejected(Kvp026BoundaryFailure.GIT_COMMAND_REJECTED)
        }
        val paths = changed.text.lineSequence().filter(String::isNotBlank).sorted().toList()
        if (paths.isEmpty() || paths.any { path ->
                allowedWrites.none { scope -> path.inScope(scope) }
            }
        ) return scopeRejected(Kvp026BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        commits += Kvp026ImplementationCommit(DeliveryGeneration(revision), paths)
    }
    return if (commits.isEmpty()) {
        scopeRejected(Kvp026BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    } else {
        Kvp026ImplementationScopeAdmission.Complete(
            AdmittedKvp026ImplementationScope(commits),
        )
    }
}

/**
 * Proof transition: graph-declared read roots plus packet/dependency evidence ->
 * `Kvp026RelevantInputAdmission`.
 *
 * Establishes a deterministic digest over clean tracked files in only the declared roots, the
 * canonical packet, and every admitted predecessor digest. This boundary performs no repository
 * walk. Dirty, empty, unreadable, or Git-rejected closure is finite rejection.
 */
internal fun admitKvp026RelevantInputs(
    exec: ExecOperations,
    repositoryRoot: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp026Dependencies,
): Kvp026RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git(
        exec,
        repositoryRoot,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.exitCode != 0) return inputRejected(
        Kvp026BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    if (status.bytes.isNotEmpty()) return inputRejected(
        Kvp026BoundaryFailure.DIRTY_RELEVANT_INPUT,
    )
    val listed = git(exec, repositoryRoot, listOf("ls-files", "-z", "--") + roots)
    if (listed.exitCode != 0) return inputRejected(
        Kvp026BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp026BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inScope(it) }) return inputRejected(
            Kvp026BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
        )
        when (val read = readBoundaryFile(repositoryRoot.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp026BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    val closure = linkedMapOf<String, Any?>(
        "packetDigest" to packet.documentDigest.value,
        "dependencyReceiptDigests" to dependencies.digests,
        "trackedInputDigests" to digests,
    )
    return Kvp026RelevantInputAdmission.Complete(
        RelevantInputDigest(sha256(canonicalJson(closure)).value),
    )
}

private data class Kvp026GitObservation(val exitCode: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git(
    exec: ExecOperations,
    root: Path,
    arguments: List<String>,
): Kvp026GitObservation {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        args(arguments)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Kvp026GitObservation(result.exitValue, output.toByteArray())
}

private fun String.inScope(scope: String) = this == scope || startsWith("$scope/")

private fun scopeRejected(failure: Kvp026BoundaryFailure) =
    Kvp026ImplementationScopeAdmission.Rejected(failure)

private fun inputRejected(failure: Kvp026BoundaryFailure) =
    Kvp026RelevantInputAdmission.Rejected(failure)
