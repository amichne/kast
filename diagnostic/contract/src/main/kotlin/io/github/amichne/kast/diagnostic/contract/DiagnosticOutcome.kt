package io.github.amichne.kast.diagnostic.contract

import io.github.amichne.kast.kernel.Refinement

enum class DiagnosticBatchFailure {
    FACT_OUTSIDE_EXACT_SCOPE,
}

/** Detached diagnostics projected for one exact scope. */
class DiagnosticBatch private constructor(
    val scope: DiagnosticScope,
    facts: List<DiagnosticFact>,
) {
    val facts: List<DiagnosticFact> = facts.toList()

    companion object {
        /**
         * Proof transition: `(DiagnosticScope, Iterable<DiagnosticFact>) ->
         * Refinement<DiagnosticBatch, DiagnosticBatchFailure>`.
         *
         * Establishes that every fact was constructed for the identical exact scope and therefore
         * carries its semantic generation. [DiagnosticBatchFailure] is the closed expected
         * failure. Detached facts may enter only from a request-local compiler collector.
         */
        fun create(
            scope: DiagnosticScope,
            facts: Iterable<DiagnosticFact>,
        ): Refinement<DiagnosticBatch, DiagnosticBatchFailure> {
            val detached = facts.toList()
            return if (detached.all { fact -> fact.scope === scope }) {
                Refinement.Refined(DiagnosticBatch(scope, detached))
            } else {
                Refinement.Rejected(DiagnosticBatchFailure.FACT_OUTSIDE_EXACT_SCOPE)
            }
        }

        /**
         * Proof transition: `DiagnosticScope -> DiagnosticBatch`.
         *
         * Establishes an empty detached batch owned by the exact scope. Empty facts prove no
         * absence until paired with [DiagnosticCompleteCoverage]. Raw extraction is unnecessary.
         */
        fun empty(scope: DiagnosticScope): DiagnosticBatch = DiagnosticBatch(scope, emptyList())
    }
}

enum class DiagnosticLimitationReason {
    FILE_UNAVAILABLE,
    OUTSIDE_SOURCE_CONTENT,
    INDEXING,
    PSI_UNAVAILABLE,
    UNSUPPORTED_FILE_KIND,
    UNSUPPORTED_DIAGNOSTIC,
    ANALYSIS_UNAVAILABLE,
}

data class DiagnosticLimitation(
    val file: DiagnosticSourceFile,
    val reason: DiagnosticLimitationReason,
)

/** Proof that every file in the exact scope was analyzed without limitation. */
class DiagnosticCompleteCoverage internal constructor(
    analyzedFiles: List<DiagnosticSourceFile>,
) {
    val analyzedFiles: List<DiagnosticSourceFile> = analyzedFiles.toList()
}

enum class DiagnosticCoverageFailure {
    EMPTY_LIMITATIONS,
    DUPLICATE_ANALYZED_FILE,
    FILE_OUTSIDE_SCOPE,
    ANALYZED_LIMITED_OVERLAP,
    UNACCOUNTED_FILE,
}

/** Proof that every scope file is either analyzed or explicitly limited, but not both. */
class DiagnosticIncompleteCoverage private constructor(
    analyzedFiles: List<DiagnosticSourceFile>,
    limitations: Set<DiagnosticLimitation>,
) {
    val analyzedFiles: List<DiagnosticSourceFile> = analyzedFiles.toList()
    val limitations: Set<DiagnosticLimitation> = limitations.toSet()

    companion object {
        /**
         * Proof transition: `(DiagnosticScope, Iterable<DiagnosticSourceFile>,
         * Set<DiagnosticLimitation>) ->
         * Refinement<DiagnosticIncompleteCoverage, Set<DiagnosticCoverageFailure>>`.
         *
         * Establishes a non-empty limitation set and exact accounting for every scope file, with
         * no file simultaneously claimed analyzed and limited. [DiagnosticCoverageFailure] is the
         * closed expected failure. Raw provider completion state may enter only from the
         * request-local compiler collector.
         */
        fun create(
            scope: DiagnosticScope,
            analyzedFiles: Iterable<DiagnosticSourceFile>,
            limitations: Set<DiagnosticLimitation>,
        ): Refinement<DiagnosticIncompleteCoverage, Set<DiagnosticCoverageFailure>> {
            val analyzed = analyzedFiles.toList()
            val analyzedSet = analyzed.toSet()
            val limitedFiles = limitations.map(DiagnosticLimitation::file).toSet()
            val scopeFiles = scope.files.toSet()
            val failures = linkedSetOf<DiagnosticCoverageFailure>()
            if (limitations.isEmpty()) {
                failures += DiagnosticCoverageFailure.EMPTY_LIMITATIONS
            }
            if (analyzed.size != analyzedSet.size) {
                failures += DiagnosticCoverageFailure.DUPLICATE_ANALYZED_FILE
            }
            if ((analyzedSet + limitedFiles).any { file -> file !in scopeFiles }) {
                failures += DiagnosticCoverageFailure.FILE_OUTSIDE_SCOPE
            }
            if (analyzedSet.intersect(limitedFiles).isNotEmpty()) {
                failures += DiagnosticCoverageFailure.ANALYZED_LIMITED_OVERLAP
            }
            if (analyzedSet + limitedFiles != scopeFiles) {
                failures += DiagnosticCoverageFailure.UNACCOUNTED_FILE
            }
            return if (failures.isEmpty()) {
                Refinement.Refined(
                    DiagnosticIncompleteCoverage(
                        scope.files.filter(analyzedSet::contains),
                        limitations,
                    ),
                )
            } else {
                Refinement.Rejected(failures)
            }
        }
    }
}

enum class DiagnosticCompilerRejection {
    WORKSPACE_ROOT_MISMATCH,
    GENERATION_MOVED,
    WORKSPACE_INDEX_UNAVAILABLE,
    SCOPE_REJECTED,
    COMPILER_CONTRACT_VIOLATION,
}

/** Closed detached output of the request-local diagnostic compiler boundary. */
sealed interface DiagnosticCompilation {
    @ConsistentCopyVisibility
    data class Complete internal constructor(
        val batch: DiagnosticBatch,
        val coverage: DiagnosticCompleteCoverage,
    ) : DiagnosticCompilation

    @ConsistentCopyVisibility
    data class Qualified internal constructor(
        val batch: DiagnosticBatch,
        val coverage: DiagnosticIncompleteCoverage,
    ) : DiagnosticCompilation

    data class Rejected(
        val reason: DiagnosticCompilerRejection,
    ) : DiagnosticCompilation

    companion object {
        /**
         * Proof transition: `DiagnosticBatch + terminal exact-scope compiler proof ->
         * DiagnosticCompilation.Complete`.
         *
         * Establishes that every exact scope file was analyzed and permits an empty batch to mean
         * diagnostic absence. Only a terminal limitation-free compiler collector may call this
         * boundary; raw platform state never escapes.
         */
        fun complete(batch: DiagnosticBatch): Complete = Complete(
            batch,
            DiagnosticCompleteCoverage(batch.scope.files),
        )

        /**
         * Proof transition: `(DiagnosticBatch, Iterable<DiagnosticSourceFile>,
         * Set<DiagnosticLimitation>) ->
         * Refinement<DiagnosticCompilation.Qualified, Set<DiagnosticCoverageFailure>>`.
         *
         * Establishes exact accounted but incomplete scope coverage. Empty diagnostic evidence
         * remains qualified and cannot mean absence. [DiagnosticCoverageFailure] is the closed
         * expected failure. Raw provider state may enter only from the compiler collector.
         */
        fun qualified(
            batch: DiagnosticBatch,
            analyzedFiles: Iterable<DiagnosticSourceFile>,
            limitations: Set<DiagnosticLimitation>,
        ): Refinement<Qualified, Set<DiagnosticCoverageFailure>> = when (
            val coverage = DiagnosticIncompleteCoverage.create(
                batch.scope,
                analyzedFiles,
                limitations,
            )
        ) {
            is Refinement.Refined -> Refinement.Refined(Qualified(batch, coverage.value))
            is Refinement.Rejected -> coverage
        }
    }
}

/** Internal semantic effect port; implementations return detached compiler evidence only. */
fun interface DiagnosticCompilerPort {
    /**
     * Proof transition: `DiagnosticScope -> DiagnosticCompilation`.
     *
     * A non-rejected result establishes detached exact-scope evidence for the scope generation,
     * with complete or explicitly qualified coverage. [DiagnosticCompilerRejection] is the closed
     * expected failure. Live compiler/platform values remain inside the implementation call.
     */
    suspend fun check(scope: DiagnosticScope): DiagnosticCompilation
}
