@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.PositiveLimitFailure
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Detached epoch evidence grants no live read authority. */
@Serializable(with = ReferenceAuthorityEpochSerializer::class)
@JvmInline
value class ReferenceAuthorityEpoch private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<ReferenceAuthorityEpoch, PositiveLimitFailure> =
            if (raw > 0) Refinement.Refined(ReferenceAuthorityEpoch(raw))
            else Refinement.Rejected(PositiveLimitFailure.NOT_POSITIVE)
    }
}

internal object ReferenceAuthorityEpochSerializer :
    RefiningLongSerializer<ReferenceAuthorityEpoch>("ReferenceAuthorityEpoch", 1) {
    override fun raw(value: ReferenceAuthorityEpoch) = value.value

    override fun refine(raw: Long) = ReferenceAuthorityEpoch.parse(raw)
}

enum class ReferenceEpochComparisonFailure {
    EQUAL
}

/** These facts exist only after the decoder admits the reference version, workspace, and host. */
@Serializable
sealed interface ReferenceAuthorityMismatch {
    /** The reference supplies expected. The current admitted authority supplies observed. */
    @Serializable(with = ReferenceEpochMismatchSerializer::class)
    @KeepGeneratedSerializer
    @SerialName("EPOCH_MISMATCH")
    data class EpochMismatch
    private constructor(
        @SerialName("expected_epoch") val expected: ReferenceAuthorityEpoch,
        @SerialName("observed_epoch") val observed: ReferenceAuthorityEpoch,
    ) : ReferenceAuthorityMismatch {
        companion object {
            fun compare(
                expected: ReferenceAuthorityEpoch,
                observed: ReferenceAuthorityEpoch,
            ): Refinement<EpochMismatch, ReferenceEpochComparisonFailure> =
                if (expected == observed) Refinement.Rejected(ReferenceEpochComparisonFailure.EQUAL)
                else Refinement.Refined(EpochMismatch(expected, observed))
        }
    }

    /** An unsupported requested view supplies no admitted epoch pair. */
    @Serializable @SerialName("CONTENT_VIEW_MISMATCH") data object ContentViewMismatch : ReferenceAuthorityMismatch
}

internal object ReferenceEpochMismatchSerializer : KSerializer<ReferenceAuthorityMismatch.EpochMismatch> {
    private val delegate = ReferenceAuthorityMismatch.EpochMismatch.generatedSerializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ReferenceAuthorityMismatch.EpochMismatch) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ReferenceAuthorityMismatch.EpochMismatch {
        val decoded = delegate.deserialize(decoder)
        return when (
            val admitted = ReferenceAuthorityMismatch.EpochMismatch.compare(decoded.expected, decoded.observed)
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> throw SerializationException("Epoch mismatch needs different epochs")
        }
    }
}
