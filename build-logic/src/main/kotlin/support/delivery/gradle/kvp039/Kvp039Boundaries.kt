package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp039BoundaryFailure {
    GIT_COMMAND_REJECTED,
    IMPLEMENTATION_COMMIT_MISSING,
    IMPLEMENTATION_NOT_ANCESTOR,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
}

internal class AdmittedKvp039ImplementationScope internal constructor(val commitCount: Int)

internal sealed interface Kvp039ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp039ImplementationScope) :
        Kvp039ImplementationScopeAdmission
    data class Rejected(val failure: Kvp039BoundaryFailure) : Kvp039ImplementationScopeAdmission
}

internal sealed interface Kvp039RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp039RelevantInputAdmission
    data class Rejected(val failure: Kvp039BoundaryFailure) : Kvp039RelevantInputAdmission
}

/**
 * Proof transition: `(DeliveryGeneration, TaskPacket) -> Kvp039ImplementationScopeAdmission`.
 *
 * Establishes that the most recent graph-prover implementation checkpoint is an ancestor of the
 * observed head and writes only graph-declared KVP-039 paths. Missing, uncommitted, non-ancestor,
 * or out-of-scope evidence remains finite [Kvp039BoundaryFailure]; raw Git output is extracted only
 * at this effect boundary.
 */
internal fun admitKvp039ImplementationScope(
    exec: ExecOperations,
    root: Path,
    head: DeliveryGeneration,
    packet: TaskPacket,
): Kvp039ImplementationScopeAdmission {
    val revision = git039(exec, root, listOf(
        "log", "-1", "--format=%H", "--", KVP039_IMPLEMENTATION_ANCHOR,
    ))
    if (revision.code != 0) return scope039Rejected(Kvp039BoundaryFailure.GIT_COMMAND_REJECTED)
    val checkpoint = revision.text
    if (checkpoint.isBlank()) {
        return scope039Rejected(Kvp039BoundaryFailure.IMPLEMENTATION_COMMIT_MISSING)
    }
    if (git039(exec, root, listOf("merge-base", "--is-ancestor", checkpoint, head.value)).code != 0) {
        return scope039Rejected(Kvp039BoundaryFailure.IMPLEMENTATION_NOT_ANCESTOR)
    }
    val changed = git039(exec, root, listOf(
        "diff-tree", "--root", "--no-commit-id", "--name-only", "-r", checkpoint,
    ))
    if (changed.code != 0) return scope039Rejected(Kvp039BoundaryFailure.GIT_COMMAND_REJECTED)
    val paths = changed.text.lineSequence().filter(String::isNotBlank).toList()
    if (KVP039_IMPLEMENTATION_ANCHOR !in paths) {
        return scope039Rejected(Kvp039BoundaryFailure.IMPLEMENTATION_COMMIT_MISSING)
    }
    if (paths.any { path -> packet.task.allowedWrites.none { path.inScope039(it) } }) {
        return scope039Rejected(Kvp039BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
    }
    return Kvp039ImplementationScopeAdmission.Complete(AdmittedKvp039ImplementationScope(1))
}

/**
 * Proof transition: `(AdmittedTaskPacketFile, AdmittedKvp039Dependency) ->
 * Kvp039RelevantInputAdmission`.
 *
 * Establishes the digest of every clean tracked graph-declared input plus packet and predecessor
 * evidence. Dirty, empty, unreadable, or out-of-scope input remains finite
 * [Kvp039BoundaryFailure]; raw path and file bytes are extracted only here.
 */
internal fun admitKvp039RelevantInputs(
    exec: ExecOperations,
    root: Path,
    packet: AdmittedTaskPacketFile,
    dependency: AdmittedKvp039Dependency,
): Kvp039RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git039(
        exec, root,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.code != 0) return input039Rejected(Kvp039BoundaryFailure.GIT_COMMAND_REJECTED)
    if (status.bytes.isNotEmpty()) {
        return input039Rejected(Kvp039BoundaryFailure.DIRTY_RELEVANT_INPUT)
    }
    val listed = git039(exec, root, listOf("ls-files", "-z", "--") + roots)
    if (listed.code != 0) return input039Rejected(Kvp039BoundaryFailure.GIT_COMMAND_REJECTED)
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return input039Rejected(Kvp039BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inScope039(it) }) {
            return input039Rejected(Kvp039BoundaryFailure.RELEVANT_INPUT_READ_REJECTED)
        }
        when (val read = readBoundaryFile(root.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return input039Rejected(
                Kvp039BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    return Kvp039RelevantInputAdmission.Complete(RelevantInputDigest(sha256(canonicalJson(
        linkedMapOf(
            "packetDigest" to packet.documentDigest.value,
            "dependencyReceiptDigest" to dependency.receiptDigest,
            "trackedInputDigests" to digests,
        ),
    )).value))
}

private data class Git039(val code: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git039(exec: ExecOperations, root: Path, args: List<String>): Git039 {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        this.args(args)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Git039(result.exitValue, output.toByteArray())
}

private fun String.inScope039(scope: String) = this == scope || startsWith("$scope/")
private fun scope039Rejected(failure: Kvp039BoundaryFailure) =
    Kvp039ImplementationScopeAdmission.Rejected(failure)
private fun input039Rejected(failure: Kvp039BoundaryFailure) =
    Kvp039RelevantInputAdmission.Rejected(failure)
private const val KVP039_IMPLEMENTATION_ANCHOR =
    "build-logic/src/main/kotlin/support/delivery/gradle/kvp039/Kvp039Registration.kt"
