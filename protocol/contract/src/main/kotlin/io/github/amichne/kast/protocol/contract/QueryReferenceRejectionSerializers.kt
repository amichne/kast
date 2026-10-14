@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

enum class QueryReferenceEvidenceFailure {
    MISMATCH_REQUIRES_STALE_AUTHORITY
}

internal object QueryReferenceRejectedSerializer : KSerializer<QueryRunRejection.ReferenceRejected> {
    private val delegate = QueryRunRejection.ReferenceRejected.generatedSerializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryRunRejection.ReferenceRejected) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): QueryRunRejection.ReferenceRejected {
        val decoded = delegate.deserialize(decoder)
        return when (
            val admitted =
                QueryRunRejection.ReferenceRejected.admit(decoded.position, decoded.reason, decoded.authorityMismatch)
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                throw SerializationException("Authority mismatch requires stale authority rejection")
        }
    }
}

internal object QueryStepReferenceRejectedSerializer : KSerializer<QueryRunRejection.StepReferenceRejected> {
    private val delegate = QueryRunRejection.StepReferenceRejected.generatedSerializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryRunRejection.StepReferenceRejected) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): QueryRunRejection.StepReferenceRejected {
        val decoded = delegate.deserialize(decoder)
        return when (
            val admitted =
                QueryRunRejection.ReferenceRejected.admit(
                    decoded.referencePosition,
                    decoded.reason,
                    decoded.authorityMismatch,
                )
        ) {
            is Refinement.Refined ->
                QueryRunRejection.StepReferenceRejected.fromReference(decoded.stepPosition, admitted.value)
            is Refinement.Rejected ->
                throw SerializationException("Authority mismatch requires stale authority rejection")
        }
    }
}

internal object QueryImpactReferenceRejectedSerializer : KSerializer<QueryImpactSourceFailureDocument.Reference> {
    private val delegate = QueryImpactSourceFailureDocument.Reference.generatedSerializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: QueryImpactSourceFailureDocument.Reference) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): QueryImpactSourceFailureDocument.Reference {
        val decoded = delegate.deserialize(decoder)
        return when (
            val admitted =
                QueryRunRejection.ReferenceRejected.admit(decoded.position, decoded.reason, decoded.authorityMismatch)
        ) {
            is Refinement.Refined -> QueryImpactSourceFailureDocument.Reference.fromReference(admitted.value)
            is Refinement.Rejected ->
                throw SerializationException("Authority mismatch requires stale authority rejection")
        }
    }
}
