package io.amichne.konditional.core.features

import io.amichne.konditional.values.FeatureId
import io.amichne.konditional.values.IdentifierEncoding.SEPARATOR
import io.amichne.konditional.values.NamespaceId
import io.amichne.konditional.values.Validateable

/**
 * Root identity interface for all typed identities in this codebase.
 *
 * Two sub-contracts exist under this interface:
 *
 * - [Named]: for string-valued identifier value classes ([io.amichne.konditional.values.NamespaceId]
 *   and future typed identifier types).
 *   Implementations automatically produce their [Named.value] as their string representation —
 *   no `.value` unwrapping needed in string contexts for non-value-class types.
 *   Value classes must explicitly declare `override fun toString(): String = value`.
 *
 * - [ById]: for objects that carry a stable [FeatureId] reference ([Feature] and its concrete subtypes).
 *   Construction is handled by the [ById.Companion.invoke] factory, which derives a stable [FeatureId]
 *   from the owning namespace and the property key.
 */
interface Identifiable {

    /**
     * String-valued typed identifier base type.
     *
     * All [Named] implementations should produce [value] as their [toString] result so they can be
     * safely interpolated in strings and log messages without explicit `.value` unwrapping.
     *
     * **Implementors must explicitly declare `override fun toString(): String = value`.**
     * Kotlin prohibits interfaces from providing default implementations of [Any] methods
     * (`toString`, `equals`, `hashCode`), so each concrete type is responsible for this override.
     * For `@JvmInline value class` types this is especially important: without an explicit
     * override the compiler generates `ClassName(value)` instead of the bare value string.
     */
    interface Named : Identifiable, Validateable {
        val value: String

        /**
         * Requires [value] to be non-blank.
         */
        interface NonBlank : Named {
            override fun validate() = apply {
                require(value.isNotBlank()) { "${this::class.simpleName} must not be blank" }
            }
        }

        /**
         * Requires [value] to be non-blank and free of the [SEPARATOR] sequence used
         * in composite identifiers.
         */
        interface Composable : NonBlank {
            override fun validate() = apply {
                super<NonBlank>.validate()
                require(!value.contains(SEPARATOR)) {
                    "${this::class.simpleName} must not contain '$SEPARATOR': '$value'"
                }
            }
        }
    }

    /**
     * Marker for types that carry a stable [FeatureId] reference.
     *
     * Use the [Companion.invoke] factory to construct delegation targets:
     * ```kotlin
     * data class FooFeatureImpl(...) : FooFeature<C, M>, Identifiable.ById by Identifiable.ById(key, namespace.id)
     * ```
     */
    interface ById : Identifiable {
        val id: FeatureId

        companion object {
            operator fun invoke(
                key: String,
                namespaceId: NamespaceId,
            ): ById =
                object : ById {
                    override val id: FeatureId = FeatureId.create(namespaceId, key)
                }
        }
    }
}
