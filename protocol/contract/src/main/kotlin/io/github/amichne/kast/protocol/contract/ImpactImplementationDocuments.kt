@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactCallTargetDocument {
    @Serializable @SerialName("DIRECT") data object Direct : ImpactDeclaredCallTargetDocument

    @Serializable
    @SerialName("IMPLEMENTATION")
    data class Implementation(
        val dispatch: ImpactImplementationDispatchDocument,
        val relation: ImpactImplementationFactDocument,
        val ownership: ImpactSourceOwnershipDocument,
    ) : ImpactDeclaredCallTargetDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactDeclaredCallTargetDocument : ImpactCallTargetDocument

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactFlowObligationEvidenceDocument {
    @Serializable @SerialName("NONE") data object None : ImpactFlowObligationEvidenceDocument

    @Serializable
    @SerialName("STRING_CONVERSION")
    data class StringConversion(
        val compilerIdentity: ProtocolText,
        val signature: ProtocolText,
        val origin: ImpactStringConversionOriginDocument,
        val entryRange: ImpactSourceRangeDocument,
        val inputRange: ImpactSourceRangeDocument,
    ) : ImpactFlowObligationEvidenceDocument

    @Serializable
    @SerialName("CALLBACK_INPUT")
    data class CallbackInput(val failure: ImpactCallbackInputFailureDocument) : ImpactFlowObligationEvidenceDocument

    @Serializable
    @SerialName("EXPRESSION")
    data class Expression(
        @ProtocolStringConstraint(pattern = "^[A-Z][A-Za-z0-9_]{0,127}$", minimumLength = 1, maximumLength = 128)
        val kind: ProtocolText,
        val range: ImpactSourceRangeDocument,
    ) : ImpactFlowObligationEvidenceDocument

    @Serializable
    @SerialName("IMPLEMENTATION_DISPATCH")
    data class ImplementationDispatch(val dispatch: ImpactImplementationDispatchDocument) :
        ImpactFlowObligationEvidenceDocument
}

@Serializable
data class ImpactImplementationEndpointDocument(
    val declaration: ImpactDeclarationReferenceDocument,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val fingerprint: ProtocolText,
)

@Serializable
data class ImpactImplementationOccurrenceDocument(val file: ProtocolText, val range: ImpactSourceRangeDocument)

@Serializable
data class ImpactImplementationFactDocument(
    val subject: ImpactImplementationEndpointDocument,
    val source: ImpactImplementationEndpointDocument,
    val target: ImpactImplementationEndpointDocument,
    val occurrence: ImpactImplementationOccurrenceDocument,
    val authority: ImpactSemanticBasisDocument,
    val provenance: RelationProvenanceDocument,
    val coverage: RelationFactCoverageDocument,
    @EncodeDefault val meaning: RelationKindDocument = RelationKindDocument.IMPLEMENTATIONS,
)

@Serializable
data class ImpactImplementationDispatchDocument(
    val question: ImpactFlowDomainDocument,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val subjectFingerprint: ProtocolText,
    val provider: RelationProviderDocument,
    val facts: BoundedProtocolList<ImpactImplementationFactDocument>,
    val encodedBytes: QueryDiscoveryCountDocument,
    val examinedWorkUnits: QueryDiscoveryCountDocument,
    val resultCount: QueryDiscoveryCountDocument,
    val omissions: BoundedProtocolList<ImpactImplementationOmissionDocument>,
    val scopeExclusions: BoundedProtocolList<ImpactImplementationExclusionDocument>,
    val coverage: ImpactImplementationCoverageDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactImplementationCoverageDocument {
    @Serializable
    @SerialName("EXHAUSTED")
    data class Exhausted(val exactCount: QueryDiscoveryCountDocument) : ImpactImplementationCoverageDocument

    @Serializable
    @SerialName("RESUMABLE")
    data class Resumable(
        val knownMinimum: QueryDiscoveryCountDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 13, uniqueItems = true)
        val limitations: BoundedProtocolList<RelationLimitationDocument>,
        val continuation: ImpactFlowReadPositionDocument.Resume,
    ) : ImpactImplementationCoverageDocument

    @Serializable
    @SerialName("TERMINAL_INCOMPLETE")
    data class TerminalIncomplete(
        val knownMinimum: QueryDiscoveryCountDocument,
        @ProtocolCollectionConstraint(minimumItems = 1, maximumItems = 13, uniqueItems = true)
        val limitations: BoundedProtocolList<RelationLimitationDocument>,
    ) : ImpactImplementationCoverageDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactImplementationOmissionMeasurementDocument {
    @Serializable
    @SerialName("UNMEASURED_ON_PAGE")
    data object UnmeasuredOnPage : ImpactImplementationOmissionMeasurementDocument

    @Serializable
    @SerialName("OBSERVED_ON_PAGE")
    data class ObservedOnPage(val items: QueryDiscoveryCountDocument) : ImpactImplementationOmissionMeasurementDocument
}

@Serializable
data class ImpactImplementationOmissionDocument(
    val provider: RelationProviderDocument,
    val reason: RelationLimitationDocument,
    val measurement: ImpactImplementationOmissionMeasurementDocument,
    @ProtocolCollectionConstraint(maximumItems = 3, uniqueItems = true)
    val samples: BoundedProtocolList<ImpactImplementationOccurrenceDocument>,
    val sampleRetention: RelationOmissionSampleRetentionDocument,
)

@Serializable
data class ImpactImplementationExcludedTargetDocument(
    val declaration: ImpactDeclarationReferenceDocument,
    val name: ProtocolText,
    val kind: SymbolKindDocument,
)

@Serializable
data class ImpactImplementationExclusionDocument(
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val subjectFingerprint: ProtocolText,
    val basis: ImpactSemanticBasisDocument,
    val requestedDomain: QueryRelationRequestedDomainDocument,
    val effectiveDomain: QueryRelationDomainDocument,
    val domainFingerprint: QueryRelationDomainFingerprint,
    val occurrence: ImpactImplementationOccurrenceDocument,
    val target: ImpactImplementationExcludedTargetDocument,
    val reason: QueryScopeExclusionReasonDocument,
    val membershipAuthority: QueryScopeMembershipAuthorityDocument,
)

@Serializable
enum class ImpactSourceKindDocument {
    PRODUCTION,
    TEST,
}

@Serializable
enum class ImpactSourceProvenanceDocument {
    AUTHORED,
    GENERATED,
}

@Serializable
data class ImpactSourceRootDocument(
    val module: ProtocolText,
    val buildRoot: ProtocolText,
    val projectPath: ProtocolText,
    val sourceSet: ProtocolText,
    val sourceRoot: ProtocolText,
    val sourceKind: ImpactSourceKindDocument,
    val provenance: ImpactSourceProvenanceDocument,
)

@Serializable
data class ImpactSourceOwnershipDocument(
    val declaration: ImpactDeclarationReferenceDocument,
    val root: ImpactSourceRootDocument,
)
