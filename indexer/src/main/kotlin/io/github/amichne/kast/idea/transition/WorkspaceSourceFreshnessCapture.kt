package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.idea.SemanticPathContentIdentity
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath as IndexedWorkspaceSourcePath
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaim
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaims
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Boundary transition:
 * `(workspace Path, normalized source Paths) -> WorkspaceTransitionRequest`.
 *
 * Produces exact detached path-and-content claims only when every requested path belongs to the
 * workspace source authority and is either a regular file or a proven tombstone. Any unprovable
 * path becomes an unkeyed source request, which cannot join an active cycle. Filesystem and
 * legacy source-index values are consumed only inside this physical capture adapter.
 */
internal fun captureSourceWorkspaceTransitionRequest(
    workspaceRoot: Path,
    paths: List<NormalizedPath>,
): WorkspaceTransitionRequest = when (
    val captured = WorkspaceSourceFreshnessCapture.capture(workspaceRoot, paths)
) {
    is WorkspaceSourceFreshnessCapture.Available ->
        WorkspaceTransitionRequest.SourceFiles(captured.claims)
    WorkspaceSourceFreshnessCapture.Unavailable ->
        WorkspaceTransitionRequest.Unkeyed(WorkspaceSignal.Source)
}

private sealed interface WorkspaceSourceFreshnessCapture {
    data class Available(
        val claims: WorkspaceSourceFreshnessClaims,
    ) : WorkspaceSourceFreshnessCapture

    data object Unavailable : WorkspaceSourceFreshnessCapture

    companion object {
        /**
         * Validation transition:
         * `(workspace Path, normalized Paths) -> WorkspaceSourceFreshnessCapture`.
         *
         * Returns non-empty, workspace-owned source claims or one finite unavailable state. Raw
         * filesystem observations do not escape this adapter.
         */
        fun capture(
            workspaceRoot: Path,
            paths: List<NormalizedPath>,
        ): WorkspaceSourceFreshnessCapture {
            if (paths.isEmpty()) return Unavailable
            val policy = SourceIndexFilePolicy.forWorkspace(workspaceRoot)
            val claims = mutableListOf<WorkspaceSourceFreshnessClaim>()
            paths.distinct().forEach { normalized ->
                val sourcePath = policy.sourcePath(normalized.toJavaPath()) ?: return Unavailable
                val path = when (val resolved = WorkspaceSourcePath.parse(sourcePath.relative.value)) {
                    is Refinement.Refined -> resolved.value
                    is Refinement.Rejected -> return Unavailable
                }
                val content = when (val resolved = WorkspaceSourceContentResolution.derive(sourcePath)) {
                    is WorkspaceSourceContentResolution.Available -> resolved.identity
                    WorkspaceSourceContentResolution.Unavailable -> return Unavailable
                }
                claims += WorkspaceSourceFreshnessClaim(path, content)
            }
            return when (val refined = WorkspaceSourceFreshnessClaims.refine(claims)) {
                is Refinement.Refined -> Available(refined.value)
                is Refinement.Rejected -> Unavailable
            }
        }
    }
}

private sealed interface WorkspaceSourceContentResolution {
    data class Available(
        val identity: WorkspaceSourceContentIdentity,
    ) : WorkspaceSourceContentResolution

    data object Unavailable : WorkspaceSourceContentResolution

    companion object {
        /**
         * Validation transition:
         * `WorkspaceSourcePath -> WorkspaceSourceContentResolution`.
         *
         * A regular file becomes an exact hash, a proven absent path becomes a tombstone, and every
         * ambiguous filesystem state remains unavailable. Raw path and hash values remain inside
         * this capture adapter.
         */
        fun derive(path: IndexedWorkspaceSourcePath): WorkspaceSourceContentResolution {
            val file = path.absolute.value.toJavaPath()
            return try {
                when {
                    Files.isRegularFile(file) -> presentContent(file)
                    Files.notExists(file) -> Available(WorkspaceSourceContentIdentity.Missing)
                    else -> Unavailable
                }
            } catch (_: IOException) {
                Unavailable
            } catch (_: SecurityException) {
                Unavailable
            }
        }

        private fun presentContent(file: Path): WorkspaceSourceContentResolution {
            val legacyHash = FileContentHash.parse(SemanticPathContentIdentity.file(file))
            return when (val hash = WorkspaceSourceContentHash.parse(legacyHash.value)) {
                is Refinement.Refined ->
                    Available(WorkspaceSourceContentIdentity.Present(hash.value))
                is Refinement.Rejected -> Unavailable
            }
        }
    }
}
