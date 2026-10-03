package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

@Serializable
enum class QueryScopeExclusionReasonDocument {
    SOURCE_DOMAIN,
    LIBRARY_POLICY,
}

@Serializable
enum class QueryScopeMembershipAuthorityDocument {
    IMPORTED_MODEL_NATIVE_SCOPE
}

/** Compiler facts for an excluded target carry no invented in-domain selector. */
data class QueryExcludedCompilerTargetDocument
private constructor(
    val file: ProtocolText,
    val range: SourceRangeDocument,
    val name: ProtocolText,
    val kind: SymbolKindDocument,
    val compilerEvidence: CompilerSymbolEvidenceDocument,
) {
    companion object {
        fun create(
            file: ProtocolText,
            range: SourceRangeDocument,
            name: ProtocolText,
            kind: SymbolKindDocument,
            compilerEvidence: CompilerSymbolEvidenceDocument,
        ): Refinement<QueryExcludedCompilerTargetDocument, SymbolDocumentFailure> =
            if (!compilerEvidence.signature.supports(kind))
                Refinement.Rejected(SymbolDocumentFailure.SIGNATURE_KIND_MISMATCH)
            else Refinement.Refined(QueryExcludedCompilerTargetDocument(file, range, name, kind, compilerEvidence))
    }
}

/** Occurrence identity, compiler target proof, and imported-model membership keep separate authorities. */
data class QueryScopeExclusionDocument(
    val occurrence: RelationOccurrenceDocument,
    val target: QueryExcludedCompilerTargetDocument,
    val reason: QueryScopeExclusionReasonDocument,
    val membershipAuthority: QueryScopeMembershipAuthorityDocument,
    val requestedDomain: QueryRelationRequestedDomainDocument,
    val effectiveDomain: QueryRelationDomainDocument,
    val domainFingerprint: QueryRelationDomainFingerprint,
)

data class QueryWalkScopeExclusionDocument(
    val subject: QueryReferenceDocument.ExactSymbol,
    val depth: TraversalDepthDocument,
    val exclusion: QueryScopeExclusionDocument,
)
