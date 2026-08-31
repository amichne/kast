package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement

/** Exact topology node projected without losing its compiler or source location identity. */
data class TopologyCoverageNode(
    val compilerIdentity: ProtocolText,
    val file: ProtocolText,
    val range: SourceRangeDocument,
)

enum class TopologyCoverageSymbolKind {
    CLASSLIKE,
    CONSTRUCTOR,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

sealed interface TopologyCoverageQualifiedIdentity {
    data class Available(val value: ProtocolText) : TopologyCoverageQualifiedIdentity
    data object Unavailable : TopologyCoverageQualifiedIdentity
}

/** Exact workspace publication identity retained by one public topology file. */
data class TopologyCoverageWorkspaceEvidence(
    val root: ProtocolText,
    val generation: EvidenceGeneration,
    val sourceState: ProtocolText,
)

enum class TopologyCoverageSourceRootProvenance {
    AUTHORED,
    GENERATED,
    UNKNOWN_EXCLUDED_FROM_SOURCE_MODEL,
}

/** Exact imported Gradle source-root identity retained by one public topology file. */
data class TopologyCoverageSourceRootEvidence(
    val module: ProtocolText,
    val buildRoot: ProtocolText,
    val projectPath: ProtocolText,
    val sourceSet: ProtocolText,
    val location: ProtocolText,
    val provenance: TopologyCoverageSourceRootProvenance,
)

enum class TopologyCoverageSourceHashFailure {
    INVALID_SHA256,
}

/** Exact lowercase SHA-256 content identity retained by one public topology file. */
@JvmInline
value class TopologyCoverageSourceHash private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<TopologyCoverageSourceHash,
         * TopologyCoverageSourceHashFailure>`.
         *
         * Establishes the exact 64-character lowercase SHA-256 form used by topology source
         * evidence. [TopologyCoverageSourceHashFailure] is the closed expected failure. Raw hash
         * text may enter only from topology composition or the generated wire boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<TopologyCoverageSourceHash, TopologyCoverageSourceHashFailure> =
            if (raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' }) {
                Refinement.Refined(TopologyCoverageSourceHash(raw))
            } else {
                Refinement.Rejected(TopologyCoverageSourceHashFailure.INVALID_SHA256)
            }
    }
}

/** Full exact source evidence retained by a public topology coverage failure. */
data class TopologyCoverageFileEvidence(
    val workspace: TopologyCoverageWorkspaceEvidence,
    val sourceRoot: TopologyCoverageSourceRootEvidence,
    val path: ProtocolText,
    val contentHash: TopologyCoverageSourceHash,
)

/** Candidate and completed evidence for one same-path topology extraction mismatch. */
data class TopologyCoverageCandidateEvidenceMismatch(
    val candidate: TopologyCoverageFileEvidence,
    val completed: TopologyCoverageFileEvidence,
)

enum class TopologyCoverageSymbolFailure {
    NODE_COMPILER_IDENTITY_MISMATCH,
    NODE_FILE_MISMATCH,
    SIGNATURE_KIND_MISMATCH,
    QUALIFIED_IDENTITY_MISMATCH,
}

/** Exact contradictory edge endpoint, including its content/source-root and compiler evidence. */
@ConsistentCopyVisibility
data class TopologyCoverageSymbol private constructor(
    val node: TopologyCoverageNode,
    val fileEvidence: TopologyCoverageFileEvidence,
    val name: ProtocolText,
    val qualifiedIdentity: TopologyCoverageQualifiedIdentity,
    val kind: TopologyCoverageSymbolKind,
    val compilerEvidence: CompilerSymbolEvidenceDocument,
) {
    companion object {
        /**
         * Proof transition: exact topology endpoint fields to a compiler-grounded public symbol.
         * The node identity, source file, kind, and qualified identity must all agree with the
         * retained canonical compiler signature. Expected disagreement is finite data.
         */
        fun create(
            node: TopologyCoverageNode,
            fileEvidence: TopologyCoverageFileEvidence,
            name: ProtocolText,
            qualifiedIdentity: TopologyCoverageQualifiedIdentity,
            kind: TopologyCoverageSymbolKind,
            compilerEvidence: CompilerSymbolEvidenceDocument,
        ): Refinement<TopologyCoverageSymbol, TopologyCoverageSymbolFailure> {
            if (node.compilerIdentity != compilerEvidence.identity) {
                return Refinement.Rejected(
                    TopologyCoverageSymbolFailure.NODE_COMPILER_IDENTITY_MISMATCH,
                )
            }
            if (node.file != fileEvidence.path) {
                return Refinement.Rejected(TopologyCoverageSymbolFailure.NODE_FILE_MISMATCH)
            }
            if (!compilerEvidence.signature.supports(kind.symbolKind())) {
                return Refinement.Rejected(TopologyCoverageSymbolFailure.SIGNATURE_KIND_MISMATCH)
            }
            if (
                qualifiedIdentity !is TopologyCoverageQualifiedIdentity.Available ||
                qualifiedIdentity.value != compilerEvidence.signature.qualifiedIdentity()
            ) {
                return Refinement.Rejected(
                    TopologyCoverageSymbolFailure.QUALIFIED_IDENTITY_MISMATCH,
                )
            }
            return Refinement.Refined(
                TopologyCoverageSymbol(
                    node,
                    fileEvidence,
                    name,
                    qualifiedIdentity,
                    kind,
                    compilerEvidence,
                ),
            )
        }
    }
}

private fun TopologyCoverageSymbolKind.symbolKind(): SymbolKindDocument = when (this) {
    TopologyCoverageSymbolKind.CLASSLIKE -> SymbolKindDocument.CLASSLIKE
    TopologyCoverageSymbolKind.CONSTRUCTOR -> SymbolKindDocument.CONSTRUCTOR
    TopologyCoverageSymbolKind.FUNCTION -> SymbolKindDocument.FUNCTION
    TopologyCoverageSymbolKind.PROPERTY -> SymbolKindDocument.PROPERTY
    TopologyCoverageSymbolKind.TYPE_ALIAS -> SymbolKindDocument.TYPE_ALIAS
}

enum class TopologyCoverageFailureAdmissionFailure {
    EMPTY,
}

/** Finite, immutable public projection of every exact topology coverage mismatch. */
@ConsistentCopyVisibility
data class TopologyCoverageFailure private constructor(
    val missing: Set<ProtocolText>,
    val unexpected: Set<ProtocolText>,
    val duplicateCandidates: Set<ProtocolText>,
    val duplicateCompletions: Set<ProtocolText>,
    val workspaceMismatches: Set<ProtocolText>,
    val candidateEvidenceMismatches: Set<TopologyCoverageCandidateEvidenceMismatch>,
    val duplicateSymbols: Set<TopologyCoverageNode>,
    val missingEdgeTargets: Set<TopologyCoverageNode>,
    val mismatchedEdgeEndpoints: Set<TopologyCoverageSymbol>,
) {
    companion object {
        /**
         * Proof transition: `nine finite coverage sets -> Refinement<TopologyCoverageFailure,
         * TopologyCoverageFailureAdmissionFailure>`.
         *
         * Establishes a non-empty coverage failure and detaches every retained mismatch into
         * immutable sets. [TopologyCoverageFailureAdmissionFailure] is the closed expected
         * failure. Typed values may be extracted only by the generated wire serializer and CLI
         * presentation boundaries.
         */
        fun admit(
            missing: Set<ProtocolText>,
            unexpected: Set<ProtocolText>,
            duplicateCandidates: Set<ProtocolText>,
            duplicateCompletions: Set<ProtocolText>,
            workspaceMismatches: Set<ProtocolText>,
            candidateEvidenceMismatches: Set<TopologyCoverageCandidateEvidenceMismatch>,
            duplicateSymbols: Set<TopologyCoverageNode>,
            missingEdgeTargets: Set<TopologyCoverageNode>,
            mismatchedEdgeEndpoints: Set<TopologyCoverageSymbol>,
        ): Refinement<TopologyCoverageFailure, TopologyCoverageFailureAdmissionFailure> {
            val sets = listOf(
                missing,
                unexpected,
                duplicateCandidates,
                duplicateCompletions,
                workspaceMismatches,
                candidateEvidenceMismatches,
                duplicateSymbols,
                missingEdgeTargets,
                mismatchedEdgeEndpoints,
            )
            if (sets.all(Set<*>::isEmpty)) {
                return Refinement.Rejected(TopologyCoverageFailureAdmissionFailure.EMPTY)
            }
            return Refinement.Refined(
                TopologyCoverageFailure(
                    java.util.Set.copyOf(missing),
                    java.util.Set.copyOf(unexpected),
                    java.util.Set.copyOf(duplicateCandidates),
                    java.util.Set.copyOf(duplicateCompletions),
                    java.util.Set.copyOf(workspaceMismatches),
                    java.util.Set.copyOf(candidateEvidenceMismatches),
                    java.util.Set.copyOf(duplicateSymbols),
                    java.util.Set.copyOf(missingEdgeTargets),
                    java.util.Set.copyOf(mismatchedEdgeEndpoints),
                ),
            )
        }
    }
}
