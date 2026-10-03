@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
enum class ImpactFlowSemanticsDocument {
    KOTLIN_FORWARD_V1
}

@Serializable
enum class ImpactRequiredObligationDocument {
    NATIVE_FLOW,
    BOUNDARY,
    REPRESENTATION_STATE,
    EXECUTION_BOUNDARY,
    PRODUCER_IDENTITY,
    REQUESTED_SITE_RELATIONSHIP,
}

/** A qualification projected from retained investigation evidence, never a separate completion authority. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactAccountingDocument {
    @Serializable @SerialName("NOT_APPLICABLE") data object NotApplicable : ImpactAccountingDocument

    @Serializable
    @SerialName("EVIDENCE_ONLY")
    data class EvidenceOnly(val pagePathCount: QueryDiscoveryCountDocument) : ImpactAccountingDocument

    @Serializable
    @SerialName("INVESTIGATED")
    data class Investigated(
        val seeds: BoundedProtocolList<ImpactValueSiteReferenceDocument>,
        val requestedDomain: ImpactRequestedBoundaryDocument,
        val semantics: ImpactFlowSemanticsDocument,
        val representationModelReferences: BoundedProtocolList<ImpactRuleReferenceDocument>,
        val boundaryModelReferences: BoundedProtocolList<ImpactRuleReferenceDocument>,
        val originalReadRejectionCount: QueryDiscoveryCountDocument,
        val originalObservationCount: QueryDiscoveryCountDocument,
        val originalPathCount: QueryDiscoveryCountDocument,
        val pagePathCount: QueryDiscoveryCountDocument,
        val status: ImpactAccountingStatusDocument,
        val view: ImpactAccountingViewDocument,
        @ProtocolCollectionConstraint(maximumItems = 128)
        val requestedSites: BoundedProtocolList<ImpactValueSiteReferenceDocument>,
    ) : ImpactAccountingDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactAccountingStatusDocument {
    @Serializable @SerialName("CONSERVED") data object Conserved : ImpactAccountingStatusDocument

    @Serializable
    @SerialName("UNRESOLVED")
    data class Unresolved(val required: BoundedProtocolList<ImpactRequiredObligationDocument>) :
        ImpactAccountingStatusDocument

    @Serializable
    @SerialName("SELECTED_SUBSET")
    data class SelectedSubset(val originalClosure: ImpactClosureDocument) : ImpactAccountingStatusDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactClosureDocument {
    @Serializable @SerialName("DISCHARGED") data object Discharged : ImpactClosureDocument

    @Serializable
    @SerialName("UNRESOLVED")
    data class Unresolved(val required: BoundedProtocolList<ImpactRequiredObligationDocument>) : ImpactClosureDocument
}
