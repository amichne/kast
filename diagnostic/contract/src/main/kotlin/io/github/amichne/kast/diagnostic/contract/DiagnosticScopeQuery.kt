package io.github.amichne.kast.diagnostic.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A requested file or directory, not yet proof of source membership or completeness. */
class DiagnosticScopeQuery private constructor(
    val lease: SemanticReadLease,
    val path: Path,
) {
    companion object {
        fun parse(lease: SemanticReadLease, raw: String): Refinement<DiagnosticScopeQuery, DiagnosticScopeResolutionFailure> {
            val parsed = try { Path.of(raw) } catch (_: InvalidPathException) {
                return Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
            }
            val root = Path.of(lease.workspaceRoot.value)
            val path = if (parsed.isAbsolute) parsed else root.resolve(parsed).normalize()
            if (raw.isBlank() || path.normalize() != path || !path.startsWith(root)) {
                return Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
            }
            return Refinement.Refined(DiagnosticScopeQuery(lease, path))
        }
    }
}

enum class DiagnosticScopeResolutionFailure {
    INVALID_SCOPE,
    EMPTY,
    LIMIT_EXCEEDED,
    UNAVAILABLE,
    WORKSPACE_NOT_READY,
}

/** Resolves source membership under the same semantic lease; never imports or refreshes. */
fun interface DiagnosticScopeResolver {
    suspend fun resolve(query: DiagnosticScopeQuery): Refinement<DiagnosticScope, DiagnosticScopeResolutionFailure>
}
