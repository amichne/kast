package io.amichne.konditional.core.dsl

/**
 * DSL scope for version range configuration.
 *
 * This interface defines the public API for configuring version ranges in rules.
 * Users cannot instantiate implementations of this interface directly - it is only
 * available as a receiver in DSL blocks through internal implementations.
 *
 * Example usage:
 * ```kotlin
 * versions {
 *     min(1, 2, 0)  // Minimum version 1.2.0 (inclusive)
 *     max(2, 0, 0)  // Maximum version 2.0.0 (exclusive)
 * }
 * ```
 *
 * @since 0.0.2
 */
@KonditionalDsl
interface VersionRangeScope {
    /**
     * Sets the minimum version (inclusive) for this range.
     *
     * Example:
     * ```kotlin
     * min(1, 2, 0)  // Targets version 1.2.0 and above
     * ```
     *
     * @param major The major version number
     * @param minor The minor version number (default 0)
     * @param patch The patch version number (default 0)
     */
    fun min(
        major: Int,
        minor: Int = 0,
        patch: Int = 0,
    )

    /**
     * Sets the maximum version (exclusive) for this range.
     *
     * Example:
     * ```kotlin
     * max(2, 0, 0)  // Targets versions below 2.0.0
     * ```
     *
     * @param major The major version number
     * @param minor The minor version number (default 0)
     * @param patch The patch version number (default 0)
     */
    fun max(
        major: Int,
        minor: Int = 0,
        patch: Int = 0,
    )
}
