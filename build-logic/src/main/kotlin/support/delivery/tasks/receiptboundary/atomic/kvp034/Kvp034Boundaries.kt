package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp034BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
    EMPTY_OWNED_WRITE_SCOPE,
}

internal class AdmittedKvp034ImplementationScope internal constructor(val commitCount: Int)

internal sealed interface Kvp034ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp034ImplementationScope) :
        Kvp034ImplementationScopeAdmission
    data class Rejected(val failure: Kvp034BoundaryFailure) :
        Kvp034ImplementationScopeAdmission
}

internal sealed interface Kvp034RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp034RelevantInputAdmission
    data class Rejected(val failure: Kvp034BoundaryFailure) : Kvp034RelevantInputAdmission
}

internal class AdmittedKvp034WriteOwnership internal constructor(
    val declaredWrites: List<String>,
    val ownedWrites: List<String>,
)

internal sealed interface Kvp034WriteOwnershipAdmission {
    data class Complete(val ownership: AdmittedKvp034WriteOwnership) :
        Kvp034WriteOwnershipAdmission
    data class Rejected(val failure: Kvp034BoundaryFailure) :
        Kvp034WriteOwnershipAdmission
}

/**
 * Proof transition: `(TaskPacket, ValidatedProgram) -> Kvp034WriteOwnershipAdmission`.
 *
 * Establishes KVP-034's complete declared writes and the nonempty physical anchors that no other
 * canonical task declares. Empty exclusive ownership is closed [Kvp034BoundaryFailure] data. Raw
 * path strings leave only at the Git write-scope boundary.
 */
internal fun admitKvp034WriteOwnership(
    packet: TaskPacket,
    program: ValidatedProgram,
): Kvp034WriteOwnershipAdmission {
    val otherWrites = program.program.tasks.filter { it.id != packet.task.id }
        .flatMap { it.allowedWrites }.toSet()
    val ownedWrites = packet.task.allowedWrites.filterNot(otherWrites::contains)
    return if (ownedWrites.isEmpty()) {
        Kvp034WriteOwnershipAdmission.Rejected(Kvp034BoundaryFailure.EMPTY_OWNED_WRITE_SCOPE)
    } else Kvp034WriteOwnershipAdmission.Complete(
        AdmittedKvp034WriteOwnership(packet.task.allowedWrites, ownedWrites),
    )
}

/**
 * Proof transition: `DeliveryGeneration + AdmittedKvp034WriteOwnership ->
 * Kvp034ImplementationScopeAdmission`.
 *
 * Establishes an ancestral, nonempty KVP-034 commit delta. Only commits containing an exclusive
 * graph-derived physical anchor enter the delta, so dependency-closed delivery checkpoints and
 * successor edits to shared paths remain valid. Each admitted commit remains wholly within the
 * task's declared writes. Git, ancestry, empty-delta, or scope failures remain closed
 * [Kvp034BoundaryFailure] data. Raw Git output exists only at this Gradle policy boundary.
 */
internal fun admitKvp034ImplementationScope(
    exec: ExecOperations,
    root: Path,
    baseline: DeliveryGeneration,
    head: DeliveryGeneration,
    ownership: AdmittedKvp034WriteOwnership,
): Kvp034ImplementationScopeAdmission {
    if (git034(exec, root, listOf("merge-base", "--is-ancestor", baseline.value, head.value)).code != 0) {
        return scopeRejected(Kvp034BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    }
    val revisions = git034(exec, root, listOf("rev-list", "--reverse", "${baseline.value}..${head.value}"))
    if (revisions.code != 0) return scopeRejected(Kvp034BoundaryFailure.GIT_COMMAND_REJECTED)
    var count = 0
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git034(
            exec, root,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.code != 0) return scopeRejected(Kvp034BoundaryFailure.GIT_COMMAND_REJECTED)
        val paths = changed.text.lineSequence().filter(String::isNotBlank).toList()
        val taskPaths = paths.filter { path ->
            ownership.ownedWrites.any { path.inScope034(it) }
        }
        if (taskPaths.isEmpty()) return@forEach
        if (paths.any { path -> ownership.declaredWrites.none { path.inScope034(it) } }) {
            return scopeRejected(Kvp034BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        }
        count += 1
    }
    return if (count == 0) scopeRejected(Kvp034BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    else Kvp034ImplementationScopeAdmission.Complete(AdmittedKvp034ImplementationScope(count))
}

/**
 * Proof transition: `AdmittedTaskPacketFile + AdmittedKvp034Dependencies ->
 * Kvp034RelevantInputAdmission`.
 *
 * Establishes a deterministic digest over every clean tracked graph read plus packet/dependency
 * closure. Git, dirty, empty, or file-read failures remain closed [Kvp034BoundaryFailure] data.
 * Raw source bytes are extracted only at this Gradle proof boundary, never during an IDE read.
 */
internal fun admitKvp034RelevantInputs(
    exec: ExecOperations,
    root: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp034Dependencies,
): Kvp034RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git034(
        exec, root, listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.code != 0) return inputRejected(Kvp034BoundaryFailure.GIT_COMMAND_REJECTED)
    if (status.bytes.isNotEmpty()) return inputRejected(Kvp034BoundaryFailure.DIRTY_RELEVANT_INPUT)
    val listed = git034(exec, root, listOf("ls-files", "-z", "--") + roots)
    if (listed.code != 0) return inputRejected(Kvp034BoundaryFailure.GIT_COMMAND_REJECTED)
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp034BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inScope034(it) }) {
            return inputRejected(Kvp034BoundaryFailure.RELEVANT_INPUT_READ_REJECTED)
        }
        when (val read = readBoundaryFile(root.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp034BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    return Kvp034RelevantInputAdmission.Complete(RelevantInputDigest(sha256(canonicalJson(
        linkedMapOf(
            "packetDigest" to packet.documentDigest.value,
            "dependencyReceiptDigests" to dependencies.digests,
            "trackedInputDigests" to digests,
        ),
    )).value))
}

private data class Git034(val code: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git034(exec: ExecOperations, root: Path, args: List<String>): Git034 {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile()); executable("git"); this.args(args)
        standardOutput = output; errorOutput = ByteArrayOutputStream(); isIgnoreExitValue = true
    }
    return Git034(result.exitValue, output.toByteArray())
}

private fun String.inScope034(scope: String) = this == scope || startsWith("$scope/")
private fun scopeRejected(failure: Kvp034BoundaryFailure) =
    Kvp034ImplementationScopeAdmission.Rejected(failure)
private fun inputRejected(failure: Kvp034BoundaryFailure) =
    Kvp034RelevantInputAdmission.Rejected(failure)
