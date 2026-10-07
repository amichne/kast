package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.Collections

/** Native VFS directory, independently confirmed filesystem absence, or unavailable evidence. */
internal enum class SemanticSourceRootPresence {
    DIRECTORY,
    ABSENT,
    UNAVAILABLE,
}

internal class CompleteSemanticSourceRoots private constructor(val roots: Map<Path, SemanticSourceRootPresence>) {
    fun appendTo(digest: SemanticInputDigest) {
        for ((path, presence) in roots.entries.sortedBy { it.key.toString() }) {
            digest.text(path.toString())
            digest.text(presence.name)
        }
    }

    companion object {
        fun admit(
            declared: Set<Path>,
            native: Set<Path>,
            observed: Map<Path, SemanticSourceRootPresence>,
        ): Refinement<CompleteSemanticSourceRoots, SemanticDependencyCaptureFailure> {
            if (!declared.containsAll(native) || observed.keys != declared)
                return Refinement.Rejected(SemanticDependencyCaptureFailure.SOURCE_ROOT_INVENTORY_MISMATCH)
            if (
                observed.values.any { it == SemanticSourceRootPresence.UNAVAILABLE } ||
                    native.any { observed[it] != SemanticSourceRootPresence.DIRECTORY }
            )
                return Refinement.Rejected(SemanticDependencyCaptureFailure.SOURCE_UNAVAILABLE)
            return Refinement.Refined(CompleteSemanticSourceRoots(Collections.unmodifiableMap(observed.toMap())))
        }
    }
}
