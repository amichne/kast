package io.github.amichne.kast.diagnostic.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path

enum class DiagnosticScopeFailure {
    EMPTY,
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    OUTSIDE_WORKSPACE,
    UNSUPPORTED_FILE_KIND,
    DUPLICATE_FILE,
}

/** Detached identity of one canonical Kotlin file in an exact diagnostic scope. */
@JvmInline
value class DiagnosticSourceFile internal constructor(
    val value: String,
)

/**
 * Non-empty deterministic Kotlin-file scope permanently bound to one semantic read lease.
 */
class DiagnosticScope private constructor(
    val lease: SemanticReadLease,
    files: List<DiagnosticSourceFile>,
) {
    val files: List<DiagnosticSourceFile> = files.toList()

    companion object {
        /**
         * Proof transition: `(SemanticReadLease, Iterable<Path>) ->
         * Refinement<DiagnosticScope, Set<DiagnosticScopeFailure>>`.
         *
         * Establishes a non-empty, duplicate-free, canonical, deterministically ordered set of
         * Kotlin files strictly below the lease's exact workspace root. The returned scope
         * preserves the root and semantic generation proof. [DiagnosticScopeFailure] is the
         * closed expected failure. Raw [Path] extraction is permitted only at the request-local
         * IntelliJ VFS lookup boundary.
         */
        fun fromCanonicalPaths(
            lease: SemanticReadLease,
            paths: Iterable<Path>,
        ): Refinement<DiagnosticScope, Set<DiagnosticScopeFailure>> {
            val candidates = paths.toList()
            if (candidates.isEmpty()) {
                return Refinement.Rejected(setOf(DiagnosticScopeFailure.EMPTY))
            }
            val workspaceRoot = Path.of(lease.workspaceRoot.value)
            val failures = linkedSetOf<DiagnosticScopeFailure>()
            val admitted = mutableListOf<DiagnosticSourceFile>()
            val identities = linkedSetOf<String>()
            candidates.forEach { path ->
                when {
                    !path.isAbsolute -> failures += DiagnosticScopeFailure.NOT_ABSOLUTE
                    path.normalize() != path -> failures += DiagnosticScopeFailure.NOT_NORMALIZED
                    path == workspaceRoot || !path.startsWith(workspaceRoot) ->
                        failures += DiagnosticScopeFailure.OUTSIDE_WORKSPACE
                    path.fileName?.toString()?.let { name ->
                        name.endsWith(".kt") || name.endsWith(".kts")
                    } != true ->
                        failures += DiagnosticScopeFailure.UNSUPPORTED_FILE_KIND
                    !identities.add(path.toString()) ->
                        failures += DiagnosticScopeFailure.DUPLICATE_FILE
                    else -> admitted += DiagnosticSourceFile(path.toString())
                }
            }
            return if (failures.isEmpty()) {
                Refinement.Refined(
                    DiagnosticScope(
                        lease,
                        admitted.sortedBy(DiagnosticSourceFile::value),
                    ),
                )
            } else {
                Refinement.Rejected(failures)
            }
        }
    }
}
