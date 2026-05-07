package io.amichne.kontracts.schema

/**
 * Result of schema validation.
 */
sealed class ValidationResult {
    object Valid : ValidationResult()

    @ConsistentCopyVisibility
    data class Invalid internal constructor(val message: String) : ValidationResult()

    val isValid: Boolean get() = this is Valid
    val isInvalid: Boolean get() = this is Invalid

    fun getErrorMessage(): String? = when (this) {
        is Invalid -> message
        is Valid -> null
    }

    companion object
}
