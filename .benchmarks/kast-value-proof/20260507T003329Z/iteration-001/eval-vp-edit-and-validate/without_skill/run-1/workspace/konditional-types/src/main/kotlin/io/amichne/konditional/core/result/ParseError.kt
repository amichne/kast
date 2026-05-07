package io.amichne.konditional.core.result

import io.amichne.konditional.values.FeatureId

/**
 * Domain-specific parseUnsafe errors that make failure reasons explicit and type-safe.
 *
 * Each error type contains structured information about what went wrong,
 * allowing consumers to handle errors precisely.
 */
sealed interface ParseError {
    /**
     * Human-readable error message.
     */
    val message: String

    /**
     * Failed to parseUnsafe a hexadecimal identifier.
     */
    @ConsistentCopyVisibility
    data class InvalidHexId internal constructor(
        val input: String,
        override val message: String,
    ) : ParseError

    /**
     * Invalid rampUp percentage (must be 0.0-100.0).
     */
    @ConsistentCopyVisibility
    data class InvalidRollout internal constructor(
        val value: Double,
        override val message: String,
    ) : ParseError

    /**
     * Failed to parseUnsafe a semantic version string.
     */
    @ConsistentCopyVisibility
    data class InvalidVersion internal constructor(
        val input: String,
        override val message: String,
    ) : ParseError

    /**
     * Feature key not found in registry.
     */
    @ConsistentCopyVisibility
    data class FeatureNotFound internal constructor(val key: FeatureId) : ParseError {
        override val message: String get() = "Feature not found: $key"
    }

    /**
     * An unexpected field was encountered at [path] during parsing.
     *
     * Emitted when the input contains a field that the parser does not recognise
     * at the given JSON path (e.g. a typo or a field from a future schema version).
     */
    data class UnknownField(
        val path: String,
        override val message: String = "Unknown field at '$path'",
    ) : ParseError

    /**
     * A required field is absent at [path] during parsing.
     *
     * Emitted when the input is missing a field that the schema mandates.
     */
    data class MissingRequired(
        val path: String,
        override val message: String = "Missing required field at '$path'",
    ) : ParseError

    /**
     * A field at [path] holds a value that violates a schema constraint.
     *
     * [reason] describes why the value is invalid (e.g. "must be in range 0.0–100.0").
     */
    data class InvalidValue(
        val path: String,
        val reason: String,
        override val message: String = "Invalid value at '$path': $reason",
    ) : ParseError

    companion object {
        fun featureNotFound(key: FeatureId): ParseError = FeatureNotFound(key)

        fun invalidJson(reason: String): ParseError = InvalidJson(reason)

        fun invalidSnapshot(reason: String): ParseError = InvalidSnapshot(reason)

        fun unknownField(path: String): ParseError = UnknownField(path)

        fun missingRequired(path: String): ParseError = MissingRequired(path)

        fun invalidValue(path: String, reason: String): ParseError = InvalidValue(path = path, reason = reason)
    }

    /**
     * Feature flag not found in registry.
     */
    @ConsistentCopyVisibility
    data class FlagNotFound internal constructor(val key: FeatureId) : ParseError {
        override val message: String get() = "Flag not found: $key"
    }

    /**
     * Failed to deserialize JSON into a snapshot.
     */
    data class InvalidSnapshot(val reason: String) : ParseError {
        override val message: String get() = "Invalid snapshot: $reason"
    }

    /**
     * Invalid JSON data that cannot be parsed.
     */
    @ConsistentCopyVisibility
    data class InvalidJson internal constructor(val reason: String) : ParseError {
        override val message: String get() = "Invalid JSON: $reason"
    }
}
