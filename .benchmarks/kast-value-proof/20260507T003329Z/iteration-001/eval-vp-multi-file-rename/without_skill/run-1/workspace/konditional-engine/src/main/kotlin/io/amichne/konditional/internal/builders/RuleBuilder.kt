@file:OptIn(io.amichne.konditional.api.KonditionalInternalApi::class)

package io.amichne.konditional.internal.builders

import io.amichne.konditional.context.Context
import io.amichne.konditional.context.LocaleTag
import io.amichne.konditional.context.PlatformTag
import io.amichne.konditional.context.RampUp
import io.amichne.konditional.core.dsl.KonditionalDsl
import io.amichne.konditional.core.dsl.VariantDispatchHost
import io.amichne.konditional.core.dsl.VersionRangeScope
import io.amichne.konditional.core.dsl.rules.RuleScope
import io.amichne.konditional.core.dsl.rules.targeting.scopes.AnyOfScope
import io.amichne.konditional.core.dsl.rules.targeting.scopes.NarrowingTargetingScope
import io.amichne.konditional.core.id.HexId
import io.amichne.konditional.core.id.StableId
import io.amichne.konditional.internal.builders.versions.VersionRangeBuilder
import io.amichne.konditional.rules.Rule
import io.amichne.konditional.rules.targeting.Targeting
import io.amichne.konditional.values.RuleId

/**
 * Internal implementation of [RuleScope].
 *
 * Accumulates [Targeting] leaves into a flat list; the final [build] call wraps
 * them in a [Targeting.All] conjunction. Multiple calls to targeting methods
 * compose with AND semantics — each call appends a leaf.
 *
 * @param C The context type that the rules will evaluate against.
 */
@KonditionalDsl
@PublishedApi
@Suppress("TooManyFunctions", "OVERRIDE_DEPRECATION")
internal class RuleBuilder<C : Context>(
    private val ruleId: RuleId = RuleId.unspecified,
    private val leaves: MutableList<Targeting<C>> = mutableListOf(),
) : RuleScope<C>,
    NarrowingTargetingScope<C>,
    VariantDispatchHost by RuleVariantScope(leaves) {
    private var note: String? = null
    private var rampUp: RampUp? = null
    private val allowlist = linkedSetOf<HexId>()

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

    override fun anyOf(build: AnyOfScope<C>.() -> Unit) {
        val anyOfBuilder = AnyOfBuilder<C>().apply(build)
        val node = anyOfBuilder.build()
        if (node.targets.isNotEmpty()) leaves += node
    }

    /**
     * Adds a custom extension predicate.
     *
     * Multiple calls accumulate with AND semantics via the [leaves] list —
     * no ConjunctivePredicate wrapper needed.
     */
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

    override fun allowlist(vararg stableIds: StableId) {
        allowlist += stableIds.map { it.hexId }
    }

    override fun note(text: String) {
        note = text
    }

    override fun rampUp(function: () -> Number) {
        this.rampUp = RampUp.of(function().toDouble())
    }

    internal fun build(): Rule<C> = Rule(
        rampUp = rampUp ?: RampUp.default,
        rampUpAllowlist = allowlist,
        note = note,
        targeting = Targeting.All(leaves.toList()),
        ruleId = ruleId,
    )
}
