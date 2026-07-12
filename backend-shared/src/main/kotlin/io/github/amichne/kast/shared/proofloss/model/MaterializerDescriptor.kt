package io.github.amichne.kast.shared.proofloss.model

sealed interface MaterializerDescriptor {
    val callable: CallableKey

    data class Total(override val callable: CallableKey) : MaterializerDescriptor
    data class NullableWithExit(override val callable: CallableKey) : MaterializerDescriptor
}
