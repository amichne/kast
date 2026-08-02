package io.github.amichne.kast.api.client.fields

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = RelationshipIndexingBatchSizeSerializer::class)
data class RelationshipIndexingBatchSize(
    override val value: Int,
) : ConfigurationField<Int>() {
    init {
        require(value > 0) { "indexing.relationships.batchSize must be greater than zero" }
    }

    override val section: String get() = "indexing.relationships"
    override val key: String get() = "batchSize"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(50)
}

object RelationshipIndexingBatchSizeSerializer : KSerializer<RelationshipIndexingBatchSize> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "io.github.amichne.kast.api.client.fields.RelationshipIndexingBatchSize",
        kind = PrimitiveKind.INT,
    )

    override fun deserialize(decoder: Decoder): RelationshipIndexingBatchSize {
        val value = decoder.decodeInt()
        return try {
            RelationshipIndexingBatchSize(value)
        } catch (error: IllegalArgumentException) {
            throw SerializationException(error.message ?: "Invalid relationship indexing batch size", error)
        }
    }

    override fun serialize(encoder: Encoder, value: RelationshipIndexingBatchSize) {
        encoder.encodeInt(value.value)
    }
}
