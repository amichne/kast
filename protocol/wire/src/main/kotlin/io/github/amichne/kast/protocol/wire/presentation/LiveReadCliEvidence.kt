@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.LiveReadEvidence
import kotlinx.serialization.Serializable

/** Explicit live evidence survives the final CLI boundary; it never becomes a generation. */
@Serializable
data class LiveReadCliEvidence
private constructor(
    @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(
        pattern = "^/[^\\x00-\\x1F\\x7F]*$",
        maximumLength = 4096,
    )
    val root: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(
        pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    val host: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 1)
    val epoch: Long? = null,
    @io.github.amichne.kast.protocol.contract.ProtocolStringConstraint(pattern = "^SAVED_PSI_COMMITTED$")
    val contentView: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint(minimum = 1, maximum = 1)
    val version: Int? = null,
) {
    /** Compact output retains the authority needed to compare opaque references and retained results. */
    fun compact(): LiveReadCliEvidence = this

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
