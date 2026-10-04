@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Original target ordinal and native identity, separate from producer relationship proof. */
@Serializable
data class ImpactSiteAccountingDocument(
    val requestedSiteOrdinal: QueryDiscoveryCountDocument,
    val site: ImpactValueSiteReferenceDocument,
    val outcome: ImpactSiteOutcomeDocument,
    val admission: ImpactSiteAdmissionDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactSiteOutcomeDocument {
    @Serializable
    @SerialName("REACHED")
    data class Reached(
        @ProtocolCollectionConstraint(minimumItems = 1)
        val paths: BoundedProtocolList<ImpactFindingEvidenceReferenceDocument>,
        val exclusions: BoundedProtocolList<ImpactSiteExclusionDocument>,
    ) : ImpactSiteOutcomeDocument

    @Serializable
    @SerialName("EXCLUDED")
    data class Excluded(
        @ProtocolCollectionConstraint(minimumItems = 1) val exclusions: BoundedProtocolList<ImpactSiteExclusionDocument>
    ) : ImpactSiteOutcomeDocument

    @Serializable @SerialName("RELATIONSHIP_UNPROVEN") data object RelationshipUnproven : ImpactSiteOutcomeDocument
}

@Serializable
data class ImpactSiteAdmissionDocument(
    val budget: ImpactFlowBudgetDocument,
    val examinedWorkUnits: QueryDiscoveryCountDocument,
)

@Serializable
data class ImpactSiteExclusionDocument(
    val path: ImpactFindingEvidenceReferenceDocument,
    val domain: ImpactRequestedBoundaryDocument,
    val cause: ImpactScopeExclusionDocument,
)
