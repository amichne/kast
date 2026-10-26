@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** The originating occurrence without recursively embedding its preceding conditions. */
@Serializable
data class ImpactValueConditionOriginDocument(
    val execution: ImpactExecutionReferenceDocument,
    val range: ImpactSourceRangeDocument,
    val role: ImpactValueRoleDocument,
    @EncodeDefault
    @ProtocolCollectionConstraint(maximumItems = 8)
    val valuePath: BoundedProtocolList<ImpactValuePathSegmentDocument> = EmptyImpactValuePath,
    @EncodeDefault
    @ProtocolCollectionConstraint(maximumItems = 32)
    val callStack: BoundedProtocolList<ImpactCallFrameDocument> = EmptyImpactCallStack,
)

/** Conditional use-site evidence does not establish runtime reachability. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactValueConditionDocument {
    val origin: ImpactValueConditionOriginDocument
    val useRange: ImpactSourceRangeDocument
    val originalType: ProtocolText

    @Serializable
    @SerialName("STABLE_RECEIVER")
    data class StableReceiver(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val kind: ImpactReceiverKindDocument,
        override val originalType: ProtocolText,
        val refinedType: ProtocolText,
    ) : ImpactValueConditionDocument

    /** Successful operand-preserving completion, not proof of erased runtime type arguments. */
    @Serializable
    @SerialName("CAST_SUCCESS")
    data class CastSuccess(
        override val origin: ImpactValueConditionOriginDocument,
        override val useRange: ImpactSourceRangeDocument,
        val kind: ImpactCastKindDocument,
        override val originalType: ProtocolText,
        val targetType: ProtocolText,
    ) : ImpactValueConditionDocument
}

@Serializable
enum class ImpactCastKindDocument {
    UNSAFE,
    SAFE,
}

val EmptyImpactConditions: BoundedProtocolList<ImpactValueConditionDocument> =
    (BoundedProtocolList.create(emptyList<ImpactValueConditionDocument>()) as Refinement.Refined).value
