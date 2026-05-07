package io.amichne.konditional.rules.versions

import io.amichne.konditional.context.Version

data class RightBound(
    override val max: Version,
) : VersionRange(Type.MAX_BOUND, MIN_VERSION, max)
