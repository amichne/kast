@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class ImpactInvocationReferenceDocument(
    val range: ImpactSourceRangeDocument,
    val callable: ImpactDeclarationReferenceDocument,
    @EncodeDefault val executionOwner: ImpactExecutionOwnerDocument = ImpactExecutionOwnerDocument.Declaration,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactExecutionOwnerDocument {
    @Serializable @SerialName("DECLARATION") data object Declaration : ImpactExecutionOwnerDocument

    @Serializable
    @SerialName("ANONYMOUS")
    data class Anonymous(val body: ImpactDeclarationReferenceDocument) : ImpactExecutionOwnerDocument
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactValueRoleDocument {
    @Serializable @SerialName("EXPRESSION_RESULT") data object ExpressionResult : ImpactValueRoleDocument

    @Serializable @SerialName("LOCAL_BINDING") data object LocalBinding : ImpactValueRoleDocument

    @Serializable @SerialName("LOCAL_READ") data object LocalRead : ImpactValueRoleDocument

    @Serializable
    @SerialName("ARGUMENT")
    data class Argument(val invocation: ImpactInvocationReferenceDocument, val index: ProtocolOffset) :
        ImpactValueRoleDocument

    @Serializable
    @SerialName("RECEIVER")
    data class Receiver(val invocation: ImpactInvocationReferenceDocument, val kind: ImpactReceiverKindDocument) :
        ImpactValueRoleDocument

    @Serializable
    @SerialName("FORMAL")
    data class Formal(val position: ImpactFormalPositionDocument) : ImpactValueRoleDocument

    @Serializable @SerialName("RETURN") data object Return : ImpactValueRoleDocument

    @Serializable @SerialName("PROPERTY_ASSIGNMENT") data object PropertyAssignment : ImpactValueRoleDocument
}

@Serializable
data class ImpactValueSiteReferenceDocument(
    val enclosing: ImpactDeclarationReferenceDocument,
    val range: ImpactSourceRangeDocument,
    val role: ImpactValueRoleDocument,
    @EncodeDefault val executionOwner: ImpactExecutionOwnerDocument = ImpactExecutionOwnerDocument.Declaration,
    @EncodeDefault
    @ProtocolCollectionConstraint(maximumItems = 8)
    val valuePath: BoundedProtocolList<ImpactValuePathSegmentDocument> = EmptyImpactValuePath,
    @EncodeDefault
    @ProtocolCollectionConstraint(maximumItems = 32)
    val callStack: BoundedProtocolList<ImpactCallFrameDocument> = EmptyImpactCallStack,
    @EncodeDefault
    @ProtocolCollectionConstraint(maximumItems = 16, uniqueItems = true)
    val predicates: BoundedProtocolList<ImpactValuePredicateDocument> = EmptyImpactPredicates,
)

@Serializable
enum class ImpactBoundaryKindDocument {
    SERIALIZATION,
    PERSISTENCE,
    EXTERNAL_SYSTEM,
}

@Serializable
enum class ImpactBoundaryCompatibilityDocument {
    CONTRACT_COMPATIBLE,
    REPRESENTATION_PRESERVED,
}

@Serializable
enum class ImpactBoundaryTerminalDocument {
    REVIEWED_DISPOSAL,
    REVIEWED_EXTERNAL_SINK,
    REVIEWED_RETENTION,
}

@Serializable
data class ImpactBoundaryContractDocument(
    val id: ImpactModelIdentifierDocument,
    val version: ImpactModelVersionDocument,
)

@Serializable
data class ImpactBoundaryPositionDocument(
    val site: ImpactValueSiteReferenceDocument,
    val kind: ImpactBoundaryKindDocument,
    val contract: ImpactBoundaryContractDocument,
    val slot: ImpactModelIdentifierDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactBoundaryRuleDocument {
    val id: ImpactModelIdentifierDocument

    @Serializable
    @SerialName("CONTINUATION")
    data class Continuation(
        override val id: ImpactModelIdentifierDocument,
        val source: ImpactBoundaryPositionDocument,
        val target: ImpactBoundaryPositionDocument,
        @ProtocolCollectionConstraint(maximumItems = 2, uniqueItems = true)
        val assumptions: BoundedProtocolList<ImpactBoundaryCompatibilityDocument>,
    ) : ImpactBoundaryRuleDocument

    @Serializable
    @SerialName("TERMINAL")
    data class Terminal(
        override val id: ImpactModelIdentifierDocument,
        val source: ImpactBoundaryPositionDocument,
        val meaning: ImpactBoundaryTerminalDocument,
    ) : ImpactBoundaryRuleDocument
}
