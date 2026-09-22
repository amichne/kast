package io.github.amichne.kast.appserver.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire projection only; a decoded status is not authority to reopen or activate a daemon. */
@Serializable
internal sealed interface DaemonUpgradeDocument {
    val requestId: String
    val candidate: String

    @Serializable
    @SerialName("pending")
    data class Pending(
        override val requestId: String,
        override val candidate: String,
        val blockers: List<UpgradeBlocker>,
    ) : DaemonUpgradeDocument

    @Serializable
    @SerialName("sealed")
    data class Sealed(override val requestId: String, override val candidate: String) : DaemonUpgradeDocument

    @Serializable
    @SerialName("committed")
    data class Committed(override val requestId: String, override val candidate: String) : DaemonUpgradeDocument

    @Serializable
    @SerialName("cancelled")
    data class Cancelled(override val requestId: String, override val candidate: String) : DaemonUpgradeDocument
}

internal fun UpgradeStatus.Requested.document(): DaemonUpgradeDocument =
    with(request) {
        val id = id.value.toString()
        when (this@document) {
            is UpgradeStatus.Pending -> DaemonUpgradeDocument.Pending(id, candidate.digest, blockers.toList())
            is UpgradeStatus.Sealed -> DaemonUpgradeDocument.Sealed(id, candidate.digest)
            is UpgradeStatus.Committed -> DaemonUpgradeDocument.Committed(id, candidate.digest)
            is UpgradeStatus.Cancelled -> DaemonUpgradeDocument.Cancelled(id, candidate.digest)
        }
    }
