@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.LiveReadEvidence
import kotlinx.serialization.Serializable

/** Explicit live evidence survives the final CLI boundary; it never becomes a generation. */
@Serializable
data class LiveReadCliEvidence
private constructor(
    val root: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val host: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val epoch: Long? = null,
    val contentView: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val version: Int? = null,
) {
    fun compact(): LiveReadCliEvidence = copy(host = null, epoch = null, version = null)

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
