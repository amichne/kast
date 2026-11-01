package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The existing replacement journal binds staged Control bytes before retirement. */
@Serializable
data class ManagedControlReservation(
    val transaction: String,
    val staged: String,
    val controlSha256: String,
    val version: String,
    val manifestSha256: String,
) {
    init {
        require(Regex("[0-9a-f-]{36}").matches(transaction))
        require(listOf(controlSha256, manifestSha256).all { Regex("[0-9a-f]{64}").matches(it) })
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version))
    }
}

@Serializable data class ManagedHostIdentity(val device: Long, val inode: Long, val owner: Long)

@Serializable
sealed interface ManagedHostPrevious {
    @Serializable @SerialName("ABSENT") data object Absent : ManagedHostPrevious

    @Serializable
    @SerialName("PRESENT")
    data class Present(
        val identity: ManagedHostIdentity,
        val inventorySha256: String,
        val receipt: String,
        val receiptIdentity: ManagedHostIdentity,
        val receiptSha256: String,
    ) : ManagedHostPrevious
}

@Serializable
sealed interface ManagedHostLock {
    @Serializable @SerialName("EXISTING") data class Existing(val identity: ManagedHostIdentity) : ManagedHostLock

    @Serializable
    @SerialName("STAGED")
    data class Staged(
        val identity: ManagedHostIdentity,
        val staged: String,
        val retainedIdentity: ManagedHostIdentity?,
    ) : ManagedHostLock
}

/** Host inventory and preimage paths stay owned by host-installation.py. */
@Serializable
data class ManagedHostReplacementRecord(
    val pluginRoot: String,
    val pluginRootIdentity: ManagedHostIdentity?,
    val retainedRootIdentity: ManagedHostIdentity?,
    val destination: String,
    val candidate: String,
    val candidateIdentity: ManagedHostIdentity,
    val candidateInventorySha256: String,
    val backup: String,
    val previous: ManagedHostPrevious,
    val version: String,
    val sha256: String,
    val lock: ManagedHostLock? = null,
)

@Serializable
enum class ManagedCompositionPhase {
    PREPARED,
    FENCED,
    SETTLED,
    CONTROL_COMMITTED,
    HOST_COMMITTED,
    ACTIVATION_PROVEN,
    RESTORING,
    RESTORED,
}

@Serializable
enum class ManagedHostStrategy {
    STOPPED,
    HOT_RELOAD,
    NORMAL_RESTART,
}

@Serializable
sealed interface ManagedFilePreimage {
    @Serializable @SerialName("ABSENT") data class Absent(val destination: String) : ManagedFilePreimage

    @Serializable
    @SerialName("PRESENT")
    data class Present(
        val destination: String,
        val preimage: String,
        val sha256: String,
        val mode: Int,
    ) : ManagedFilePreimage
}

@Serializable
sealed interface ManagedProfileDirectory {
    @Serializable @SerialName("MISSING") data class Missing(val path: String) : ManagedProfileDirectory

    @Serializable
    @SerialName("CREATED")
    data class Created(val path: String, val identity: ManagedHostIdentity) : ManagedProfileDirectory
}

/** Exact artifact and preimage identities survive an interrupted command. */
@Serializable
data class ManagedCompositionRecord(
    val phase: ManagedCompositionPhase,
    val strategy: ManagedHostStrategy,
    val ideaHome: String,
    val profile: String,
    val selection: ManagedFilePreimage,
    val management: ManagedFilePreimage,
    val publicExecutable: ManagedFilePreimage,
    val profileOptions: ManagedFilePreimage,
    val host: ManagedHostReplacementRecord,
    val priorRuntime: ManagedFilePreimage,
    val profileDirectories: List<ManagedProfileDirectory> = emptyList(),
    val binding: ManagementBuildBinding,
    val notices: List<ManagedCompositionNotice> = emptyList(),
    /** Opaque native inspection frame. Its digest must match the captured runtime preimage. */
    val priorRuntimeJson: String? = null,
) {
    init {
        require(selection is ManagedFilePreimage.Present)
    }
}

@Serializable
enum class ManagedCompositionNotice {
    HARNESS_REFRESH_REQUIRED
}
