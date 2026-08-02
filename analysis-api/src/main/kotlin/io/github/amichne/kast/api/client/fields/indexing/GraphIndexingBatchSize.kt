package io.github.amichne.kast.api.client.fields

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = GraphIndexingBatchSizeSerializer::class)
data class GraphIndexingBatchSize(
    override val value: Int,
) : ConfigurationField<Int>() {
    init {
        require(value > 0) { "indexing.graph.batchSize must be greater than zero" }
    }

    override val section: String get() = "indexing.graph"
    override val key: String get() = "batchSize"
    override val default: ConfigurationDefault<Int> get() = ConfigurationDefault(32)
}

object GraphIndexingBatchSizeSerializer : KSerializer<GraphIndexingBatchSize> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "io.github.amichne.kast.api.client.fields.GraphIndexingBatchSize",
        kind = PrimitiveKind.INT,
    )

    override fun deserialize(decoder: Decoder): GraphIndexingBatchSize {
        val value = decoder.decodeInt()
        return try {
            GraphIndexingBatchSize(value)
        } catch (error: IllegalArgumentException) {
            throw SerializationException(error.message ?: "Invalid graph indexing batch size", error)
        }
    }

    override fun serialize(encoder: Encoder, value: GraphIndexingBatchSize) {
        encoder.encodeInt(value.value)
    }
}
