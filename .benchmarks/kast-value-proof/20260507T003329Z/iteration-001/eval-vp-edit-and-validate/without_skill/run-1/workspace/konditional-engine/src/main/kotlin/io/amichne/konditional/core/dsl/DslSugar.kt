@file:OptIn(KonditionalInternalApi::class)
@file:Suppress("TooManyFunctions")

package io.amichne.konditional.core.dsl

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.axis.KonditionalExplicitId
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.rules.ContextRuleScope
import io.amichne.konditional.core.dsl.rules.NamespaceRuleSet
import io.amichne.konditional.core.dsl.rules.RuleScope
import io.amichne.konditional.core.dsl.rules.RuleSet
import io.amichne.konditional.core.dsl.rules.RuleSetBuilder
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.values.RuleId
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * Semantic tokens for boolean values in DSL contexts.
 */
const val ENABLED: Boolean = true
const val DISABLED: Boolean = false

/**
 * Defines a boolean rule that yields `true` when the criteria matches.
 *
 * This is syntactic sugar for `rule(true) { ... }`.
 *
 * @param build DSL block for configuring targeting criteria
 */
fun <C : Context, M : Namespace> FlagScope<Boolean, C, M>.enable(build: RuleScope<C>.() -> Unit = {}) =
    rule(ENABLED, build)

/**
 * Defines a boolean rule that yields `false` when the criteria matches.
 *
 * This is syntactic sugar for `rule(false) { ... }`.
 *
 * @param build DSL block for configuring targeting criteria
 */
fun <C : Context, M : Namespace> FlagScope<Boolean, C, M>.disable(build: RuleScope<C>.() -> Unit = {}) =
    rule(DISABLED, build)

/**
 * Defines a boolean rule that yields `true` using a composable rule scope.
 *
 * This is syntactic sugar for `ruleScoped(true) { ... }`.
 *
 * @param build DSL block for configuring composable targeting criteria
 */
@KonditionalInternalApi
fun <C : Context, M : Namespace> FlagScope<Boolean, C, M>.enableScoped(build: ContextRuleScope<C>.() -> Unit = {}) =
    ruleScoped(ENABLED, build)

/**
 * Defines a boolean rule that yields `false` using a composable rule scope.
 *
 * This is syntactic sugar for `ruleScoped(false) { ... }`.
 *
 * @param build DSL block for configuring composable targeting criteria
 */
@KonditionalInternalApi
fun <C : Context, M : Namespace> FlagScope<Boolean, C, M>.disableScoped(build: ContextRuleScope<C>.() -> Unit = {}) =
    ruleScoped(DISABLED, build)

/**
 * Builds a rule set scoped to this feature using the feature's declared context type.
 *
 * This overload keeps call sites minimal when you do not need a contravariant
 * context type. The compiler infers all types from the feature receiver.
 */
@JvmName("ruleSetDefault")
fun <T : Any, C : Context, M : Namespace> Feature<T, C, M>.ruleSet(
    build: RuleSetBuilder<T, C>.() -> Unit,
): RuleSet<C, T, C, M> =
    RuleSet(
        feature = this,
        rules = RuleSetBuilder<T, C>(
            ruleIdFactory = { ruleOrdinal -> RuleId.forFeatureRuleSetRule(id, ruleOrdinal) },
        ).apply(build).build(),
    )

/**
 * Builds a rule set using an explicit supertype context without reified generics.
 *
 * Use this when you want contravariant rule sets but prefer value-based
 * type selection at the call site:
 * ```kotlin
 * val global = feature.ruleSet(Context::class) { rule(value) { ios() } }
 * ```
 */
@JvmName("ruleSetWithContextType")
fun <T : Any, C, M : Namespace, RC : Context> Feature<T, C, M>.ruleSet(
    @Suppress("UNUSED_PARAMETER") contextType: KClass<RC>,
    build: RuleSetBuilder<T, RC>.() -> Unit,
): RuleSet<RC, T, C, M> where C : RC =
    RuleSet(
        feature = this,
        rules = RuleSetBuilder<T, RC>(
            ruleIdFactory = { ruleOrdinal -> RuleId.forFeatureRuleSetRule(id, ruleOrdinal) },
        ).apply(build).build(),
    )

/**
 * Builds a rule set using a reified supertype context.
 *
 * Prefer this when you want contravariant context composition and a terse
 * call site:
 * ```kotlin
 * val global = feature.ruleSet<Context> { rule(value) { ios() } }
 * ```
 */
inline fun <reified RC : Context, T : Any, C, M : Namespace> Feature<T, C, M>.ruleSet(
    build: RuleSetBuilder<T, RC>.() -> Unit,
): RuleSet<RC, T, C, M> where C : RC =
    RuleSet(
        feature = this,
        rules = RuleSetBuilder<T, RC>(
            ruleIdFactory = { ruleOrdinal -> RuleId.forFeatureRuleSetRule(id, ruleOrdinal) },
        ).apply(build).build(),
    )

/**
 * Declares a namespace-scoped reusable rule set using an explicit value type.
 *
 * This API is delegate-first:
 * ```kotlin
 * private val sharedCheckout by ruleSet<CheckoutVariant, CommerceContext, Namespace> {
 *     rule(CheckoutVariant.IOS_LOCAL) { ios() }
 * }
 * ```
 * The seed used for deterministic [RuleId] generation is derived from the delegated property name.
 * To pin a seed across property renames, annotate the property with [KonditionalExplicitId].
 */
@JvmName("namespaceRuleSetDefault")
inline fun <reified T : Any, C : Context, M : Namespace> M.ruleSet(
    noinline build: RuleSetBuilder<T, C>.() -> Unit,
): NamespaceRuleSetDelegate<C, T, C, M> = namespaceRuleSetDelegate(build)

/**
 * Declares a namespace-scoped reusable rule set using an explicit supertype context.
 *
 * The delegated property name is used as the rule-set seed unless [KonditionalExplicitId] is present.
 */
@JvmName("namespaceRuleSetWithContextType")
inline fun <reified T : Any, C, M : Namespace, RC : Context> M.ruleSet(
    @Suppress("UNUSED_PARAMETER")
    name: String,
    @Suppress("UNUSED_PARAMETER") contextType: KClass<RC>,
    noinline build: RuleSetBuilder<T, RC>.() -> Unit,
): NamespaceRuleSetDelegate<RC, T, C, M> where C : RC = namespaceRuleSetDelegate(build)

/**
 * Declares a namespace-scoped reusable rule set using reified value and context supertypes.
 *
 * The delegated property name is used as the rule-set seed unless [KonditionalExplicitId] is present.
 */
inline fun <reified T : Any, reified RC : Context, C, M : Namespace> M.ruleSet(
    noinline build: RuleSetBuilder<T, RC>.() -> Unit,
): NamespaceRuleSetDelegate<RC, T, C, M> where C : RC = namespaceRuleSetDelegate(build)

@PublishedApi
internal fun <T : Any, RC : Context, C, M : Namespace> M.namespaceRuleSetDelegate(
    build: RuleSetBuilder<T, RC>.() -> Unit,
): NamespaceRuleSetDelegate<RC, T, C, M> where C : RC =
    NamespaceRuleSetDelegate(
        buildRuleSet = { seed ->
            NamespaceRuleSet(
                namespace = this,
                rules = RuleSetBuilder<T, RC>(
                    ruleIdFactory = { ruleOrdinal -> RuleId.forNamespaceRuleSetRule(id, seed, ruleOrdinal) },
                ).apply(build).build(),
            )
        },
    )

/**
 * Delegate used by namespace-scoped reusable rule-set declarations.
 *
 * On [provideDelegate], this derives the rule-set seed from:
 * 1. [KonditionalExplicitId] on the property, when present.
 * 2. Otherwise, [KProperty.name].
 */
class NamespaceRuleSetDelegate<RC : Context, T : Any, C, M : Namespace> @PublishedApi internal constructor(
    private val buildRuleSet: (String) -> NamespaceRuleSet<RC, T, C, M>,
) where C : RC {
    private var value: NamespaceRuleSet<RC, T, C, M>? = null

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): NamespaceRuleSetDelegate<RC, T, C, M> = apply {
        val explicitId = property.annotations.filterIsInstance<KonditionalExplicitId>().firstOrNull()?.id
        val seed = explicitId ?: property.name
        require(seed.isNotBlank()) {
            "Rule-set seed for property '${property.name}' must be non-blank."
        }
        value = buildRuleSet(seed)
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): NamespaceRuleSet<RC, T, C, M> =
        checkNotNull(value) {
            "Namespace rule set '${property.name}' has not been initialized."
        }
}
