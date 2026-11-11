package io.github.amichne.kast.distribution.managed

import kotlinx.serialization.Serializable

/** Explicit creation-time choice; absence in older installations retains the private default. */
@Serializable data class ManagedHeadlessGradleHome(val path: String)
