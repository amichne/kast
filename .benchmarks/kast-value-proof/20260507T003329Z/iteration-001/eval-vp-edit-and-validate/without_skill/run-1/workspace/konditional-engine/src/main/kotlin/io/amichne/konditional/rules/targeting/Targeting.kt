package io.amichne.konditional.rules.targeting

import io.amichne.konditional.context.Context
import io.amichne.konditional.context.Context.Companion.getAxisValue
import io.amichne.konditional.rules.versions.Unbounded
import io.amichne.konditional.rules.versions.VersionRange

/**
 * Algebraic description of conditions under which a rule fires.
 *
 * Each leaf encodes exactly the context capability it requires via its type
 * parameter. A [Guarded] wrapper lifts any precisely-typed leaf into a common
 * `Targeting<C>` so heterogeneous constraints can be composed in [All].
 *
 * ## Invariants
 * - [matches] is pure and deterministic: same context produces the same result.
 * - [specificity] is structurally derived and stable across calls.
 * - [All] with an empty list matches everything (identity / catch-all).
 * - Non-matching semantics for capability absence: [Guarded.matches] returns
 *   `false`, never throws, when the context does not carry the required capability.
 *
 * ## Constraint to leaf mapping
 * | Targeting dimension | Leaf type          | Required context mixin      |
 * |---------------------|--------------------|-----------------------------|
 * | Locales             | [Locale]           | [Context.LocaleContext]     |
 * | Platforms           | [Platform]         | [Context.PlatformContext]   |
 * | App version range   | [Version]          | [Context.VersionContext]    |
 * | Axis value          | [Axis]             | [Context]                   |
 * | Custom / extension  | [Custom]           | `C` (generic)               |
 * | Capability-narrowed | [Guarded]          | `C` narrowed to `R`         |
 */
sealed interface Targeting<in C : Context> {

    /** Returns true iff [context] satisfies this targeting constraint. */
    fun matches(context: C): Boolean

    /**
     * Contribution to rule precedence ordering.
     *
     * Higher specificity means higher priority when multiple rules match.
     * [All] sums the specificity of all contained leaves.
     */
    fun specificity(): Int

    // -- Standard dimension leaves ------------------------------------------------

    /**
     * Matches when [Context.LocaleContext.locale].id is in [ids].
     *
     * Determinism: result depends only on the locale id set and the context's locale.
     */
    @JvmInline
    value class Locale(val ids: Set<String>) : Targeting<Context.LocaleContext> {
        override fun matches(context: Context.LocaleContext): Boolean = context.locale.id in ids
        override fun specificity(): Int = 1
    }

    /**
     * Matches when [Context.PlatformContext.platform].id is in [ids].
     *
     * Determinism: result depends only on the platform id set and the context's platform.
     */
    @JvmInline
    value class Platform(val ids: Set<String>) : Targeting<Context.PlatformContext> {
        override fun matches(context: Context.PlatformContext): Boolean = context.platform.id in ids
        override fun specificity(): Int = 1
    }

    /**
     * Matches when [Context.VersionContext.appVersion] falls within [range].
     *
     * An [Unbounded] range always matches and contributes zero specificity.
     */
    data class Version(val range: VersionRange) : Targeting<Context.VersionContext> {
        override fun matches(context: Context.VersionContext): Boolean = range.contains(context.appVersion)
        override fun specificity(): Int = if (range == Unbounded) 0 else 1
    }

    /**
     * Matches when the context exposes at least one axis value whose id is in [allowedIds]
     * for the axis identified by [axisId].
     *
     * Determinism: result depends only on the axisId, allowedIds set, and context axis values.
     */
    data class Axis(
        val axisId: String,
        val allowedIds: Set<String>,
    ) : Targeting<Context> {
        override fun matches(context: Context): Boolean =
            context.getAxisValue(axisId).any { it.id in allowedIds }

        override fun specificity(): Int = 1
    }

    /**
     * Custom predicate targeting.
     *
     * Evaluates [block] against the full context [C].
     * [weight] is the specificity contribution; default 1.
     * Prefer [Guarded] when the predicate only applies to a narrower context subtype.
     *
     * @param block Pure evaluation function. Must be deterministic for a given context.
     * @param weight Specificity contribution. Default 1.
     */
    data class Custom<C : Context>(
        val block: (C) -> Boolean,
        val weight: Int = 1,
    ) : Targeting<C> {
        override fun matches(context: C): Boolean = block(context)
        override fun specificity(): Int = weight
    }

    /**
     * Capability-narrowed predicate.
     *
     * Lifts a `Targeting<R>` (which requires context subtype [R]) into `Targeting<C>`.
     * When the runtime context does not implement [R], [matches] returns `false` without
     * throwing — non-matching semantics for absent capabilities.
     *
     * The [evidence] function captures the type narrowing; it is the **only** site in
     * the Targeting hierarchy where a runtime context cast is performed.
     *
     * @param inner The precisely-typed targeting leaf.
     * @param evidence Projects context [C] to [R], returning null if the capability is absent.
     */
    data class Guarded<C : Context, R : Context>(
        val inner: Targeting<R>,
        val evidence: (C) -> R?,
    ) : Targeting<C> {
        override fun matches(context: C): Boolean = evidence(context)?.let { inner.matches(it) } ?: false
        override fun specificity(): Int = inner.specificity()
    }

    /**
     * OR-disjunction of zero or more [Targeting] constraints.
     *
     * - Empty list never matches (annihilator element, dual of [All]'s identity).
     * - [specificity] is the structural maximum of all branch specificities; pure,
     *   no context required — consistent with the [specificity] contract.
     * - Code-only: not serializable. Treated as opaque by projection helpers,
     *   same as [Custom].
     *
     * @param targets Ordered list of constraints; any one must match.
     */
    data class AnyOf<C : Context>(
        val targets: List<Targeting<C>>,
    ) : Targeting<C> {
        override fun matches(context: C): Boolean = targets.any { it.matches(context) }
        override fun specificity(): Int = targets.maxOfOrNull { it.specificity() } ?: 0
    }

    // -- Combinator ---------------------------------------------------------------

    /**
     * AND-conjunction of zero or more [Targeting] constraints.
     *
     * - Empty list matches everything (identity element / catch-all).
     * - [specificity] is the sum of all leaf specificities.
     * - [plus] produces a new [All] without mutating either operand.
     *
     * @param targets Ordered list of constraints; all must match.
     */
    data class All<C : Context>(
        val targets: List<Targeting<C>>,
    ) : Targeting<C> {
        override fun matches(context: C): Boolean = targets.all { it.matches(context) }
        override fun specificity(): Int = targets.sumOf { it.specificity() }
        operator fun plus(other: All<C>): All<C> = All(targets + other.targets)
    }

    companion object {
        /** Returns an [All] that matches every context (identity element). */
        fun <C : Context> catchAll(): All<C> = All(emptyList())

        /**
         * Lifts a locale leaf into `Targeting<C>` via a [Guarded] wrapper.
         *
         * The `as?` cast is intentional and contained; it is the only dynamic
         * probe for [Context.LocaleContext] in the system.
         */
        fun <C : Context> locale(ids: Set<String>): Targeting<C> =
            Guarded(Locale(ids)) { c -> c as? Context.LocaleContext }

        /** Lifts a platform leaf into `Targeting<C>` via a [Guarded] wrapper. */
        fun <C : Context> platform(ids: Set<String>): Targeting<C> =
            Guarded(Platform(ids)) { c -> c as? Context.PlatformContext }

        /** Lifts a version leaf into `Targeting<C>` via a [Guarded] wrapper. */
        fun <C : Context> version(range: VersionRange): Targeting<C> =
            Guarded(Version(range)) { c -> c as? Context.VersionContext }

        /**
         * Lifts a capability-narrowed predicate into `Targeting<C>`.
         *
         * Intended for DSL use via `whenContext<R> { ... }`. The reified [R]
         * is captured in the [Guarded.evidence] lambda; no KClass is stored.
         *
         * @param weight Specificity contribution of the custom predicate.
         * @param block Evaluation function evaluated against the narrowed context [R].
         */
        inline fun <C : Context, reified R : C> whenContext(
            weight: Int = 1,
            crossinline block: R.() -> Boolean,
        ): Targeting<C> = Guarded<C, R>(
            inner = Custom(block = { r -> r.block() }, weight = weight),
            evidence = { c -> c as? R },
        )
    }
}
