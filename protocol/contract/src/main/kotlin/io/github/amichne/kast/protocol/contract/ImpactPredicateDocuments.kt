@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** The tested occurrence omits predicates so the proof cannot recursively contain its own conjunction. */
@Serializable
data class ImpactPredicateSubjectDocument(
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
)

@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactValuePredicateDocument {
    val subject: ImpactPredicateSubjectDocument

    @Serializable
    @SerialName("SUBTYPE")
    data class Subtype(
        override val subject: ImpactPredicateSubjectDocument,
        val resolvedType: ImpactDeclarationReferenceDocument,
    ) : ImpactValuePredicateDocument

    @Serializable
    @SerialName("NON_NULL")
    data class NonNull(override val subject: ImpactPredicateSubjectDocument) : ImpactValuePredicateDocument
}

val EmptyImpactPredicates: BoundedProtocolList<ImpactValuePredicateDocument> =
    (BoundedProtocolList.create(emptyList<ImpactValuePredicateDocument>()) as Refinement.Refined).value
