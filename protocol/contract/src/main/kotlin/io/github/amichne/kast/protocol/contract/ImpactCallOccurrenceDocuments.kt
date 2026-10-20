@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactCallOccurrenceDocument {
    val range: ImpactSourceRangeDocument
    val executionOwner: ImpactExecutionOwnerDocument

    @Serializable
    @SerialName("DECLARED")
    data class Declared(val invocation: ImpactInvocationReferenceDocument) : ImpactCallOccurrenceDocument {
        override val range
            get() = invocation.range

        override val executionOwner
            get() = invocation.executionOwner
    }

    @Serializable
    @SerialName("FUNCTION")
    data class Function(val invocation: ImpactFunctionInvocationReferenceDocument) : ImpactCallOccurrenceDocument {
        override val range
            get() = invocation.range

        override val executionOwner
            get() = invocation.executionOwner
    }
}

@Serializable
data class ImpactFunctionInvocationReferenceDocument(
    val range: ImpactSourceRangeDocument,
    val callable: QuerySourceLessCallableWireDocument,
    @EncodeDefault val executionOwner: ImpactExecutionOwnerDocument = ImpactExecutionOwnerDocument.Declaration,
)

/** The supplying source frame has a declared invocation and cannot recursively contain a callback target. */
@Serializable
data class ImpactDeclaredCallFrameDocument(
    val enclosing: ImpactDeclarationReferenceDocument,
    val invocation: ImpactInvocationReferenceDocument,
    val callee: ImpactExecutionReferenceDocument,
    @EncodeDefault val target: ImpactDeclaredCallTargetDocument = ImpactCallTargetDocument.Direct,
)
