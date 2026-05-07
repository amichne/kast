package io.amichne.konditional.rules.versions

import io.amichne.konditional.context.Version

data class LeftBound(
    override val min: Version,
) : VersionRange(Type.MIN_BOUND, min, MAX_VERSION)
