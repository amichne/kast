package support.delivery

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.gradle.process.ExecOperations

internal enum class Kvp037BoundaryFailure {
    GIT_COMMAND_REJECTED,
    PREDECESSOR_NOT_ANCESTOR,
    NO_IMPLEMENTATION_COMMIT,
    WRITE_OUTSIDE_DECLARED_SCOPE,
    DIRTY_RELEVANT_INPUT,
    EMPTY_RELEVANT_INPUT,
    RELEVANT_INPUT_READ_REJECTED,
}

internal class AdmittedKvp037ImplementationScope internal constructor(val commitCount: Int)

internal sealed interface Kvp037ImplementationScopeAdmission {
    data class Complete(val scope: AdmittedKvp037ImplementationScope) :
        Kvp037ImplementationScopeAdmission
    data class Rejected(val failure: Kvp037BoundaryFailure) : Kvp037ImplementationScopeAdmission
}

internal sealed interface Kvp037RelevantInputAdmission {
    data class Complete(val digest: RelevantInputDigest) : Kvp037RelevantInputAdmission
    data class Rejected(val failure: Kvp037BoundaryFailure) : Kvp037RelevantInputAdmission
}

/** Ready frontier plus graph packet -> nonempty KVP-037-exclusive implementation scope. */
internal fun admitKvp037ImplementationScope(
    exec: ExecOperations,
    root: Path,
    baseline: DeliveryGeneration,
    head: DeliveryGeneration,
    packet: TaskPacket,
): Kvp037ImplementationScopeAdmission {
    if (git037(exec, root, listOf(
            "merge-base", "--is-ancestor", baseline.value, head.value,
        )).code != 0
    ) return scopeRejected(Kvp037BoundaryFailure.PREDECESSOR_NOT_ANCESTOR)
    val revisions = git037(
        exec,
        root,
        listOf("rev-list", "--reverse", "${baseline.value}..${head.value}"),
    )
    if (revisions.code != 0) return scopeRejected(Kvp037BoundaryFailure.GIT_COMMAND_REJECTED)
    var count = 0
    revisions.text.lineSequence().filter(String::isNotBlank).forEach { revision ->
        val changed = git037(
            exec,
            root,
            listOf("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", revision),
        )
        if (changed.code != 0) return scopeRejected(Kvp037BoundaryFailure.GIT_COMMAND_REJECTED)
        val paths = changed.text.lineSequence().filter(String::isNotBlank).toList()
        if (KVP037_IMPLEMENTATION_ANCHOR !in paths) return@forEach
        if (paths.any { path -> packet.task.allowedWrites.none { path.inScope037(it) } }) {
            return scopeRejected(Kvp037BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE)
        }
        count += 1
    }
    return if (count == 0) scopeRejected(Kvp037BoundaryFailure.NO_IMPLEMENTATION_COMMIT)
    else Kvp037ImplementationScopeAdmission.Complete(AdmittedKvp037ImplementationScope(count))
}

/** Packet/dependency evidence plus clean graph reads -> deterministic relevant-input digest. */
internal fun admitKvp037RelevantInputs(
    exec: ExecOperations,
    root: Path,
    packet: AdmittedTaskPacketFile,
    dependencies: AdmittedKvp037Dependencies,
): Kvp037RelevantInputAdmission {
    val roots = packet.packet.task.allowedReads
    val status = git037(
        exec,
        root,
        listOf("status", "--porcelain=v1", "-z", "--untracked-files=all", "--") + roots,
    )
    if (status.code != 0) return inputRejected(Kvp037BoundaryFailure.GIT_COMMAND_REJECTED)
    if (status.bytes.isNotEmpty()) return inputRejected(Kvp037BoundaryFailure.DIRTY_RELEVANT_INPUT)
    val listed = git037(exec, root, listOf("ls-files", "-z", "--") + roots)
    if (listed.code != 0) return inputRejected(Kvp037BoundaryFailure.GIT_COMMAND_REJECTED)
    val paths = listed.bytes.toString(Charsets.UTF_8).split('\u0000')
        .filter(String::isNotEmpty).sorted()
    if (paths.isEmpty()) return inputRejected(Kvp037BoundaryFailure.EMPTY_RELEVANT_INPUT)
    val digests = linkedMapOf<String, String>()
    paths.forEach { path ->
        if (roots.none { path.inScope037(it) }) {
            return inputRejected(Kvp037BoundaryFailure.RELEVANT_INPUT_READ_REJECTED)
        }
        when (val read = readBoundaryFile(root.resolve(path), MAX_SOURCE_ARTIFACT_BYTES)) {
            is BoundaryFileRead.Complete -> digests[path] = sha256Bytes(read.bytes)
            is BoundaryFileRead.Rejected -> return inputRejected(
                Kvp037BoundaryFailure.RELEVANT_INPUT_READ_REJECTED,
            )
        }
    }
    return Kvp037RelevantInputAdmission.Complete(RelevantInputDigest(sha256(canonicalJson(
        linkedMapOf(
            "packetDigest" to packet.documentDigest.value,
            "dependencyReceiptDigests" to dependencies.digests,
            "trackedInputDigests" to digests,
        ),
    )).value))
}

private data class Git037(val code: Int, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8).trim()
}

private fun git037(exec: ExecOperations, root: Path, args: List<String>): Git037 {
    val output = ByteArrayOutputStream()
    val result = exec.exec {
        workingDir(root.toFile())
        executable("git")
        this.args(args)
        standardOutput = output
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    return Git037(result.exitValue, output.toByteArray())
}

private fun String.inScope037(scope: String) = this == scope || startsWith("$scope/")
private fun scopeRejected(failure: Kvp037BoundaryFailure) =
    Kvp037ImplementationScopeAdmission.Rejected(failure)
private fun inputRejected(failure: Kvp037BoundaryFailure) =
    Kvp037RelevantInputAdmission.Rejected(failure)
private const val KVP037_IMPLEMENTATION_ANCHOR = "acceptance/ide-hosted/prove_failures.py"
