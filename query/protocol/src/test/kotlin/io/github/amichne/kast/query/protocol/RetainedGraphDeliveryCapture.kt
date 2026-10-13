package io.github.amichne.kast.query.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The CLI decoder owns each admitted inner document. The shared delivery client treats it as opaque. */
@Serializable
private data class CapturedGraphDeliveryReply(
    val type: CapturedGraphDeliveryReplyKind = CapturedGraphDeliveryReplyKind.COMPLETE,
    val document: JsonObject,
)

@Serializable
private enum class CapturedGraphDeliveryReplyKind {
    @SerialName("complete") COMPLETE
}

@Serializable
private data class CapturedGraphDeliveryPages(
    val verbose: List<CapturedGraphDeliveryReply>,
    val compact: List<CapturedGraphDeliveryReply>,
)

internal fun captureGraphDeliveryPages(
    verbose: List<JsonObject>,
    compact: List<JsonObject>,
    fileName: String = "canonical-partitions.json",
) {
    val directory = Path.of("build/reports/retained-graph-delivery")
    Files.createDirectories(directory)
    val path = directory.resolve(fileName)
    Files.writeString(
        path,
        Json { encodeDefaults = true }
            .encodeToString(
                CapturedGraphDeliveryPages.serializer(),
                CapturedGraphDeliveryPages(
                    verbose.map { CapturedGraphDeliveryReply(document = it) },
                    compact.map { CapturedGraphDeliveryReply(document = it) },
                ),
            ),
    )
    println("D3 canonical partition evidence=${path.toAbsolutePath()}")
}

internal fun encodedGraphDeliveryRepliesBytes(documents: List<JsonObject>): Long = Json {
    encodeDefaults = true
}
    .encodeToString(
        ListSerializer(CapturedGraphDeliveryReply.serializer()),
        documents.map { CapturedGraphDeliveryReply(document = it) },
    )
    .toByteArray(Charsets.UTF_8)
    .size
    .toLong()
