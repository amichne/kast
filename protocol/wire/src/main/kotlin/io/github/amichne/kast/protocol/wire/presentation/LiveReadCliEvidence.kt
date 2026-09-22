package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.LiveReadEvidence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Explicit live evidence survives the final CLI boundary; it never becomes a generation. */
@Serializable
data class LiveReadCliEvidence
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

fun EvidenceBasis.liveDocument(): LiveReadCliEvidence? =
    when (this) {
        is EvidenceBasis.Published -> null
        is EvidenceBasis.Live -> LiveReadCliEvidence.from(evidence)
    }
