package io.github.amichne.kast.api.client.fields

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = RelationshipIndexingParallelismSerializer::class)
data class RelationshipIndexingParallelism(
    override val value: Int,
) : ConfigurationField<Int>() {
    init {
        require(value > 0) { "indexing.relationships.parallelism must be greater than zero" }
    }

    override val section: String get() = "indexing.relationships"
    override val key: String get() = "parallelism"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(4)
}

object RelationshipIndexingParallelismSerializer : KSerializer<RelationshipIndexingParallelism> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "io.github.amichne.kast.api.client.fields.RelationshipIndexingParallelism",
        kind = PrimitiveKind.INT,
    )

    override fun deserialize(decoder: Decoder): RelationshipIndexingParallelism {
        val value = decoder.decodeInt()
        return try {
            RelationshipIndexingParallelism(value)
        } catch (error: IllegalArgumentException) {
            throw SerializationException(error.message ?: "Invalid relationship indexing parallelism", error)
        }
    }

    override fun serialize(encoder: Encoder, value: RelationshipIndexingParallelism) {
        encoder.encodeInt(value.value)
    }
}
