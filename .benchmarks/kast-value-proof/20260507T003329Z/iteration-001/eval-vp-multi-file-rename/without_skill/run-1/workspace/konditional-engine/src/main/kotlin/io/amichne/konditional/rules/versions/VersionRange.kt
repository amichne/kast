package io.amichne.konditional.rules.versions

import io.amichne.konditional.context.Version
import kotlin.math.pow

sealed class VersionRange(
    val type: Type,
    open val min: Version? = null,
    open val max: Version? = null,
) {
    enum class Type {
        MIN_BOUND,
        MAX_BOUND,
        MIN_AND_MAX_BOUND,
        UNBOUNDED,
    }

    companion object {
        internal val MIN_VERSION = Version(0, 0, 0)
        internal val MAX_VERSION = Version(2.0.pow(32.0).toInt(), 2.0.pow(32.0).toInt(), 2.0.pow(32.0).toInt())
    }

    open fun contains(v: Version): Boolean = (min?.let { v >= it } ?: true) && (max?.let { v <= it } ?: true)

    /**
     * Returns true if this version range has any bounds (min or max).
     *
     * This method is open to allow subclasses (like Unbounded) to override
     * the default behavior based on their semantic meaning rather than
     * implementation details.
     */
    open fun hasBounds(): Boolean = (min != null) || (max != null)
}
