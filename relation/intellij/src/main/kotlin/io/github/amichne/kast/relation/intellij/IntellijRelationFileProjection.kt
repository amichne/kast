package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot

/** Detachment consumes nullable native path observations once and returns a closed exact file outcome. */
internal fun detachRelationFile(
    file: VirtualFile,
    workspaceRoot: CanonicalWorkspaceRoot,
): IntellijDetachedRelationFile {
    val native =
        when (val classified = relationNativePath(file)) {
            is IntellijRelationNativePath.Absolute -> classified.value
            IntellijRelationNativePath.Relative,
            IntellijRelationNativePath.Unavailable -> null
        }
    return when (
        val detached =
            SymbolDiscoveryFileIdentity.fromBoundary(
                workspaceRoot,
                native,
                file.url,
            )
    ) {
        is Refinement.Refined -> IntellijDetachedRelationFile.Found(detached.value)
        is Refinement.Rejected -> IntellijDetachedRelationFile.Unsupported
    }
}
