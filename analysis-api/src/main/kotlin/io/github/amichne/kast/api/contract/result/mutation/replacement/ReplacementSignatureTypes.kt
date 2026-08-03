package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
enum class ReplacementVisibility {
    PUBLIC,
    PROTECTED,
    INTERNAL,
    PACKAGE_PROTECTED,
    PACKAGE_PRIVATE,
    PRIVATE,
    LOCAL,
}

@Serializable
enum class ReplacementModality {
    FINAL,
    SEALED,
    OPEN,
    ABSTRACT,
}

@Serializable
enum class ReplacementTypeVariance {
    INVARIANT,
    IN,
    OUT,
}

@Serializable
data class ReplacementTypeParameterSignature(
    @DocField(description = "Compiler-provided type parameter name.")
    val name: String,
    @DocField(description = "Canonical compiler type text for all declared upper bounds, in order.")
    val upperBounds: String,
    @DocField(description = "Compiler-provided variance of the type parameter.")
    val variance: ReplacementTypeVariance,
    @DocField(description = "Whether the type parameter is reified.")
    val reified: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Replacement type parameter name must not be blank" }
        require(upperBounds.isNotBlank()) { "Replacement type parameter upper bounds must not be blank" }
    }
}

@Serializable
data class ReplacementValueParameterSignature(
    @DocField(description = "Compiler-provided value parameter name.")
    val name: String,
    @DocField(description = "Canonical compiler type text for the value parameter.")
    val type: String,
    @DocField(description = "Whether the value parameter is vararg.")
    val vararg: Boolean,
    @DocField(description = "Whether the value parameter declares a default value.")
    val hasDefaultValue: Boolean,
    @DocField(description = "Whether the value parameter is noinline.")
    val noinline: Boolean,
    @DocField(description = "Whether the value parameter is crossinline.")
    val crossinline: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Replacement value parameter name must not be blank" }
        require(type.isNotBlank()) { "Replacement value parameter type must not be blank" }
    }
}
