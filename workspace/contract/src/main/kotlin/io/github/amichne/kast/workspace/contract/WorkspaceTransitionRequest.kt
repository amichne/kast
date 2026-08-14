package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Semantic input families that can invalidate one published workspace generation.
 */
enum class WorkspaceSignal {
    /** Reconcile the compiler-ready project model imported before worker construction. */
    InitialProjectModel,

    Source,
    BuildSemantic,
    Configuration,
    Scope,
    SemanticEnvironment,
    GitWorktree,
    RecoveryProbe,
    RecoveryAudit,
}

/**
 * Construction transition: `String -> WorkspaceStateIdentity`.
 *
 * Establishes a non-blank detached identity for the exact inputs observed around one
 * reconciliation. Raw extraction is permitted only at evidence persistence and physical identity
 * capture boundaries.
 */
@JvmInline
value class WorkspaceStateIdentity(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Workspace state identity must not be blank" }
    }

    companion object {
        /**
         * Proof transition:
         * `String -> Refinement<WorkspaceStateIdentity, WorkspaceStateIdentityFailure>`.
         *
         * Establishes a non-blank workspace-state identity.
         * [WorkspaceStateIdentityFailure] is the closed expected failure. Raw identity text may
         * enter only from a physical identity resolver or persistent evidence boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<WorkspaceStateIdentity, WorkspaceStateIdentityFailure> =
            if (raw.isBlank()) {
                Refinement.Rejected(WorkspaceStateIdentityFailure.BLANK)
            } else {
                Refinement.Refined(WorkspaceStateIdentity(raw))
            }
    }
}

enum class WorkspaceStateIdentityFailure {
    BLANK,
}

enum class WorkspaceSourcePathFailure {
    BLANK,
    INVALID,
    ABSOLUTE,
    NOT_NORMALIZED,
    ESCAPES_WORKSPACE,
}

/**
 * Detached normalized path relative to one separately admitted canonical workspace root.
 */
@JvmInline
value class WorkspaceSourcePath private constructor(
    val value: String,
) : Comparable<WorkspaceSourcePath> {
    companion object {
        /**
         * Proof transition:
         * `String -> Refinement<WorkspaceSourcePath, WorkspaceSourcePathFailure>`.
         *
         * Establishes a non-blank, normalized, relative path with no parent traversal.
         * [WorkspaceSourcePathFailure] is the closed expected failure. Raw path extraction is
         * permitted only at a physical workspace adapter boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<WorkspaceSourcePath, WorkspaceSourcePathFailure> {
            if (raw.isBlank()) return Refinement.Rejected(WorkspaceSourcePathFailure.BLANK)
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return Refinement.Rejected(WorkspaceSourcePathFailure.INVALID)
            }
            if (path.isAbsolute) return Refinement.Rejected(WorkspaceSourcePathFailure.ABSOLUTE)
            if (path.any { segment -> segment.toString() == ".." }) {
                return Refinement.Rejected(WorkspaceSourcePathFailure.ESCAPES_WORKSPACE)
            }
            val normalized = path.normalize()
            if (normalized != path) {
                return Refinement.Rejected(WorkspaceSourcePathFailure.NOT_NORMALIZED)
            }
            return Refinement.Refined(
                WorkspaceSourcePath(
                    normalized.joinToString(separator = "/") { segment -> segment.toString() },
                ),
            )
        }
    }

    override fun compareTo(other: WorkspaceSourcePath): Int = value.compareTo(other.value)
}

enum class WorkspaceSourceContentHashFailure {
    NOT_SHA256,
}

/**
 * Exact lowercase SHA-256 identity of one source-file content image.
 */
@JvmInline
value class WorkspaceSourceContentHash private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition:
         * `String -> Refinement<WorkspaceSourceContentHash, WorkspaceSourceContentHashFailure>`.
         *
         * Establishes a canonical lowercase SHA-256 digest. [WorkspaceSourceContentHashFailure] is
         * the closed expected failure. Raw digest text may enter only from a physical content
         * hashing adapter.
         */
        fun parse(
            raw: String,
        ): Refinement<WorkspaceSourceContentHash, WorkspaceSourceContentHashFailure> {
            val canonical = raw.lowercase()
            return if (canonical.length == SHA256_HEX_LENGTH && canonical.all { character ->
                    character in '0'..'9' || character in 'a'..'f'
                }
            ) {
                Refinement.Refined(WorkspaceSourceContentHash(canonical))
            } else {
                Refinement.Rejected(WorkspaceSourceContentHashFailure.NOT_SHA256)
            }
        }

        private const val SHA256_HEX_LENGTH = 64
    }
}

sealed interface WorkspaceSourceContentIdentity {
    data class Present(
        val hash: WorkspaceSourceContentHash,
    ) : WorkspaceSourceContentIdentity

    data object Missing : WorkspaceSourceContentIdentity
}

data class WorkspaceSourceFreshnessClaim(
    val path: WorkspaceSourcePath,
    val content: WorkspaceSourceContentIdentity,
)

sealed interface WorkspaceSourceFreshnessClaimsFailure {
    data object Empty : WorkspaceSourceFreshnessClaimsFailure

    data class DuplicatePath(
        val path: WorkspaceSourcePath,
    ) : WorkspaceSourceFreshnessClaimsFailure
}

/**
 * Non-empty exact path-and-content claims for one source request.
 */
class WorkspaceSourceFreshnessClaims private constructor(
    private val claimsByPath: Map<WorkspaceSourcePath, WorkspaceSourceContentIdentity>,
) {
    companion object {
        /**
         * Proof transition:
         * `List<WorkspaceSourceFreshnessClaim> -> Refinement<WorkspaceSourceFreshnessClaims,
         * WorkspaceSourceFreshnessClaimsFailure>`.
         *
         * Establishes a non-empty, uniquely keyed, deterministic source-freshness claim set.
         * [WorkspaceSourceFreshnessClaimsFailure] is the closed expected failure. Individual
         * physical path and content observations may enter only after their own refinement.
         */
        fun refine(
            claims: List<WorkspaceSourceFreshnessClaim>,
        ): Refinement<WorkspaceSourceFreshnessClaims, WorkspaceSourceFreshnessClaimsFailure> {
            if (claims.isEmpty()) {
                return Refinement.Rejected(WorkspaceSourceFreshnessClaimsFailure.Empty)
            }
            val retained = sortedMapOf<WorkspaceSourcePath, WorkspaceSourceContentIdentity>()
            claims.forEach { claim ->
                if (retained.putIfAbsent(claim.path, claim.content) != null) {
                    return Refinement.Rejected(
                        WorkspaceSourceFreshnessClaimsFailure.DuplicatePath(claim.path),
                    )
                }
            }
            return Refinement.Refined(WorkspaceSourceFreshnessClaims(retained.toMap()))
        }
    }

    fun followedBy(later: WorkspaceSourceFreshnessClaims): WorkspaceSourceFreshnessClaims =
        WorkspaceSourceFreshnessClaims(
            (claimsByPath + later.claimsByPath).toSortedMap(),
        )

    fun coverageOf(requested: WorkspaceSourceFreshnessClaims): WorkspaceSourceFreshnessCoverage =
        if (requested.claimsByPath.all { (path, content) -> claimsByPath[path] == content }) {
            WorkspaceSourceFreshnessCoverage.Covered
        } else {
            WorkspaceSourceFreshnessCoverage.Uncovered
        }

    override fun equals(other: Any?): Boolean =
        other is WorkspaceSourceFreshnessClaims && claimsByPath == other.claimsByPath

    override fun hashCode(): Int = claimsByPath.hashCode()

    override fun toString(): String = "WorkspaceSourceFreshnessClaims(count=${claimsByPath.size})"
}

sealed interface WorkspaceTransitionRequest {
    val signal: WorkspaceSignal

    data class Unkeyed(
        override val signal: WorkspaceSignal,
    ) : WorkspaceTransitionRequest

    data class SourceFiles(
        val claims: WorkspaceSourceFreshnessClaims,
    ) : WorkspaceTransitionRequest {
        override val signal: WorkspaceSignal = WorkspaceSignal.Source
    }
}

sealed interface WorkspaceSourceFreshness {
    data object Absent : WorkspaceSourceFreshness

    data object Unkeyed : WorkspaceSourceFreshness

    data class Claimed(
        val claims: WorkspaceSourceFreshnessClaims,
    ) : WorkspaceSourceFreshness

    fun followedBy(later: WorkspaceSourceFreshness): WorkspaceSourceFreshness = when (this) {
        Absent -> later
        Unkeyed -> Unkeyed
        is Claimed -> when (later) {
            Absent -> this
            Unkeyed -> Unkeyed
            is Claimed -> Claimed(claims.followedBy(later.claims))
        }
    }

    fun coverageOf(request: WorkspaceTransitionRequest): WorkspaceSourceFreshnessCoverage =
        if (this is Claimed && request is WorkspaceTransitionRequest.SourceFiles) {
            claims.coverageOf(request.claims)
        } else {
            WorkspaceSourceFreshnessCoverage.Uncovered
        }

    companion object {
        fun from(request: WorkspaceTransitionRequest): WorkspaceSourceFreshness = when (request) {
            is WorkspaceTransitionRequest.SourceFiles -> Claimed(request.claims)
            is WorkspaceTransitionRequest.Unkeyed ->
                if (request.signal == WorkspaceSignal.Source) Unkeyed else Absent
        }
    }
}

enum class WorkspaceSourceFreshnessCoverage {
    Covered,
    Uncovered,
}
