package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import io.github.amichne.kast.api.contract.compatibility.ReleaseRevision

internal data class CliBuildIdentity(
    val version: CliImplementationVersion,
    val revision: ReleaseRevision,
)
