package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp038BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
}

internal class AdmittedKvp038ImplementationScope internal constructor(val commitCount: Int)

internal sealed interface Kvp038ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp038ImplementationScope) :
        Kvp038ImplementationScopeAdmission
    data class Rejected(val failure: Kvp038BoundaryFailure) :
        Kvp038ImplementationScopeAdmission
}

internal sealed interface Kvp038RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp038RelevantInputAdmission
    data class Rejected(val failure: Kvp038BoundaryFailure) : Kvp038RelevantInputAdmission
}

/**
 * Proof transition: `(DeliveryGeneration, DeliveryGeneration, TaskPacket) ->
 * Kvp038ImplementationScopeAdmission`.
 *
 * Establishes a nonempty, harness-anchored commit set wholly inside graph-declared writes. Git,
 * ancestry, empty-selection, or scope failures remain closed [Kvp038BoundaryFailure]; raw Git
 * output is extracted only at this effect boundary.
 */
internal fun admitKvp038ImplementationScope(
    exec: ExecOperations,
    root: Path,
    baseline: DeliveryGeneration,
    head: DeliveryGeneration,
    packet: TaskPacket,
): Kvp038ImplementationScopeAdmission {
    if (git038(exec, root, listOf(
            "merge-base", "--is-ancestor", baseline.value, head.value,
        )).code != 0
    ) return scopeRejected(Kvp038BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    val revisions = git038(
        exec, root, listOf("rev-list", "--reverse", "${baseline.value}..${head.value}"),
    )
    if (revisions.code != 0) return scopeRejected(Kvp038BoundaryFailure.GIT_COMMAND_REJECTED)
    var count = 0
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git038(
            exec, root,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.code != 0) return scopeRejected(Kvp038BoundaryFailure.GIT_COMMAND_REJECTED)
        val paths = changed.text.lineSequence().filter(String::isNotBlank).toList()
        if (KVP038_IMPLEMENTATION_ANCHOR !in paths) return@forEach
        if (paths.any { path -> packet.task.allowedWrites.none { path.inScope038(it) } }) {
            return scopeRejected(Kvp038BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        }
        count += 1
    }
    return if (count == 0) scopeRejected(Kvp038BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    else Kvp038ImplementationScopeAdmission.Complete(AdmittedKvp038ImplementationScope(count))
}

/**
 * Proof transition: `(AdmittedTaskPacketFile, AdmittedKvp038Dependencies) ->
 * Kvp038RelevantInputAdmission`.
 *
 * Establishes a digest of every clean tracked graph-declared input plus predecessor/packet proof.
 * Dirty, empty, unreadable, or out-of-scope input remains closed [Kvp038BoundaryFailure]; raw paths
 * and bytes are extracted only at this filesystem boundary.
 */
internal fun admitKvp038RelevantInputs(
    exec: ExecOperations,
    root: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp038Dependencies,
): Kvp038RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git038(
        exec, root,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.code != 0) return inputRejected(Kvp038BoundaryFailure.GIT_COMMAND_REJECTED)
    if (status.bytes.isNotEmpty()) return inputRejected(Kvp038BoundaryFailure.DIRTY_RELEVANT_INPUT)
    val listed = git038(exec, root, listOf("ls-files", "-z", "--") + roots)
    if (listed.code != 0) return inputRejected(Kvp038BoundaryFailure.GIT_COMMAND_REJECTED)
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp038BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inScope038(it) }) {
            return inputRejected(Kvp038BoundaryFailure.RELEVANT_INPUT_READ_REJECTED)
        }
        when (val read = readBoundaryFile(root.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp038BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    return Kvp038RelevantInputAdmission.Complete(RelevantInputDigest(sha256(canonicalJson(
        linkedMapOf(
            "packetDigest" to packet.documentDigest.value,
            "dependencyReceiptDigests" to dependencies.digests,
            "trackedInputDigests" to digests,
        ),
    )).value))
}

private data class Git038(val code: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git038(exec: ExecOperations, root: Path, args: List<String>): Git038 {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        this.args(args)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Git038(result.exitValue, output.toByteArray())
}

private fun String.inScope038(scope: String) = this == scope || startsWith("$scope/")
private fun scopeRejected(failure: Kvp038BoundaryFailure) =
    Kvp038ImplementationScopeAdmission.Rejected(failure)
private fun inputRejected(failure: Kvp038BoundaryFailure) =
    Kvp038RelevantInputAdmission.Rejected(failure)
private const val KVP038_IMPLEMENTATION_ANCHOR =
    "build-logic/src/main/kotlin/support/delivery/gradle/kvp038/prove-clean-checkout.sh"
