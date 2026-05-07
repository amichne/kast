package io.amichne.konditional.context

/**
 * Typed key that identifies a named field within a [Context].
 *
 * Using [ContextKey] instead of a raw [String] prevents silent mislookups caused by
 * key typos or identifier konditional — issues that raw strings hide until runtime.
 *
 * ## Usage
 *
 * ```kotlin
 * val LOCALE_KEY = ContextKey("locale")
 * val PLATFORM_KEY = ContextKey("platform")
 * ```
 *
 * @property id The stable, non-blank string identifier for this context field.
 */
@JvmInline
value class ContextKey(val id: String) {
    init {
        require(id.isNotBlank()) { "ContextKey.id must not be blank" }
    }

    override fun toString(): String = "ContextKey($id)"
}
