package io.amichne.konditional.rules.versions

import io.amichne.konditional.context.Version

data class FullyBound(
    override val min: Version,
    override val max: Version,
) : VersionRange(Type.MIN_AND_MAX_BOUND, min, max) {
    init {
        require(min <= max) {
            "Invalid range: minimum ($min) must be <= maximum ($max)"
        }
    }
}
