package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

const val INSTALLATION_SHUTDOWN_FENCE = "shutdown.json"
const val INSTALLATION_SHUTDOWN_CAPABILITY = "share/kast/lifecycle-fence-v1"

/** A shutdown request fences admission; it does not assert that external owners have retired. */
@Serializable data class InstallationShutdownRequest(val installationRoot: String, @Required val schemaVersion: Int = 1)
