package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp032BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
    EMPTY_OWNED_WRITE_SCOPE,
}

internal data class Kvp032ImplementationCommit(
    val revision: DeliveryGeneration,
    val changedPaths: List<String>,
)

internal data class Kvp032ObservedCommit(
    val revision: DeliveryGeneration,
    val changedPaths: List<String>,
)

internal class AdmittedKvp032ImplementationScope internal constructor(
    val commits: List<Kvp032ImplementationCommit>,
)

internal sealed interface Kvp032ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp032ImplementationScope) :
        Kvp032ImplementationScopeAdmission
    data class Rejected(val failure: Kvp032BoundaryFailure) :
        Kvp032ImplementationScopeAdmission
}

internal sealed interface Kvp032RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp032RelevantInputAdmission
    data class Rejected(val failure: Kvp032BoundaryFailure) : Kvp032RelevantInputAdmission
}

internal class AdmittedKvp032WriteOwnership internal constructor(
    val declaredWrites: List<String>,
    val ownedWrites: List<String>,
    val companionWrites: List<String>,
    val successorWrites: List<String>,
)

internal sealed interface Kvp032WriteOwnershipAdmission {
    data class Complete(val ownership: AdmittedKvp032WriteOwnership) :
        Kvp032WriteOwnershipAdmission
    data class Rejected(val failure: Kvp032BoundaryFailure) : Kvp032WriteOwnershipAdmission
}

/**
 * Proof transition: `(TaskPacket, ValidatedProgram) -> Kvp032WriteOwnershipAdmission`.
 *
 * Establishes KVP-032's declared write closure, canonical delivery-batch ownership, and later graph
 * task scopes that are strictly nested beneath a KVP-032 physical root. Equal or broader later
 * scopes cannot recover ownership. An empty ownership set is a finite rejection. Raw path strings
 * may be extracted only by the Git write-scope boundary.
 */
internal fun admitKvp032WriteOwnership(
    packet: TaskPacket,
    program: ValidatedProgram,
): Kvp032WriteOwnershipAdmission {
    val ownedWrites = hostedProductionCompositionOwnedWrites(packet.task.id)
    val laterBatchWrites = program.program.deliveryBatches
        .filterNot { it.id == hostedProductionCompositionBatch().id }
        .flatMap { batch -> batch.tasks.flatMap { it.ownedWrites } }
    val taskWave = program.waves.getValue(packet.task.id)
    val nestedLaterTaskWrites = program.program.tasks
        .filter { task -> program.waves.getValue(task.id) > taskWave }
        .flatMap { it.allowedWrites }
        .filter { successor ->
            ownedWrites.any { owner -> successor != owner && successor.startsWith("$owner/") }
        }
    return if (ownedWrites.isEmpty()) {
        Kvp032WriteOwnershipAdmission.Rejected(Kvp032BoundaryFailure.EMPTY_OWNED_WRITE_SCOPE)
    } else {
        Kvp032WriteOwnershipAdmission.Complete(
            AdmittedKvp032WriteOwnership(
                packet.task.allowedWrites,
                ownedWrites,
                hostedProductionCompositionCompanionWrites(packet.task.id),
                (laterBatchWrites + nestedLaterTaskWrites).distinct(),
            ),
        )
    }
}

/**
 * Proof transition: admitted KVP-031 baseline/current head plus graph-derived write ownership ->
 * `Kvp032ImplementationScopeAdmission`.
 *
 * Establishes a nonempty ordered KVP-032 commit delta after observing every checkpoint without a
 * pathspec. Only checkpoints containing a KVP-032 physical ownership anchor enter the task delta.
 * A mixed checkpoint is ignored only when it also contains a physical anchor owned by a later
 * canonical batch or a strictly nested later graph task; otherwise every out-of-scope write is
 * rejected. Every admitted
 * KVP-032 checkpoint remains wholly within declared or companion writes. Git and scope failures
 * remain finite data.
 */
internal fun admitKvp032ImplementationScope(
    exec: ExecOperations,
    repositoryRoot: Path,
    predecessorHead: DeliveryGeneration,
    currentHead: DeliveryGeneration,
    ownership: AdmittedKvp032WriteOwnership,
): Kvp032ImplementationScopeAdmission {
    if (git(exec, repositoryRoot, listOf(
            "merge-base", "--is-ancestor", predecessorHead.value, currentHead.value,
        )).exitCode != 0
    ) return scopeRejected(Kvp032BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    val revisions = git(
        exec,
        repositoryRoot,
        listOf("rev-list", "--reverse", "${predecessorHead.value}..${currentHead.value}"),
    )
    if (revisions.exitCode != 0) {
        return scopeRejected(Kvp032BoundaryFailure.GIT_COMMAND_REJECTED)
    }
    val observedCommits = mutableListOf<Kvp032ObservedCommit>()
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git(
            exec,
            repositoryRoot,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.exitCode != 0) {
            return scopeRejected(Kvp032BoundaryFailure.GIT_COMMAND_REJECTED)
        }
        observedCommits += Kvp032ObservedCommit(
            DeliveryGeneration(revision),
            changed.text.lineSequence().filter(String::isNotBlank).sorted().toList(),
        )
    }
    return admitKvp032ObservedImplementationScope(observedCommits, ownership)
}

/**
 * Proof transition: `(List<Kvp032ObservedCommit>, AdmittedKvp032WriteOwnership) ->
 * Kvp032ImplementationScopeAdmission`.
 *
 * Establishes that every retained checkpoint has a KVP-032 physical ownership anchor and no path
 * outside its declared or companion scope. A checkpoint wholly anchored by a strictly nested
 * later-task scope is excluded, and a mixed checkpoint may be excluded only when such a successor
 * anchor is present. Empty KVP-032 scope and unowned writes remain finite [Kvp032BoundaryFailure]
 * data. Raw Git path extraction is permitted only by [admitKvp032ImplementationScope].
 */
internal fun admitKvp032ObservedImplementationScope(
    observedCommits: List<Kvp032ObservedCommit>,
    ownership: AdmittedKvp032WriteOwnership,
): Kvp032ImplementationScopeAdmission {
    val commits = mutableListOf<Kvp032ImplementationCommit>()
    observedCommits.forEach { commit ->
        val observed = commit.changedPaths.sorted()
        val ownedPaths = observed.filter { path ->
            ownership.ownedWrites.any { scope -> path.inKvp032Scope(scope) }
        }
        if (ownedPaths.isEmpty()) return@forEach
        val successorOwnedPaths = ownedPaths.filter { path ->
            ownership.successorWrites.any { scope -> path.inKvp032Scope(scope) }
        }
        if (successorOwnedPaths.size == ownedPaths.size) return@forEach
        val outside = observed.filter { path ->
            (ownership.declaredWrites + ownership.companionWrites).none { scope ->
                path.inKvp032Scope(scope)
            }
        }
        if (outside.isNotEmpty()) {
            val successorCheckpoint = observed.any { path ->
                ownership.successorWrites.any { scope -> path.inKvp032Scope(scope) }
            }
            if (successorCheckpoint) return@forEach
            return scopeRejected(Kvp032BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        }
        commits += Kvp032ImplementationCommit(commit.revision, ownedPaths)
    }
    return if (commits.isEmpty()) {
        scopeRejected(Kvp032BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    } else {
        Kvp032ImplementationScopeAdmission.Complete(
            AdmittedKvp032ImplementationScope(commits),
        )
    }
}

/**
 * Proof transition: graph read roots plus admitted packet/dependency evidence ->
 * `Kvp032RelevantInputAdmission`.
 *
 * Establishes a deterministic digest over clean tracked files in only the declared roots, the
 * canonical packet, and every predecessor digest. This performs no repository walk. Dirty,
 * empty, unreadable, or Git-rejected closure is finite rejection.
 */
internal fun admitKvp032RelevantInputs(
    exec: ExecOperations,
    repositoryRoot: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp032Dependencies,
): Kvp032RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git(
        exec,
        repositoryRoot,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.exitCode != 0) return inputRejected(
        Kvp032BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    if (status.bytes.isNotEmpty()) return inputRejected(
        Kvp032BoundaryFailure.DIRTY_RELEVANT_INPUT,
    )
    val listed = git(exec, repositoryRoot, listOf("ls-files", "-z", "--") + roots)
    if (listed.exitCode != 0) return inputRejected(
        Kvp032BoundaryFailure.GIT_COMMAND_REJECTED,
    )
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp032BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inKvp032Scope(it) }) return inputRejected(
            Kvp032BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
        )
        when (val read = readBoundaryFile(repositoryRoot.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp032BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    val closure = linkedMapOf<String, Any?>(
        "packetDigest" to packet.documentDigest.value,
        "dependencyReceiptDigests" to dependencies.digests,
        "trackedInputDigests" to digests,
    )
    return Kvp032RelevantInputAdmission.Complete(
        RelevantInputDigest(sha256(canonicalJson(closure)).value),
    )
}

private data class Kvp032GitObservation(val exitCode: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git(
    exec: ExecOperations,
    root: Path,
    arguments: List<String>,
): Kvp032GitObservation {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        args(arguments)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Kvp032GitObservation(result.exitValue, output.toByteArray())
}

private fun String.inKvp032Scope(scope: String) = this == scope || startsWith("$scope/")
private fun scopeRejected(failure: Kvp032BoundaryFailure) =
    Kvp032ImplementationScopeAdmission.Rejected(failure)
private fun inputRejected(failure: Kvp032BoundaryFailure) =
    Kvp032RelevantInputAdmission.Rejected(failure)
