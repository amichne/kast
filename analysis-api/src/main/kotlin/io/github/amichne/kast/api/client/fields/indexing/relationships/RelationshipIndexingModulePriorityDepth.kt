package io.github.amichne.kast.api.client.fields

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = RelationshipIndexingModulePriorityDepthSerializer::class)
data class RelationshipIndexingModulePriorityDepth(
    override val value: Int,
) : ConfigurationField<Int>() {
    init {
        require(value >= 0) { "indexing.relationships.modulePriorityDepth must not be negative" }
    }

    override val section: String get() = "indexing.relationships"
    override val key: String get() = "modulePriorityDepth"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(2)
}

object RelationshipIndexingModulePriorityDepthSerializer : KSerializer<RelationshipIndexingModulePriorityDepth> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "io.github.amichne.kast.api.client.fields.RelationshipIndexingModulePriorityDepth",
        kind = PrimitiveKind.INT,
    )

    override fun deserialize(decoder: Decoder): RelationshipIndexingModulePriorityDepth {
        val value = decoder.decodeInt()
        return try {
            RelationshipIndexingModulePriorityDepth(value)
        } catch (error: IllegalArgumentException) {
            throw SerializationException(error.message ?: "Invalid relationship indexing module priority depth", error)
        }
    }

    override fun serialize(encoder: Encoder, value: RelationshipIndexingModulePriorityDepth) {
        encoder.encodeInt(value.value)
    }
}
