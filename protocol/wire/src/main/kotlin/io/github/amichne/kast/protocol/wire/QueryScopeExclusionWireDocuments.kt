package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationObservationDocument
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryScopeMembershipAuthorityDocument
import io.github.amichne.kast.protocol.contract.QueryWalkScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class QueryExcludedCompilerTargetWireDocument(
    val file: String,
    val range: SourceRangeWireDocument,
    val name: String,
    val kind: SymbolKindWireDocument,
    @SerialName("compiler_evidence") val compilerEvidence: CompilerSymbolEvidenceWireDocument,
)

@Serializable
internal data class QueryScopeExclusionWireDocument(
    val occurrence: RelationOccurrenceWireDocument,
    val target: QueryExcludedCompilerTargetWireDocument,
    val reason: QueryScopeExclusionReasonDocument,
    @SerialName("membership_authority") val membershipAuthority: QueryScopeMembershipAuthorityDocument,
    @SerialName("requested_domain") val requestedDomain: QueryRelationRequestedDomainDocument,
    @SerialName("effective_domain") val effectiveDomain: QueryRelationDomainDocument,
    @SerialName("domain_fingerprint") val domainFingerprint: QueryRelationDomainFingerprint,
)

@Serializable
internal data class QueryWalkScopeExclusionWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    val depth: Int,
    val exclusion: QueryScopeExclusionWireDocument,
)

@Serializable
internal data class QueryRelationObservationWireDocument(
    val subject: QueryReferenceWireDocument.ExactSymbol,
    val relation: RelationKindDocument,
    val provider: RelationProviderDocument,
    @SerialName("requested_domain") val requestedDomain: QueryRelationRequestedDomainDocument,
    @SerialName("effective_domain") val effectiveDomain: QueryRelationDomainDocument,
    @SerialName("domain_fingerprint") val domainFingerprint: QueryRelationDomainFingerprint,
    val coverage: QueryRelationCoverageDocument,
    @SerialName("scope_exclusions") val scopeExclusions: List<QueryScopeExclusionWireDocument>,
)

internal fun QueryScopeExclusionDocument.toWireDocument() =
    QueryScopeExclusionWireDocument(
        RelationOccurrenceWireDocument(
            occurrence.candidateSelector.value,
            occurrence.file.value,
            occurrence.range.toWireDocument(),
        ),
        QueryExcludedCompilerTargetWireDocument(
            target.file.value,
            target.range.toWireDocument(),
            target.name.value,
            target.kind.toWireDocument(),
            target.compilerEvidence.toWireDocument(),
        ),
        reason,
        membershipAuthority,
        requestedDomain,
        effectiveDomain,
        domainFingerprint,
    )

internal fun QueryScopeExclusionWireDocument.toContract(): WireDocumentConversion<QueryScopeExclusionDocument> =
    occurrence.toContract().flatMapConverted { admittedOccurrence ->
        combineConverted(
                ProtocolText.parse(target.file).toWireDocumentConversion(),
                target.range.toContract(),
                ProtocolText.parse(target.name).toWireDocumentConversion(),
                target.compilerEvidence.toContract(),
            ) { file, range, name, evidence ->
                QueryExcludedCompilerTargetDocument.create(file, range, name, target.kind.toContract(), evidence)
                    .toWireDocumentConversion()
            }
            .flattenConverted()
            .mapConverted { admittedTarget ->
                QueryScopeExclusionDocument(
                    admittedOccurrence,
                    admittedTarget,
                    reason,
                    membershipAuthority,
                    requestedDomain,
                    effectiveDomain,
                    domainFingerprint,
                )
            }
    }

internal fun QueryWalkScopeExclusionDocument.toWireDocument() =
    QueryWalkScopeExclusionWireDocument(
        QueryReferenceWireDocument.ExactSymbol(subject.token.value),
        depth.value,
        exclusion.toWireDocument(),
    )

internal fun QueryWalkScopeExclusionWireDocument.toContract(): WireDocumentConversion<QueryWalkScopeExclusionDocument> =
    combineConverted(
        ProtocolText.parse(subject.token).toWireDocumentConversion(),
        TraversalDepthDocument.parse(depth).toWireDocumentConversion(),
        exclusion.toContract(),
    ) { token, depth, exclusion ->
        QueryWalkScopeExclusionDocument(QueryReferenceDocument.ExactSymbol(token), depth, exclusion)
    }

internal fun QueryRelationObservationDocument.toWireDocument() =
    QueryRelationObservationWireDocument(
        QueryReferenceWireDocument.ExactSymbol(subject.token.value),
        relation,
        provider,
        requestedDomain,
        effectiveDomain,
        domainFingerprint,
        coverage,
        scopeExclusions.values.map(QueryScopeExclusionDocument::toWireDocument),
    )

internal fun QueryRelationObservationWireDocument.toContract():
    WireDocumentConversion<QueryRelationObservationDocument> =
    ProtocolText.parse(subject.token).toWireDocumentConversion().flatMapConverted { token ->
        scopeExclusions.convertEach(QueryScopeExclusionWireDocument::toContract).flatMapConverted { exclusions ->
            BoundedProtocolList.create(exclusions).toWireDocumentConversion().mapConverted { admittedExclusions ->
                QueryRelationObservationDocument(
                    QueryReferenceDocument.ExactSymbol(token),
                    relation,
                    provider,
                    requestedDomain,
                    effectiveDomain,
                    domainFingerprint,
                    coverage,
                    admittedExclusions,
                )
            }
        }
    }
