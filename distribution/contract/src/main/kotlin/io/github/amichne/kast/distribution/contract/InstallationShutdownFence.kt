package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

const val INSTALLATION_SHUTDOWN_FENCE = "shutdown.json"
val INSTALLATION_SHUTDOWN_CAPABILITY: String = InstallationLayout.Relative.SHUTDOWN_CAPABILITY.value

/** A shutdown request fences admission; it does not assert that external owners have retired. */
@Serializable data class InstallationShutdownRequest(val installationRoot: String, @Required val schemaVersion: Int = 1)
