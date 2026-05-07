@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.core

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.dsl.FlagScope
import io.amichne.konditional.core.features.BooleanFeature
import io.amichne.konditional.core.features.DoubleFeature
import io.amichne.konditional.core.features.EnumFeature
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.features.IntFeature
import io.amichne.konditional.core.features.KotlinClassFeature
import io.amichne.konditional.core.features.StringFeature
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.registry.InMemoryNamespaceRegistry
import io.amichne.konditional.core.registry.NamespaceRegistry
import io.amichne.konditional.core.spi.FeatureRegistrationHooks
import io.amichne.konditional.core.types.Konstrained
import io.amichne.konditional.internal.builders.FlagBuilder
import io.amichne.konditional.values.NamespaceId
import java.util.UUID
import kotlin.reflect.KProperty

/**
 * Namespace-scoped feature container with atomic runtime state.
 */
open class Namespace private constructor(
    val id: NamespaceId = defaultNamespaceId(),
    @property:KonditionalInternalApi
    val registry: NamespaceRegistry = InMemoryNamespaceRegistry(namespaceId = id.value),
    @PublishedApi internal val identifierSeed: NamespaceId.Seed = id.seed(),
) : NamespaceRegistry by registry {
    constructor() : this(defaultNamespaceId())

    constructor(id: String) : this(NamespaceId(id))

    private val features = mutableListOf<Feature<*, *, *>>()
    private val declaredDefaults = linkedMapOf<Feature<*, *, *>, Any>()
    private val declaredDefinitions = linkedMapOf<Feature<*, *, *>, FlagDefinition<*, *, *>>()

    fun allFeatures(): List<Feature<*, *, *>> = features.toList()

    @KonditionalInternalApi
    fun declaredDefault(feature: Feature<*, *, *>): Any? = declaredDefaults[feature]

    @KonditionalInternalApi
    fun declaredDefinition(feature: Feature<*, *, *>): FlagDefinition<*, *, *>? = declaredDefinitions[feature]

    override val configuration: Configuration
        get() = Configuration(
            flags = declaredDefinitions + registry.configuration.flags,
            metadata = registry.configuration.metadata,
        )

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any, C : Context, M : Namespace> flag(
        key: Feature<T, C, M>,
    ): FlagDefinition<T, C, M> =
        findFlag(key)
            ?: error("Flag not found for feature '${key.key}' in namespace '$id'.")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any, C : Context, M : Namespace> findFlag(
        key: Feature<T, C, M>,
    ): FlagDefinition<T, C, M>? =
        registry.findFlag(key)
            ?: declaredDefinition(key) as? FlagDefinition<T, C, M>

    protected fun <C : Context> boolean(
        default: Boolean,
        flagScope: FlagScope<Boolean, C, Namespace>.() -> Unit = {},
    ): BooleanDelegate<C> = BooleanDelegate(default, flagScope)

    protected fun <C : Context> string(
        default: String,
        stringScope: FlagScope<String, C, Namespace>.() -> Unit = {},
    ): StringDelegate<C> = StringDelegate(default, stringScope)

    protected fun <C : Context> integer(
        default: Int,
        integerScope: FlagScope<Int, C, Namespace>.() -> Unit = {},
    ): IntDelegate<C> = IntDelegate(default, integerScope)

    protected fun <C : Context> double(
        default: Double,
        decimalScope: FlagScope<Double, C, Namespace>.() -> Unit = {},
    ): DoubleDelegate<C> = DoubleDelegate(default, decimalScope)

    protected fun <E : Enum<E>, C : Context> enum(
        default: E,
        enumScope: FlagScope<E, C, Namespace>.() -> Unit = {},
    ): EnumDelegate<E, C> = EnumDelegate(default, enumScope)

    protected inline fun <reified T : Konstrained, C : Context> custom(
        default: T,
        noinline customScope: FlagScope<T, C, Namespace>.() -> Unit = {},
    ): KotlinClassDelegate<T, C> = KotlinClassDelegate(default, customScope)

    @Suppress("LongParameterList")
    private inline fun <T : Any, C : Context, M : Namespace, F : Feature<T, C, M>, D> registerFeature(
        thisRef: M,
        property: KProperty<*>,
        default: T,
        configScope: FlagScope<T, C, Namespace>.() -> Unit,
        featureFactory: (String, M) -> F,
        featureSetter: (F) -> Unit,
        delegateInstance: D,
    ): D =
        delegateInstance.apply {
            featureFactory(property.name, thisRef).also { feature ->
                featureSetter(feature)
                (thisRef as Namespace).features += feature
                thisRef.declaredDefaults[feature] = default
                val definition = FlagBuilder(default, feature).apply(configScope).build()
                thisRef.declaredDefinitions[feature] = definition
                FeatureRegistrationHooks.notifyFeatureDefined(feature)
            }
        }

    @Suppress("UNCHECKED_CAST")
    protected class BooleanDelegate<C : Context>(
        private val default: Boolean,
        private val configScope: FlagScope<Boolean, C, Namespace>.() -> Unit,
    ) {
        private lateinit var feature: BooleanFeature<C, *>

        operator fun <M : Namespace> provideDelegate(thisRef: M, property: KProperty<*>): BooleanDelegate<C> =
            thisRef.registerFeature(
                thisRef = thisRef,
                property = property,
                default = default,
                configScope = configScope,
                featureFactory = { name, namespace -> BooleanFeature(name, namespace) },
                featureSetter = { feature = it },
                delegateInstance = this,
            )

        operator fun <M : Namespace> getValue(thisRef: M, property: KProperty<*>): BooleanFeature<C, M> =
            feature as BooleanFeature<C, M>
    }

    @Suppress("UNCHECKED_CAST")
    protected class StringDelegate<C : Context>(
        private val default: String,
        private val configScope: FlagScope<String, C, Namespace>.() -> Unit,
    ) {
        private lateinit var feature: StringFeature<C, *>

        operator fun <M : Namespace> provideDelegate(thisRef: M, property: KProperty<*>): StringDelegate<C> =
            thisRef.registerFeature(
                thisRef = thisRef,
                property = property,
                default = default,
                configScope = configScope,
                featureFactory = { name, namespace -> StringFeature(name, namespace) },
                featureSetter = { feature = it },
                delegateInstance = this,
            )

        operator fun <M : Namespace> getValue(thisRef: M, property: KProperty<*>): StringFeature<C, M> =
            feature as StringFeature<C, M>
    }

    @Suppress("UNCHECKED_CAST")
    protected class IntDelegate<C : Context>(
        private val default: Int,
        private val configScope: FlagScope<Int, C, Namespace>.() -> Unit,
    ) {
        private lateinit var feature: IntFeature<C, *>

        operator fun <M : Namespace> provideDelegate(thisRef: M, property: KProperty<*>): IntDelegate<C> =
            thisRef.registerFeature(
                thisRef = thisRef,
                property = property,
                default = default,
                configScope = configScope,
                featureFactory = { name, namespace -> IntFeature(name, namespace) },
                featureSetter = { feature = it },
                delegateInstance = this,
            )

        operator fun <M : Namespace> getValue(thisRef: M, property: KProperty<*>): IntFeature<C, M> =
            feature as IntFeature<C, M>
    }

    @Suppress("UNCHECKED_CAST")
    protected class DoubleDelegate<C : Context>(
        private val default: Double,
        private val configScope: FlagScope<Double, C, Namespace>.() -> Unit,
    ) {
        private lateinit var feature: DoubleFeature<C, *>

        operator fun <M : Namespace> provideDelegate(thisRef: M, property: KProperty<*>): DoubleDelegate<C> =
            thisRef.registerFeature(
                thisRef = thisRef,
                property = property,
                default = default,
                configScope = configScope,
                featureFactory = { name, namespace -> DoubleFeature(name, namespace) },
                featureSetter = { feature = it },
                delegateInstance = this,
            )

        operator fun <M : Namespace> getValue(thisRef: M, property: KProperty<*>): DoubleFeature<C, M> =
            feature as DoubleFeature<C, M>
    }

    @Suppress("UNCHECKED_CAST")
    protected class EnumDelegate<E : Enum<E>, C : Context>(
        private val default: E,
        private val configScope: FlagScope<E, C, Namespace>.() -> Unit,
    ) {
        private lateinit var feature: EnumFeature<E, C, *>

        operator fun <M : Namespace> provideDelegate(thisRef: M, property: KProperty<*>): EnumDelegate<E, C> =
            thisRef.registerFeature(
                thisRef = thisRef,
                property = property,
                default = default,
                configScope = configScope,
                featureFactory = { name, namespace -> EnumFeature(name, namespace) },
                featureSetter = { feature = it },
                delegateInstance = this,
            )

        operator fun <M : Namespace> getValue(thisRef: M, property: KProperty<*>): EnumFeature<E, C, M> =
            feature as EnumFeature<E, C, M>
    }

    @Suppress("UNCHECKED_CAST")
    protected class KotlinClassDelegate<T : Konstrained, C : Context>(
        private val default: T,
        private val configScope: FlagScope<T, C, Namespace>.() -> Unit,
    ) {
        private lateinit var feature: KotlinClassFeature<T, C, *>

        operator fun <M : Namespace> provideDelegate(
            thisRef: M,
            property: KProperty<*>,
        ): KotlinClassDelegate<T, C> =
            thisRef.registerFeature(
                thisRef = thisRef,
                property = property,
                default = default,
                configScope = configScope,
                featureFactory = { name, namespace -> KotlinClassFeature(name, namespace) },
                featureSetter = { feature = it },
                delegateInstance = this,
            )

        operator fun <M : Namespace> getValue(thisRef: M, property: KProperty<*>): KotlinClassFeature<T, C, M> =
            feature as KotlinClassFeature<T, C, M>
    }

    abstract class TestNamespaceFacade(
        id: String,
        registry: NamespaceRegistry = InMemoryNamespaceRegistry(namespaceId = id),
        identifierSeed: String = UUID.randomUUID().toString(),
    ) : Namespace(
            id = NamespaceId(id),
            registry = registry,
            identifierSeed = NamespaceId.Seed(identifierSeed),
        ) {
        constructor(id: NamespaceId) : this(id = id.value)
    }

    override fun equals(other: Any?): Boolean =
        other is Namespace && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Namespace($id)"

    private companion object {
        fun defaultNamespaceId(): NamespaceId {
            val inferredClassName =
                Thread.currentThread().stackTrace
                    .asSequence()
                    .map { it.className }
                    .firstOrNull { candidate ->
                        candidate != Namespace::class.java.name &&
                            runCatching {
                                Namespace::class.java.isAssignableFrom(Class.forName(candidate))
                            }.getOrDefault(false)
                    }

            return NamespaceId(
                requireNotNull(inferredClassName) {
                    "Unable to infer namespace id. Provide Namespace(\"<stable-id>\") explicitly."
                },
            )
        }
    }
}
