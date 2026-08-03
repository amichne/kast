package io.github.amichne.kast.idea.mutation

import com.sun.jna.Native
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import java.nio.file.Path

internal sealed interface NamespaceTransition {
    val sourceName: String

    data class Rename(
        override val sourceName: String,
        val destinationName: String,
        val phase: SecureWorkspaceRenamePhase,
    ) : NamespaceTransition

    data class Unlink(
        override val sourceName: String,
    ) : NamespaceTransition
}

internal sealed interface ParentDirectoryDurabilityResult {
    data object Durable : ParentDirectoryDurabilityResult

    data class Failed(
        val errno: Int,
    ) : ParentDirectoryDurabilityResult
}

internal fun interface ParentDirectoryDurabilityBarrier {
    fun persist(
        parent: NativeDescriptor,
        transition: NamespaceTransition,
    ): ParentDirectoryDurabilityResult
}

internal object NativeParentDirectoryDurabilityBarrier : ParentDirectoryDurabilityBarrier {
    override fun persist(
        parent: NativeDescriptor,
        transition: NamespaceTransition,
    ): ParentDirectoryDurabilityResult =
        if (parent.api.fsync(parent.value) >= 0) {
            ParentDirectoryDurabilityResult.Durable
        } else {
            ParentDirectoryDurabilityResult.Failed(Native.getLastError())
        }
}

internal class ParentDirectoryDurabilityFailure(
    val evidence: UnsafeWorkspaceMutationException,
) : RuntimeException(evidence.message, evidence)

internal fun SecureWorkspaceMutation.persistParentDirectory(
    parent: NativeDescriptor,
    target: Path,
    transition: NamespaceTransition,
) {
    when (val result = parentDirectoryDurabilityBarrier.persist(parent, transition)) {
        ParentDirectoryDurabilityResult.Durable -> Unit
        is ParentDirectoryDurabilityResult.Failed -> {
            val parentPath = requireNotNull(target.parent) { "A secure mutation target must have a parent" }
            throw ParentDirectoryDurabilityFailure(
                UnsafeWorkspaceMutationException(
                    message = "Secure workspace mutation could not persist its namespace transition",
                    details = failureDetails(target, "fsync-parent-directory") + buildMap {
                        put("namespaceTransition", transition.wireName)
                        put("sourcePath", parentPath.resolve(transition.sourceName).toString())
                        if (transition is NamespaceTransition.Rename) {
                            put("destinationPath", parentPath.resolve(transition.destinationName).toString())
                            put("renamePhase", transition.phase.name)
                        }
                        put("errno", result.errno.toString())
                    },
                ),
            )
        }
    }
}

internal fun Throwable.rethrowIfParentDirectoryDurabilityFailed(evidence: RuntimeException? = null) {
    if (this is ParentDirectoryDurabilityFailure) {
        evidence?.let(this.evidence::addSuppressed)
        throw this
    }
}

internal inline fun <T> withParentDirectoryDurabilityEvidence(action: () -> T): T = try {
    action()
} catch (failure: ParentDirectoryDurabilityFailure) {
    throw failure.evidence
}

private val NamespaceTransition.wireName: String
    get() = when (this) {
        is NamespaceTransition.Rename -> "rename"
        is NamespaceTransition.Unlink -> "unlink"
    }
