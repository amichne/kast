package io.amichne.konditional.core.features

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.Namespace

sealed interface StringFeature<C : Context, M : Namespace> : Feature<String, C, M> {
    companion object {
        internal operator fun <C : Context, M : Namespace> invoke(
            key: String,
            module: M,
        ): StringFeature<C, M> =
            StringFeatureImpl(key, module)

        @PublishedApi
        internal data class StringFeatureImpl<C : Context, M : Namespace>(
            override val key: String,
            override val namespace: M,
        ) : StringFeature<C, M>, Identifiable.ById by Identifiable.ById(key, namespace.id)
    }
}
