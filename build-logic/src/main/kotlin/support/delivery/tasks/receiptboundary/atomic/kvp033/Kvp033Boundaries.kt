package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp033BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
    EMPTY_OWNED_WRITE_SCOPE,
}

internal class AdmittedKvp033ImplementationScope internal constructor(
    val commitCount: Int,
)

internal sealed interface Kvp033ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp033ImplementationScope) :
        Kvp033ImplementationScopeAdmission
    data class Rejected(val failure: Kvp033BoundaryFailure) :
        Kvp033ImplementationScopeAdmission
}

internal sealed interface Kvp033RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp033RelevantInputAdmission
    data class Rejected(val failure: Kvp033BoundaryFailure) : Kvp033RelevantInputAdmission
}

internal class AdmittedKvp033WriteOwnership internal constructor(
    val declaredWrites: List<String>,
    val ownedWrites: List<String>,
)

internal sealed interface Kvp033WriteOwnershipAdmission {
    data class Complete(val ownership: AdmittedKvp033WriteOwnership) :
        Kvp033WriteOwnershipAdmission
    data class Rejected(val failure: Kvp033BoundaryFailure) : Kvp033WriteOwnershipAdmission
}

/**
 * Proof transition: `(TaskPacket, ValidatedProgram) -> Kvp033WriteOwnershipAdmission`.
 *
 * Establishes KVP-033's declared writes and graph-derived physical anchors that no other canonical
 * task declares. An empty ownership set is closed [Kvp033BoundaryFailure] data. Raw path strings
 * leave only at the Git write-scope boundary.
 */
internal fun admitKvp033WriteOwnership(
    packet: TaskPacket,
    program: ValidatedProgram,
): Kvp033WriteOwnershipAdmission {
    val otherWrites = program.program.tasks.filter { it.id != packet.task.id }
        .flatMap { it.allowedWrites }.toSet()
    val ownedWrites = packet.task.allowedWrites.filterNot(otherWrites::contains)
    return if (ownedWrites.isEmpty()) {
        Kvp033WriteOwnershipAdmission.Rejected(Kvp033BoundaryFailure.EMPTY_OWNED_WRITE_SCOPE)
    } else Kvp033WriteOwnershipAdmission.Complete(
        AdmittedKvp033WriteOwnership(packet.task.allowedWrites, ownedWrites),
    )
}

/**
 * Proof transition: ready-frontier/current generations plus graph write roots ->
 * `Kvp033ImplementationScopeAdmission`.
 *
 * Establishes a nonempty ordered KVP-033 commit delta. Only commits containing a graph-derived
 * physical ownership anchor enter the delta, so successor edits to shared projection/convention
 * paths cannot invalidate content proof. Each admitted commit remains wholly inside declared
 * writes. Git, ancestry, empty-delta, and mixed-scope failures are finite failure data.
 */
internal fun admitKvp033ImplementationScope(
    exec: ExecOperations,
    repositoryRoot: Path,
    predecessorHead: DeliveryGeneration,
    currentHead: DeliveryGeneration,
    ownership: AdmittedKvp033WriteOwnership,
): Kvp033ImplementationScopeAdmission {
    if (git033(exec, repositoryRoot, listOf(
            "merge-base", "--is-ancestor", predecessorHead.value, currentHead.value,
        )).exitCode != 0
    ) return scopeRejected(Kvp033BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    val revisions = git033(
        exec,
        repositoryRoot,
        listOf("rev-list", "--reverse", "${predecessorHead.value}..${currentHead.value}"),
    )
    if (revisions.exitCode != 0) return scopeRejected(
        Kvp033BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    var commitCount = 0
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git033(
            exec,
            repositoryRoot,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.exitCode != 0) return scopeRejected(
            Kvp033BoundaryFailure.GIT_COMMAND_REJECTED,
        )
        val observed = changed.text.lineSequence().filter(String::isNotBlank).toList()
        val ownedPaths = observed.filter { path ->
            ownership.ownedWrites.any { scope -> path.inKvp033Scope(scope) }
        }
        if (ownedPaths.isEmpty()) return@forEach
        if (observed.any { path ->
                ownership.declaredWrites.none { scope -> path.inKvp033Scope(scope) }
            }
        ) return scopeRejected(Kvp033BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        commitCount += 1
    }
    return if (commitCount == 0) scopeRejected(Kvp033BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    else Kvp033ImplementationScopeAdmission.Complete(
        AdmittedKvp033ImplementationScope(commitCount),
    )
}

/**
 * Proof transition: graph read roots plus packet/dependency closure ->
 * `Kvp033RelevantInputAdmission`.
 *
 * Establishes a deterministic digest over clean tracked inputs reached only through exact graph
 * pathspecs, the generated packet, and predecessor receipt digests. Dirty, empty, unreadable, or
 * Git-rejected closure remains finite [Kvp033BoundaryFailure] data. Raw source bytes are extracted
 * only at this build-policy boundary, never inside the hosted read path.
 */
internal fun admitKvp033RelevantInputs(
    exec: ExecOperations,
    repositoryRoot: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp033Dependencies,
): Kvp033RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git033(
        exec,
        repositoryRoot,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.exitCode != 0) return inputRejected(Kvp033BoundaryFailure.GIT_COMMAND_REJECTED)
    if (status.bytes.isNotEmpty()) return inputRejected(Kvp033BoundaryFailure.DIRTY_RELEVANT_INPUT)
    val listed = git033(exec, repositoryRoot, listOf("ls-files", "-z", "--") + roots)
    if (listed.exitCode != 0) return inputRejected(Kvp033BoundaryFailure.GIT_COMMAND_REJECTED)
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp033BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inKvp033Scope(it) }) return inputRejected(
            Kvp033BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
        )
        when (val read = readBoundaryFile(repositoryRoot.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp033BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    return Kvp033RelevantInputAdmission.Complete(RelevantInputDigest(sha256(canonicalJson(
        linkedMapOf<String, Any?>(
            "packetDigest" to packet.documentDigest.value,
            "dependencyReceiptDigests" to dependencies.digests,
            "trackedInputDigests" to digests,
        ),
    )).value))
}

private data class Kvp033GitObservation(val exitCode: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git033(
    exec: ExecOperations,
    root: Path,
    arguments: List<String>,
): Kvp033GitObservation {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        args(arguments)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Kvp033GitObservation(result.exitValue, output.toByteArray())
}

private fun String.inKvp033Scope(scope: String) = this == scope || startsWith("$scope/")
private fun scopeRejected(failure: Kvp033BoundaryFailure) =
    Kvp033ImplementationScopeAdmission.Rejected(failure)
private fun inputRejected(failure: Kvp033BoundaryFailure) =
    Kvp033RelevantInputAdmission.Rejected(failure)
