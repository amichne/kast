package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp036BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
    OWNERSHIP_MISMATCH,
}

internal class AdmittedKvp036ImplementationScope internal constructor(val commitCount: Int)

internal sealed interface Kvp036ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp036ImplementationScope) :
        Kvp036ImplementationScopeAdmission
    data class Rejected(val failure: Kvp036BoundaryFailure) :
        Kvp036ImplementationScopeAdmission
}

internal sealed interface Kvp036RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp036RelevantInputAdmission
    data class Rejected(val failure: Kvp036BoundaryFailure) : Kvp036RelevantInputAdmission
}

/**
 * Proof transition: graph batch frontier plus KVP-036 packet -> implementation-scope admission.
 *
 * Establishes an ancestral nonempty commit delta selected by the batch's exclusive physical
 * anchors, with every selected commit wholly inside graph-declared task writes. Git and scope
 * failures remain closed data; raw Git output exists only at this Gradle boundary.
 */
internal fun admitKvp036ImplementationScope(
    exec: ExecOperations,
    root: Path,
    baseline: DeliveryGeneration,
    head: DeliveryGeneration,
    packet: TaskPacket,
): Kvp036ImplementationScopeAdmission {
    val batchTask = defaultIsolatedRuntimeRetirementBatch().tasks.singleOrNull {
        it.taskId == packet.task.id
    } ?: return scopeRejected(Kvp036BoundaryFailure.OWNERSHIP_MISMATCH)
    if (batchTask.ownedWrites.any { owned ->
            packet.task.allowedWrites.none { declared -> owned.inScope036(declared) }
        }
    ) return scopeRejected(Kvp036BoundaryFailure.OWNERSHIP_MISMATCH)
    if (git036(exec, root, listOf("merge-base", "--is-ancestor", baseline.value, head.value)).code != 0) {
        return scopeRejected(Kvp036BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    }
    val revisions = git036(
        exec, root, listOf("rev-list", "--reverse", "${baseline.value}..${head.value}"),
    )
    if (revisions.code != 0) return scopeRejected(Kvp036BoundaryFailure.GIT_COMMAND_REJECTED)
    var count = 0
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git036(
            exec, root,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.code != 0) return scopeRejected(Kvp036BoundaryFailure.GIT_COMMAND_REJECTED)
        val paths = changed.text.lineSequence().filter(String::isNotBlank).toList()
        if (paths.none { path -> batchTask.ownedWrites.any { path.inScope036(it) } }) {
            return@forEach
        }
        if (paths.any { path -> packet.task.allowedWrites.none { path.inScope036(it) } }) {
            return scopeRejected(Kvp036BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        }
        count += 1
    }
    return if (count == 0) scopeRejected(Kvp036BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    else Kvp036ImplementationScopeAdmission.Complete(AdmittedKvp036ImplementationScope(count))
}

/**
 * Proof transition: admitted packet/dependencies plus tracked reads -> relevant-input digest.
 *
 * Establishes clean content closure over every graph-declared tracked read, packet digest, and
 * predecessor receipt digest. Dirty, empty, Git, or bounded-read failures remain closed data.
 */
internal fun admitKvp036RelevantInputs(
    exec: ExecOperations,
    root: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp036Dependencies,
): Kvp036RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git036(
        exec, root, listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.code != 0) return inputRejected(Kvp036BoundaryFailure.GIT_COMMAND_REJECTED)
    if (status.bytes.isNotEmpty()) return inputRejected(Kvp036BoundaryFailure.DIRTY_RELEVANT_INPUT)
    val listed = git036(exec, root, listOf("ls-files", "-z", "--") + roots)
    if (listed.code != 0) return inputRejected(Kvp036BoundaryFailure.GIT_COMMAND_REJECTED)
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp036BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inScope036(it) }) {
            return inputRejected(Kvp036BoundaryFailure.RELEVANT_INPUT_READ_REJECTED)
        }
        when (val read = readBoundaryFile(root.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp036BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    return Kvp036RelevantInputAdmission.Complete(RelevantInputDigest(sha256(canonicalJson(
        linkedMapOf(
            "packetDigest" to packet.documentDigest.value,
            "dependencyReceiptDigests" to dependencies.digests,
            "trackedInputDigests" to digests,
        ),
    )).value))
}

private data class Git036(val code: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git036(exec: ExecOperations, root: Path, args: List<String>): Git036 {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        this.args(args)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Git036(result.exitValue, output.toByteArray())
}

private fun String.inScope036(scope: String) = this == scope || startsWith("$scope/")
private fun scopeRejected(failure: Kvp036BoundaryFailure) =
    Kvp036ImplementationScopeAdmission.Rejected(failure)
private fun inputRejected(failure: Kvp036BoundaryFailure) =
    Kvp036RelevantInputAdmission.Rejected(failure)
