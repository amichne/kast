package io.amichne.kontracts.schema

/**
 * Base interface for OpenAPI schema representation.
 *
 */
internal sealed interface OpenApi<out T : Any> {
    val type: Type
    val title: String?
    val description: String?
    val default: T?
    val nullable: Boolean
    val example: T?
    val deprecated: Boolean
}
