package io.github.amichne.kast.distribution.contract

import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val INSTALLATION_RESET_CAPABILITY: String = InstallationLayout.Relative.RESET_CAPABILITY.value
private const val RESET_ROOT_DIGEST_LENGTH = 32

/** Stable outside the replaceable directory; callers pass the canonical management root. */
fun installationResetFence(root: Path): Path {
    val digest =
        MessageDigest.getInstance("SHA-256").digest(root.toString().toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    return root.parent.resolve(".kast-reset-${digest.take(RESET_ROOT_DIGEST_LENGTH)}.json")
}

@Serializable
enum class InstallationResetType {
    RESET_IN_PROGRESS
}

@Serializable
data class InstallationResetRequest(
    val installationRoot: String,
    val storage: InstallationResetStorage,
    @Required val type: InstallationResetType = InstallationResetType.RESET_IN_PROGRESS,
    @Required val schemaVersion: Int = 1,
)

/** Retention intent is recorded before moving bytes, so an interrupted reset remains recoverable. */
@Serializable
sealed interface InstallationResetStorage {
    @Serializable @SerialName("FENCED") data object Fenced : InstallationResetStorage

    @Serializable @SerialName("RETAINED") data class Retained(val directory: String) : InstallationResetStorage

    @Serializable @SerialName("ACTIVATING") data object Activating : InstallationResetStorage

    @Serializable @SerialName("ERASED") data object Erased : InstallationResetStorage
}
