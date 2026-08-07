package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.idea.SemanticPathContentIdentity
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal sealed interface WorkspaceTransitionRequest {
    val signal: WorkspaceSignal

    data class Unkeyed(override val signal: WorkspaceSignal) : WorkspaceTransitionRequest

    @ConsistentCopyVisibility
    data class SourceFiles internal constructor(
        val claims: WorkspaceSourceFreshnessClaims,
    ) : WorkspaceTransitionRequest {
        override val signal: WorkspaceSignal = WorkspaceSignal.Source
    }

    companion object {
        /**
         * Boundary transition:
         * `(workspace Path, normalized source Paths) -> WorkspaceTransitionRequest`.
         *
         * Produces exact path-and-content claims only when every requested path
         * belongs to the workspace source authority and is either a regular
         * file or a proven tombstone. Any unprovable path safely degrades to an
         * unkeyed source request, which cannot join an active cycle.
         */
        fun sourceFiles(
            workspaceRoot: Path,
            paths: List<NormalizedPath>,
        ): WorkspaceTransitionRequest = when (
            val captured = WorkspaceSourceFreshnessClaims.capture(workspaceRoot, paths)
        ) {
            is WorkspaceSourceFreshnessCapture.Available -> SourceFiles(captured.claims)
            WorkspaceSourceFreshnessCapture.Unavailable -> Unkeyed(WorkspaceSignal.Source)
        }
    }
}

internal sealed interface WorkspaceSourceContentIdentity {
    data class Present(val hash: FileContentHash) : WorkspaceSourceContentIdentity

    data object Missing : WorkspaceSourceContentIdentity
}

@ConsistentCopyVisibility
internal data class WorkspaceSourceFreshnessClaims private constructor(
    private val claimsByPath: Map<WorkspaceSourcePath, WorkspaceSourceContentIdentity>,
) {
    fun followedBy(later: WorkspaceSourceFreshnessClaims): WorkspaceSourceFreshnessClaims =
        WorkspaceSourceFreshnessClaims(claimsByPath + later.claimsByPath)

    /**
     * Proof transition:
     * `(active WorkspaceSourceFreshnessClaims, requested claims)`
     * `-> WorkspaceSourceFreshnessCoverage`.
     *
     * Coverage exists only when the active cycle contains the exact content or
     * tombstone identity for every requested canonical source path.
     */
    fun coverageOf(requested: WorkspaceSourceFreshnessClaims): WorkspaceSourceFreshnessCoverage =
        if (requested.claimsByPath.all { (path, content) -> claimsByPath[path] == content }) {
            WorkspaceSourceFreshnessCoverage.Covered
        } else {
            WorkspaceSourceFreshnessCoverage.Uncovered
        }

    companion object {
        /**
         * Validation transition:
         * `(workspace Path, normalized Paths) -> WorkspaceSourceFreshnessCapture`.
         *
         * Returns non-empty, workspace-owned source claims or one finite
         * unavailable state. Raw filesystem observations do not escape this
         * boundary.
         */
        fun capture(
            workspaceRoot: Path,
            paths: List<NormalizedPath>,
        ): WorkspaceSourceFreshnessCapture {
            if (paths.isEmpty()) return WorkspaceSourceFreshnessCapture.Unavailable
            val policy = SourceIndexFilePolicy.forWorkspace(workspaceRoot)
            val claims = linkedMapOf<WorkspaceSourcePath, WorkspaceSourceContentIdentity>()
            paths.distinct().forEach { normalized ->
                val sourcePath = policy.sourcePath(normalized.toJavaPath())
                    ?: return WorkspaceSourceFreshnessCapture.Unavailable
                val content = when (val resolved = WorkspaceSourceContentResolution.derive(sourcePath)) {
                    is WorkspaceSourceContentResolution.Available -> resolved.identity
                    WorkspaceSourceContentResolution.Unavailable ->
                        return WorkspaceSourceFreshnessCapture.Unavailable
                }
                claims[sourcePath] = content
            }
            return WorkspaceSourceFreshnessCapture.Available(
                WorkspaceSourceFreshnessClaims(claims.toMap()),
            )
        }
    }
}

internal sealed interface WorkspaceSourceFreshnessCapture {
    data class Available(val claims: WorkspaceSourceFreshnessClaims) : WorkspaceSourceFreshnessCapture

    data object Unavailable : WorkspaceSourceFreshnessCapture
}

internal sealed interface WorkspaceSourceContentResolution {
    data class Available(val identity: WorkspaceSourceContentIdentity) : WorkspaceSourceContentResolution

    data object Unavailable : WorkspaceSourceContentResolution

    companion object {
        /**
         * Validation transition:
         * `WorkspaceSourcePath -> WorkspaceSourceContentResolution`.
         *
         * A regular file becomes an exact hash, a proven absent path becomes a
         * tombstone, and every ambiguous filesystem state remains unavailable.
         */
        fun derive(path: WorkspaceSourcePath): WorkspaceSourceContentResolution {
            val file = path.absolute.value.toJavaPath()
            return try {
                when {
                    Files.isRegularFile(file) -> Available(
                        WorkspaceSourceContentIdentity.Present(
                            FileContentHash.parse(SemanticPathContentIdentity.file(file)),
                        ),
                    )

                    Files.notExists(file) -> Available(WorkspaceSourceContentIdentity.Missing)
                    else -> Unavailable
                }
            } catch (_: IOException) {
                Unavailable
            } catch (_: SecurityException) {
                Unavailable
            }
        }
    }
}

internal sealed interface WorkspaceSourceFreshness {
    data object Absent : WorkspaceSourceFreshness

    data object Unkeyed : WorkspaceSourceFreshness

    data class Claimed(val claims: WorkspaceSourceFreshnessClaims) : WorkspaceSourceFreshness

    /**
     * State transition:
     * `(earlier WorkspaceSourceFreshness, later freshness) -> WorkspaceSourceFreshness`.
     *
     * Later claims replace earlier claims for the same path. Any unkeyed source
     * event removes subsumption authority for the combined work.
     */
    fun followedBy(later: WorkspaceSourceFreshness): WorkspaceSourceFreshness = when (this) {
        Absent -> later
        Unkeyed -> Unkeyed
        is Claimed -> when (later) {
            Absent -> this
            Unkeyed -> Unkeyed
            is Claimed -> Claimed(claims.followedBy(later.claims))
        }
    }

    /**
     * Proof transition:
     * `(active WorkspaceSourceFreshness, WorkspaceTransitionRequest)`
     * `-> WorkspaceSourceFreshnessCoverage`.
     */
    fun coverageOf(request: WorkspaceTransitionRequest): WorkspaceSourceFreshnessCoverage =
        if (this is Claimed && request is WorkspaceTransitionRequest.SourceFiles) {
            claims.coverageOf(request.claims)
        } else {
            WorkspaceSourceFreshnessCoverage.Uncovered
        }

    companion object {
        /**
         * Derivation transition:
         * `WorkspaceTransitionRequest -> WorkspaceSourceFreshness`.
         */
        fun from(request: WorkspaceTransitionRequest): WorkspaceSourceFreshness = when (request) {
            is WorkspaceTransitionRequest.SourceFiles -> Claimed(request.claims)
            is WorkspaceTransitionRequest.Unkeyed -> if (request.signal == WorkspaceSignal.Source) {
                Unkeyed
            } else {
                Absent
            }
        }
    }
}

internal enum class WorkspaceSourceFreshnessCoverage {
    Covered,
    Uncovered,
}
