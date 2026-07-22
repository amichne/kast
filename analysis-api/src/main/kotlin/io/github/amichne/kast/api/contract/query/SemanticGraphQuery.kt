package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

@Serializable(with = SemanticGraphPathSerializer::class)
@JvmInline
value class SemanticGraphPath private constructor(
    @DocField(description = "Normalized absolute Kotlin source or script path.")
    val value: NormalizedPath,
) : Comparable<SemanticGraphPath> {
    companion object {
        fun parse(raw: String): SemanticGraphPath {
            val path = NormalizedPath.parse(raw)
            require(path.value.endsWith(".kt") || path.value.endsWith(".kts")) {
                "Semantic graph paths must end in .kt or .kts"
            }
            return SemanticGraphPath(path)
        }
    }

    override fun compareTo(other: SemanticGraphPath): Int = value.compareTo(other.value)
}

@Serializable
@JvmInline
value class SemanticGraphPageToken private constructor(
    @DocField(description = "Opaque single-use semantic graph continuation token.")
    val value: String,
) {
    init {
        val parsed = UUID.fromString(value)
        require(parsed.toString() == value) { "Semantic graph page token must be a canonical UUID" }
    }

    companion object {
        fun parse(raw: String): SemanticGraphPageToken = SemanticGraphPageToken(raw)

        fun random(): SemanticGraphPageToken = SemanticGraphPageToken(UUID.randomUUID().toString())
    }
}

@Serializable
data class SemanticGraphQuery(
    @DocField(description = "Sorted absolute Kotlin files to refresh or read.")
    val filePaths: List<SemanticGraphPath>,
    @DocField(description = "Sorted absolute Kotlin paths removed from the workspace.")
    val removedFilePaths: List<SemanticGraphPath> = emptyList(),
    @DocField(description = "Maximum combined symbol and relation records in one page.")
    val pageSize: PositiveInt = PositiveInt(500),
    @DocField(description = "Opaque continuation returned by the preceding page.")
    val continuation: SemanticGraphPageToken? = null,
) {
    init {
        require(filePaths.isNotEmpty() || removedFilePaths.isNotEmpty()) {
            "Semantic graph scope must contain a selected or removed Kotlin file"
        }
    }
}

object SemanticGraphPathSerializer : KSerializer<SemanticGraphPath> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SemanticGraphPath", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SemanticGraphPath) {
        encoder.encodeString(value.value.value)
    }

    override fun deserialize(decoder: Decoder): SemanticGraphPath =
        SemanticGraphPath.parse(decoder.decodeString())
}
