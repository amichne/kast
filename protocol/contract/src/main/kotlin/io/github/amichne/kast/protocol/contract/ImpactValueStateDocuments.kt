@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactValuePathSegmentDocument {
    @Serializable @SerialName("ELEMENT") data object Element : ImpactValuePathSegmentDocument

    @Serializable
    @SerialName("FIELD")
    data class Field(val declaration: ImpactDeclarationReferenceDocument) : ImpactValuePathSegmentDocument
}

@Serializable
data class ImpactExecutionReferenceDocument(
    val enclosing: ImpactDeclarationReferenceDocument,
    @EncodeDefault val executionOwner: ImpactExecutionOwnerDocument = ImpactExecutionOwnerDocument.Declaration,
)

@Serializable
data class ImpactCallFrameDocument(
    val enclosing: ImpactDeclarationReferenceDocument,
    val invocation: ImpactCallOccurrenceDocument,
    val callee: ImpactExecutionReferenceDocument,
    @EncodeDefault val target: ImpactCallTargetDocument = ImpactCallTargetDocument.Direct,
) {
    constructor(
        enclosing: ImpactDeclarationReferenceDocument,
        invocation: ImpactInvocationReferenceDocument,
        callee: ImpactExecutionReferenceDocument,
        target: ImpactCallTargetDocument = ImpactCallTargetDocument.Direct,
    ) : this(enclosing, ImpactCallOccurrenceDocument.Declared(invocation), callee, target)
}

@Serializable
data class ImpactFormalParameterDocument(
    val callable: ImpactDeclarationReferenceDocument,
    val index: ProtocolOffset,
    val name: ProtocolText,
    val range: ImpactSourceRangeDocument,
    @EncodeDefault val executionOwner: ImpactExecutionOwnerDocument = ImpactExecutionOwnerDocument.Declaration,
)

@Serializable
enum class ImpactReceiverKindDocument {
    DISPATCH,
    EXTENSION,
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactFormalPositionDocument {
    @Serializable
    @SerialName("PARAMETER")
    data class Parameter(val parameter: ImpactFormalParameterDocument) : ImpactFormalPositionDocument

    @Serializable
    @SerialName("RECEIVER")
    data class Receiver(val kind: ImpactReceiverKindDocument) : ImpactFormalPositionDocument
}

@Serializable
data class ImpactConstructorStorageDocument(
    val constructor: ImpactDeclarationReferenceDocument,
    val parameter: ImpactFormalParameterDocument,
    val field: ImpactDeclarationReferenceDocument,
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactSourceOperationDocument {
    @Serializable
    @SerialName("ENTER")
    data class Enter(val frame: ImpactCallFrameDocument, val position: ImpactFormalPositionDocument) :
        ImpactSourceOperationDocument

    @Serializable
    @SerialName("RETURN")
    data class Return(val frame: ImpactCallFrameDocument) : ImpactSourceOperationDocument

    @Serializable
    @SerialName("SEED_RETURN")
    data class SeedReturn(val frame: ImpactCallFrameDocument) : ImpactSourceOperationDocument

    @Serializable
    @SerialName("DECLARED_CALLBACK_RESULT")
    data class DeclaredCallbackResult(val binding: ImpactDeclaredCallbackValueBindingDocument) :
        ImpactSourceOperationDocument

    @Serializable
    @SerialName("CALLBACK_RETURN")
    data class CallbackReturn(val invocation: ImpactCaptureInvocationDocument, val frame: ImpactCallFrameDocument) :
        ImpactSourceOperationDocument

    @Serializable
    @SerialName("CALLBACK_ARGUMENT_ENTRY")
    data class CallbackArgumentEntry(
        val invocation: ImpactCaptureInvocationDocument,
        val supplierFrame: ImpactCallFrameDocument,
        val parameter: ImpactFormalParameterDocument,
        val argumentRange: ImpactSourceRangeDocument,
    ) : ImpactSourceOperationDocument

    @Serializable
    @SerialName("READ_FIELD")
    data class ReadField(val field: ImpactDeclarationReferenceDocument) : ImpactSourceOperationDocument

    @Serializable
    @SerialName("CONSTRUCT_FIELD")
    data class ConstructField(
        val invocation: ImpactInvocationReferenceDocument,
        val storage: ImpactConstructorStorageDocument,
    ) : ImpactSourceOperationDocument
}

val EmptyImpactValuePath: BoundedProtocolList<ImpactValuePathSegmentDocument> =
    (BoundedProtocolList.create(emptyList<ImpactValuePathSegmentDocument>()) as Refinement.Refined).value

val EmptyImpactCallStack: BoundedProtocolList<ImpactCallFrameDocument> =
    (BoundedProtocolList.create(emptyList<ImpactCallFrameDocument>()) as Refinement.Refined).value
