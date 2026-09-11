package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.LiveReadEvidence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Explicit live evidence survives the final CLI boundary; it never becomes a generation. */
@Serializable
internal data class LiveReadCliEvidence
private constructor(
    val root: String,
    val host: String,
    val epoch: Long,
    val contentView: String,
    val version: Int,
) {
    companion object {
        fun from(value: LiveReadEvidence) =
            LiveReadCliEvidence(
                value.workspaceRoot,
                value.host.toString(),
                value.epoch,
                value.contentView.name,
                value.version,
            )
    }
}

internal fun CliJsonDocument.withEvidence(basis: EvidenceBasis): CliJsonDocument =
    when (basis) {
        is EvidenceBasis.Published -> this
        is EvidenceBasis.Live -> {
            val projected = Json.parseToJsonElement(value).jsonObject
            CliJsonDocument.generated(JsonObject.serializer())
                .create(
                    JsonObject(
                        projected +
                            ("live" to
                                Json.encodeToJsonElement(
                                    LiveReadCliEvidence.serializer(),
                                    LiveReadCliEvidence.from(basis.evidence),
                                ))
                    )
                )
        }
    }
