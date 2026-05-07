package io.amichne.kontracts.schema

/**
 * Interface for types that provide their own schema definition.
 *
 * Implement this interface to associate a schema with a data class,
 * enabling type-safe validation and serialization.
 *
 * Example:
 * ```kotlin
 * data class UserSettings(
 *     val theme: String = "light",
 *     val notificationsEnabled: Boolean = true,
 *     val maxRetries: Int = 3
 * ) : SchemaProvider<ObjectSchema> {
 *     override val schema = schema {
 *         ::theme of { minLength = 1 }
 *         ::notificationsEnabled of { default = true }
 *         ::maxRetries of { minimum = 0 }
 *     }
 * }
 * ```
 *
 * @param S The schema type (typically ObjectSchema or RootObjectSchema)
 */
interface SchemaProvider<out S : JsonSchema<*>> {
    /**
     * The schema defining the structure and validation rules.
     */
    val schema: S
}
