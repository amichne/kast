@file:OptIn(io.amichne.konditional.api.KonditionalInternalApi::class)

package io.amichne.konditional.internal.builders

import io.amichne.konditional.context.Context
import io.amichne.konditional.context.LocaleTag
import io.amichne.konditional.context.PlatformTag
import io.amichne.konditional.context.axis.Axis
import io.amichne.konditional.context.axis.AxisValue
import io.amichne.konditional.core.dsl.KonditionalDsl
import io.amichne.konditional.core.dsl.VariantDispatchHost
import io.amichne.konditional.core.dsl.VersionRangeScope
import io.amichne.konditional.core.dsl.rules.targeting.scopes.AnyOfScope
import io.amichne.konditional.core.dsl.rules.targeting.scopes.NarrowingTargetingScope
import io.amichne.konditional.internal.builders.versions.VersionRangeBuilder
import io.amichne.konditional.rules.targeting.Targeting

/**
 * Internal builder for [Targeting.AnyOf] nodes.
 *
 * Accumulates [Targeting] leaves via the [AnyOfScope] methods; [build] wraps
 * them in a [Targeting.AnyOf] OR-disjunction. An empty block produces an
 * empty [Targeting.AnyOf] which never matches — callers should guard against
 * this before appending to the enclosing [Targeting.All].
 */
@KonditionalDsl
@PublishedApi
@Suppress("OVERRIDE_DEPRECATION")
internal class AnyOfBuilder<C : Context> : AnyOfScope<C>, NarrowingTargetingScope<C>, VariantDispatchHost {

    private val leaves = mutableListOf<Targeting<C>>()
    private val variantScope = RuleVariantScope<C>(leaves)

    override fun locales(vararg appLocales: LocaleTag) {
        if (appLocales.isNotEmpty())
            leaves += Targeting.locale(appLocales.mapTo(linkedSetOf()) { it.id })
    }

    override fun platforms(vararg ps: PlatformTag) {
        if (ps.isNotEmpty())
            leaves += Targeting.platform(ps.mapTo(linkedSetOf()) { it.id })
    }

    override fun versions(build: VersionRangeScope.() -> Unit) {
        val range = VersionRangeBuilder().apply(build).build()
        leaves += Targeting.version(range)
    }

    override fun extension(block: C.() -> Boolean) {
        leaves += Targeting.Custom(block = { c -> c.block() })
    }

    override fun require(block: C.() -> Boolean) {
        extension(block)
    }

    override fun <R : Context> extensionNarrowed(
        evidence: (C) -> R?,
        block: R.() -> Boolean,
    ) {
        leaves += Targeting.Guarded(
            inner = Targeting.Custom(block = { narrowed: R -> narrowed.block() }),
            evidence = evidence,
        )
    }

    override fun <V> onAxisSelection(
        axis: Axis<V>,
        values: Set<V>,
    ) where V : AxisValue<V>, V : Enum<V> {
        variantScope.onAxisSelection(axis, values)
    }

    internal fun build(): Targeting.AnyOf<C> = Targeting.AnyOf(leaves.toList())
}
