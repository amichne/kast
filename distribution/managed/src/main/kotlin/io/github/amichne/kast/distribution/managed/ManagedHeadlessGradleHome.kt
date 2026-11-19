package io.github.amichne.kast.distribution.managed

import kotlinx.serialization.Serializable

/** Explicit creation-time choice; absence in older installations retains the private default. */
@Serializable
data class ManagedHeadlessGradleHome(val path: String, val sharedDependencies: Boolean = false) {
    companion object {
        const val READ_ONLY_DIRECTORY = "kast-read-only"
    }
}
