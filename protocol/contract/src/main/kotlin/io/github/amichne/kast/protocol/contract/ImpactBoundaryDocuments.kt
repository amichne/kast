@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Required
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
    @SerialName("REVIEWED_CALLBACK")
    data class ReviewedCallback(val binding: ImpactReviewedValueBindingDocument) : ImpactExecutionOwnerDocument

    @Serializable
    @SerialName("DECLARED_CALLBACK")
    data class DeclaredCallback(val binding: ImpactDeclaredCallbackValueBindingDocument) : ImpactExecutionOwnerDocument

    @Serializable
    @SerialName("CALLBACK")
    data class Callback(
        val body: ImpactCapturedBodyDocument,
        @ProtocolCollectionConstraint(maximumItems = 1024)
        val invocations: BoundedProtocolList<ImpactCaptureInvocationDocument>,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val selection: ImpactCallbackSelectionDocument = ImpactCallbackSelectionDocument.Unique,
    ) : ImpactExecutionOwnerDocument
}

@Serializable
data class ImpactCapturedBodyDocument(
    val file: ProtocolText,
    val range: ImpactSourceRangeDocument,
    val compilerIdentity: ProtocolText,
)

@Serializable
data class ImpactCaptureInvocationDocument(
    val body: ImpactCapturedBodyDocument,
    val enclosing: ImpactDeclarationReferenceDocument,
    val supply: ImpactInvocationReferenceDocument,
    val supplyOwner: ImpactCapturedBodyDocument,
    val parameter: ImpactSourceRangeDocument,
    val position: ProtocolOffset,
    val invocation: ImpactSourceRangeDocument,
    val invocationOwner: ImpactCapturedBodyDocument,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val supplyTarget: ImpactCallTargetDocument = ImpactCallTargetDocument.Direct,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ProtocolCollectionConstraint(maximumItems = 32)
    val forwardings: BoundedProtocolList<ImpactCallbackForwardingDocument> = EmptyImpactCallbackForwardings,
)

/** An ordered formal-to-formal edge retains the exact selected source-call frame. */
@Serializable
data class ImpactCallbackForwardingDocument(
    val sourceParameter: ImpactSourceRangeDocument,
    val sourcePosition: ProtocolOffset,
    val argument: ImpactSourceRangeDocument,
    val targetFrame: ImpactCallFrameDocument,
    val parameter: ImpactSourceRangeDocument,
    val position: ProtocolOffset,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @ProtocolCollectionConstraint(maximumItems = 1024)
    val callableTransfers: BoundedProtocolList<ImpactCompilerTransferDocument> = EmptyImpactCallbackTransfers,
)

val EmptyImpactCallbackForwardings: BoundedProtocolList<ImpactCallbackForwardingDocument> =
    (BoundedProtocolList.create(emptyList<ImpactCallbackForwardingDocument>()) as Refinement.Refined).value

val EmptyImpactCallbackTransfers: BoundedProtocolList<ImpactCompilerTransferDocument> =
    (BoundedProtocolList.create(emptyList<ImpactCompilerTransferDocument>()) as Refinement.Refined).value

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactValueRoleDocument {
    @Serializable @SerialName("EXPRESSION_RESULT") data object ExpressionResult : ImpactValueRoleDocument

    @Serializable
    @SerialName("STRING_CONVERSION_RESULT")
    data class StringConversionResult(val invocation: ImpactInvocationReferenceDocument) : ImpactValueRoleDocument

    @Serializable
    @SerialName("LOOP_RESULT")
    data class LoopResult(val result: ImpactLoopResultDocument) : ImpactValueRoleDocument

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
    @Required val conditions: BoundedProtocolList<ImpactValueConditionDocument> = EmptyImpactConditions,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val localVersion: ImpactLocalVersionDocument = ImpactLocalVersionDocument.Unversioned,
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
