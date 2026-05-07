package io.amichne.konditional.context.axis

/**
 * Typed key that uniquely identifies an [Axis].
 *
 * Wraps the stable axis ID (derived from the enum's FQCN or a
 * [@KonditionalExplicitId][KonditionalExplicitId] annotation) in a value class,
 * providing compile-time distinction between axis identifiers and arbitrary strings.
 *
 * Obtain an [AxisKey] from an [Axis] instance via [Axis.key]:
 * ```kotlin
 * val key: AxisKey = Axis.of<Environment>().key
 * ```
 *
 * @property id The stable, non-blank string identifier for this axis.
 */
@JvmInline
value class AxisKey(val id: String) {
    init {
        require(id.isNotBlank()) { "AxisKey.id must not be blank" }
    }

    override fun toString(): String = "AxisKey($id)"
}
