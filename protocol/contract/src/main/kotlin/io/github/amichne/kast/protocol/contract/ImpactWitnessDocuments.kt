@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
enum class ImpactWitnessSectionDocument {
    PRODUCERS,
    MODELS,
    NATIVE_READS,
    READ_REJECTIONS,
}

/** Stable ordinal within one section of the original immutable retained investigation. */
@Serializable
data class ImpactWitnessItemDocument(
    val section: ImpactWitnessSectionDocument,
    val ordinal: QueryDiscoveryCountDocument,
    val witness: ImpactWitnessDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactWitnessDocument {
    @Serializable
    @SerialName("PRODUCER_SITE_ONLY")
    data class ProducerSiteOnly(val site: ImpactValueSiteReferenceDocument) : ImpactWitnessDocument

    @Serializable
    @SerialName("PRODUCER")
    data class Producer(val site: ImpactValueSiteReferenceDocument, val invocation: ImpactInvocationReferenceDocument) :
        ImpactWitnessDocument

    @Serializable
    @SerialName("REPRESENTATION_MODEL")
    data class RepresentationModel(
        val reference: ImpactRuleReferenceDocument,
        val rule: ImpactRepresentationRuleDocument,
    ) : ImpactWitnessDocument

    @Serializable
    @SerialName("BOUNDARY_MODEL")
    data class BoundaryModel(val reference: ImpactRuleReferenceDocument, val rule: ImpactBoundaryRuleDocument) :
        ImpactWitnessDocument

    @Serializable
    @SerialName("NATIVE_READ")
    data class NativeRead(
        val observationOrdinal: QueryDiscoveryCountDocument,
        val source: ImpactValueSiteReferenceDocument,
        val domain: ImpactFlowDomainDocument,
        val examinedWorkUnits: QueryDiscoveryCountDocument,
        val retainedBytes: QueryDiscoveryCountDocument,
        val terminal: ImpactNativeObservationTerminalDocument,
        val transferCount: QueryDiscoveryCountDocument,
        val obligationCount: QueryDiscoveryCountDocument,
    ) : ImpactWitnessDocument

    @Serializable
    @SerialName("COMPILER_TRANSFER")
    data class CompilerTransfer(
        val observationOrdinal: QueryDiscoveryCountDocument,
        val transferOrdinal: QueryDiscoveryCountDocument,
        val transfer: ImpactCompilerTransferDocument,
    ) : ImpactWitnessDocument

    @Serializable
    @SerialName("FLOW_OBLIGATION")
    data class FlowObligation(
        val observationOrdinal: QueryDiscoveryCountDocument,
        val obligationOrdinal: QueryDiscoveryCountDocument,
        val site: ImpactValueSiteReferenceDocument,
        val cause: ImpactFlowUnsupportedDocument,
    ) : ImpactWitnessDocument

    @Serializable
    @SerialName("READ_REJECTION")
    data class ReadRejection(val rejection: ImpactReadRejectionDocument) : ImpactWitnessDocument
}

@Serializable
enum class ImpactNativeObservationTerminalDocument {
    SUPPORTED_DOMAIN_EXHAUSTED,
    UNRESOLVED,
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactAccountingViewDocument {
    @Serializable @SerialName("PATHS") data object Paths : ImpactAccountingViewDocument

    @Serializable
    @SerialName("WITNESS")
    data class Witness(
        val section: ImpactWitnessSectionDocument,
        val firstOrdinal: QueryDiscoveryCountDocument,
        val nextOrdinal: QueryDiscoveryCountDocument,
        val sectionCount: QueryDiscoveryCountDocument,
    ) : ImpactAccountingViewDocument
}
