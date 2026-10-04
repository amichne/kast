@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
enum class ImpactWitnessSectionDocument {
    PRODUCERS,
    MODELS,
    NATIVE_READS,
    READ_REJECTIONS,
    FINDINGS,
    SITE_ACCOUNTING,
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
    @SerialName("PEER_BOUNDARY_MODEL")
    data class PeerBoundaryModel(
        val reference: ImpactRuleReferenceDocument,
        val rule: ImpactBoundaryRuleDocument,
        val admission: ImpactPeerSiteAdmissionDocument,
    ) : ImpactWitnessDocument

    @Serializable
    @SerialName("SITE_ACCOUNTING")
    data class SiteAccounting(val accounting: ImpactSiteAccountingDocument) : ImpactWitnessDocument

    @Serializable @SerialName("FINDING") data class Finding(val finding: ImpactFindingDocument) : ImpactWitnessDocument

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
        val receipts: BoundedProtocolList<ImpactNativeReadReceiptDocument> =
            (BoundedProtocolList.create(emptyList<ImpactNativeReadReceiptDocument>()) as Refinement.Refined).value,
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

/** Compact projection of an original retained path; its reference expands the unchanged full evidence. */
@Serializable
data class ImpactFindingDocument(
    val path: ImpactFindingEvidenceReferenceDocument,
    val producer: ImpactValueSiteReferenceDocument,
    val destination: ImpactValueSiteReferenceDocument,
    val representation: ImpactFindingRepresentationDocument,
    val terminal: ImpactFindingTerminalDocument,
    val boundaryObligations: BoundedProtocolList<ImpactBoundaryObligationDocument>,
)

@Serializable
data class ImpactFindingEvidenceReferenceDocument(
    val pathOrdinal: QueryDiscoveryCountDocument,
    val pathRowId: QueryResultRowReference,
)

@Serializable
data class ImpactFindingBranchDocument(
    val current: ImpactRepresentationCurrentDocument,
    val provenance: BoundedProtocolList<ImpactFindingProvenanceDocument>,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactFindingRepresentationDocument {
    @Serializable @SerialName("NOT_MODELED") data object NotModeled : ImpactFindingRepresentationDocument

    @Serializable
    @SerialName("PRESENT")
    data class Present(val branches: BoundedProtocolList<ImpactFindingBranchDocument>) :
        ImpactFindingRepresentationDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactFindingProvenanceDocument {
    @Serializable
    @SerialName("ORIGIN")
    data class Origin(val reference: ImpactRuleReferenceDocument, val state: ImpactRepresentationStateDocument) :
        ImpactFindingProvenanceDocument

    @Serializable
    @SerialName("MODELED_TRANSFER")
    data class ModeledTransfer(val reference: ImpactRuleReferenceDocument) : ImpactFindingProvenanceDocument

    @Serializable
    @SerialName("MODELED_TRANSFORMATION")
    data class ModeledTransformation(val reference: ImpactRuleReferenceDocument) : ImpactFindingProvenanceDocument

    @Serializable
    @SerialName("BOUNDARY_MODEL")
    data class BoundaryModel(val reference: ImpactRuleReferenceDocument) : ImpactFindingProvenanceDocument

    @Serializable @SerialName("UNMODELED") data object Unmodeled : ImpactFindingProvenanceDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactFindingTerminalDocument {
    @Serializable
    @SerialName("UNRESOLVED_PEER_CONTINUATION")
    data class UnresolvedPeerContinuation(
        val reference: ImpactRuleReferenceDocument,
        val target: ImpactValueSiteReferenceDocument,
        val admission: ImpactPeerSiteAdmissionDocument,
        val reason: ImpactPeerContinuationReasonDocument,
    ) : ImpactFindingTerminalDocument

    @Serializable
    @SerialName("CONSUMER")
    data class Consumer(
        val reference: ImpactRuleReferenceDocument,
        val expectedState: ImpactRepresentationStateDocument,
        val outcome: ImpactConsumerOutcomeDocument,
    ) : ImpactFindingTerminalDocument

    @Serializable
    @SerialName("MODELED_TERMINAL")
    data class ModeledTerminal(
        val reference: ImpactRuleReferenceDocument,
        val source: ImpactBoundaryPositionDocument,
        val meaning: ImpactBoundaryTerminalDocument,
        val obligations: BoundedProtocolList<ImpactBoundaryObligationDocument>,
    ) : ImpactFindingTerminalDocument

    @Serializable
    @SerialName("UNRESOLVED_FLOW")
    data class UnresolvedFlow(val cause: ImpactFlowUnsupportedDocument) : ImpactFindingTerminalDocument

    @Serializable
    @SerialName("UNRESOLVED_READ")
    data class UnresolvedRead(val rejection: ImpactReadRejectionDocument) : ImpactFindingTerminalDocument

    @Serializable
    @SerialName("UNRESOLVED_BOUNDARY")
    data class UnresolvedBoundary(
        val source: ImpactBoundaryPositionDocument,
        val reason: ImpactBoundaryUnresolvedDocument,
        val obligation: ImpactBoundaryObligationDocument,
    ) : ImpactFindingTerminalDocument

    @Serializable
    @SerialName("EXECUTION_STOP")
    data class ExecutionStop(val stop: ImpactFindingExecutionStopDocument) : ImpactFindingTerminalDocument

    @Serializable
    @SerialName("SUPPORTED_DOMAIN_END")
    data class SupportedDomainEnd(val observation: ImpactFlowEndObservationDocument) : ImpactFindingTerminalDocument

    @Serializable
    @SerialName("EXPLICIT_SCOPE_EXCLUSION")
    data class ExplicitScopeExclusion(
        val domain: QueryRelationDomainDocument,
        val cause: ImpactScopeExclusionDocument,
    ) : ImpactFindingTerminalDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactFindingExecutionStopDocument {
    @Serializable
    @SerialName("CYCLE")
    data class Cycle(val repeatedAt: QueryDiscoveryCountDocument) : ImpactFindingExecutionStopDocument

    @Serializable
    @SerialName("CHECKPOINT_CAPACITY")
    data class CheckpointCapacity(
        val requiredBytes: QueryDiscoveryCountDocument,
        val availableBytes: QueryDiscoveryCountDocument,
    ) : ImpactFindingExecutionStopDocument
}

@Serializable
enum class ImpactNativeObservationTerminalDocument {
    SUPPORTED_DOMAIN_EXHAUSTED,
    UNRESOLVED,
    RESOURCE_SUSPENDED,
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

/** Actual per-call authority and detached work/output receipt, separate from accumulated native evidence. */
@Serializable
data class ImpactNativeReadReceiptDocument(
    val domain: ImpactFlowDomainDocument,
    val examinedWorkUnits: QueryDiscoveryCountDocument,
    val returnedResults: QueryDiscoveryCountDocument,
    val returnedBytes: QueryDiscoveryCountDocument,
)
