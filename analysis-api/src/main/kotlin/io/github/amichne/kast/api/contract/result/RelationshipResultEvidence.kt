package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
sealed interface RelationshipResultEvidence {
    val cardinality: ResultCardinality
    val coverage: RelationshipSearchCoverage

    @Serializable
    sealed interface Available {
        val cardinality: ResultCardinality
        val coverage: RelationshipSearchCoverage
    }

    @Serializable
    @SerialName("COMPLETE")
    data class Complete(
        @DocField(description = "Exact relationship cardinality proven across the complete requested family.")
        @Serializable(with = ExactRelationshipCardinalitySerializer::class)
        override val cardinality: ResultCardinality.Exact,
        @DocField(description = "Coverage proof with every relationship completeness dimension satisfied.")
        @Serializable(with = CompleteRelationshipCoverageSerializer::class)
        override val coverage: RelationshipSearchCoverage.Complete,
    ) : RelationshipResultEvidence, Available

    @Serializable
    @SerialName("RESUMABLE")
    data class Resumable(
        @DocField(description = "Relationship count proven so far while complete boundary coverage is retained.")
        @Serializable(with = KnownMinimumRelationshipCardinalitySerializer::class)
        override val cardinality: ResultCardinality.KnownMinimum,
        @DocField(description = "Coverage proof whose requested family remains resumable through a continuation.")
        @Serializable(with = ResumableRelationshipCoverageSerializer::class)
        override val coverage: RelationshipSearchCoverage.Resumable,
    ) : RelationshipResultEvidence, Available

    @Serializable
    @SerialName("LIMITED")
    data class Limited(
        @DocField(description = "Relationship count proven without claiming complete coverage.")
        @Serializable(with = KnownMinimumRelationshipCardinalitySerializer::class)
        override val cardinality: ResultCardinality.KnownMinimum,
        @DocField(description = "Incomplete coverage facts and their canonical limitations.")
        @Serializable(with = LimitedRelationshipCoverageSerializer::class)
        override val coverage: RelationshipSearchCoverage.Limited,
    ) : RelationshipResultEvidence

    object CompleteSerializer : KSerializer<Complete> {
        override val descriptor = RelationshipResultEvidence.serializer().descriptor

        override fun serialize(encoder: Encoder, value: Complete) {
            encoder.encodeSerializableValue(RelationshipResultEvidence.serializer(), value)
        }

        override fun deserialize(decoder: Decoder): Complete =
            when (val evidence = decoder.decodeSerializableValue(RelationshipResultEvidence.serializer())) {
                is Complete -> evidence
                is Resumable,
                is Limited,
                -> throw SerializationException("Complete relationship evidence requires the COMPLETE variant")
            }
    }

    object LimitedSerializer : KSerializer<Limited> {
        override val descriptor = RelationshipResultEvidence.serializer().descriptor

        override fun serialize(encoder: Encoder, value: Limited) {
            encoder.encodeSerializableValue(RelationshipResultEvidence.serializer(), value)
        }

        override fun deserialize(decoder: Decoder): Limited =
            when (val evidence = decoder.decodeSerializableValue(RelationshipResultEvidence.serializer())) {
                is Complete,
                is Resumable,
                -> throw SerializationException("Limited relationship evidence requires the LIMITED variant")
                is Limited -> evidence
            }
    }
}

private object ExactRelationshipCardinalitySerializer : KSerializer<ResultCardinality.Exact> {
    override val descriptor = ResultCardinality.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ResultCardinality.Exact) {
        encoder.encodeSerializableValue(ResultCardinality.serializer(), value)
    }

    override fun deserialize(decoder: Decoder): ResultCardinality.Exact =
        when (val cardinality = decoder.decodeSerializableValue(ResultCardinality.serializer())) {
            is ResultCardinality.Exact -> cardinality
            is ResultCardinality.KnownMinimum -> throw SerializationException(
                "Complete relationship evidence requires exact cardinality",
            )
        }
}

private object KnownMinimumRelationshipCardinalitySerializer : KSerializer<ResultCardinality.KnownMinimum> {
    override val descriptor = ResultCardinality.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ResultCardinality.KnownMinimum) {
        encoder.encodeSerializableValue(ResultCardinality.serializer(), value)
    }

    override fun deserialize(decoder: Decoder): ResultCardinality.KnownMinimum =
        when (val cardinality = decoder.decodeSerializableValue(ResultCardinality.serializer())) {
            is ResultCardinality.Exact -> throw SerializationException(
                "Incomplete relationship evidence cannot claim exact cardinality",
            )
            is ResultCardinality.KnownMinimum -> cardinality
        }
}

private object CompleteRelationshipCoverageSerializer : KSerializer<RelationshipSearchCoverage.Complete> {
    override val descriptor = RelationshipSearchCoverage.serializer().descriptor

    override fun serialize(encoder: Encoder, value: RelationshipSearchCoverage.Complete) {
        encoder.encodeSerializableValue(RelationshipSearchCoverage.serializer(), value)
    }

    override fun deserialize(decoder: Decoder): RelationshipSearchCoverage.Complete =
        when (val coverage = decoder.decodeSerializableValue(RelationshipSearchCoverage.serializer())) {
            is RelationshipSearchCoverage.Complete -> coverage
            is RelationshipSearchCoverage.Resumable,
            is RelationshipSearchCoverage.Limited,
            -> throw SerializationException("Complete relationship evidence requires complete coverage")
        }
}

private object ResumableRelationshipCoverageSerializer : KSerializer<RelationshipSearchCoverage.Resumable> {
    override val descriptor = RelationshipSearchCoverage.serializer().descriptor

    override fun serialize(encoder: Encoder, value: RelationshipSearchCoverage.Resumable) {
        encoder.encodeSerializableValue(RelationshipSearchCoverage.serializer(), value)
    }

    override fun deserialize(decoder: Decoder): RelationshipSearchCoverage.Resumable =
        when (val coverage = decoder.decodeSerializableValue(RelationshipSearchCoverage.serializer())) {
            is RelationshipSearchCoverage.Complete,
            is RelationshipSearchCoverage.Limited,
            -> throw SerializationException("Resumable relationship evidence requires resumable coverage")
            is RelationshipSearchCoverage.Resumable -> coverage
        }
}

private object LimitedRelationshipCoverageSerializer : KSerializer<RelationshipSearchCoverage.Limited> {
    override val descriptor = RelationshipSearchCoverage.serializer().descriptor

    override fun serialize(encoder: Encoder, value: RelationshipSearchCoverage.Limited) {
        encoder.encodeSerializableValue(RelationshipSearchCoverage.serializer(), value)
    }

    override fun deserialize(decoder: Decoder): RelationshipSearchCoverage.Limited =
        when (val coverage = decoder.decodeSerializableValue(RelationshipSearchCoverage.serializer())) {
            is RelationshipSearchCoverage.Complete,
            is RelationshipSearchCoverage.Resumable,
            -> throw SerializationException("Limited relationship evidence requires limited coverage")
            is RelationshipSearchCoverage.Limited -> coverage
        }
}
