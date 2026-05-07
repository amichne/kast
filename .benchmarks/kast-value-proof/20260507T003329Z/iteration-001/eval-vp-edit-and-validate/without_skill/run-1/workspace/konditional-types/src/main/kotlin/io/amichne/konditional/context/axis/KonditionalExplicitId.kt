package io.amichne.konditional.context.axis

/**
 * Overrides the derived id used for annotated Kotlin declarations.
 *
 * Apply this to:
 * - an [AxisValue] enum when the FQCN is not a suitable stable identifier, or
 * - a delegated reusable namespace rule set property when you need to pin a stable seed that is
 *   independent from the property name.
 *
 * ```kotlin
 * @KonditionalExplicitId("environment")
 * enum class Environment : AxisValue<Environment> { PROD, STAGE, DEV }
 * ```
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class KonditionalExplicitId(val id: String)
