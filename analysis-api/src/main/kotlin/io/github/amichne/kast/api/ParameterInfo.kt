package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val defaultValue: String? = null,
    val isVararg: Boolean = false,
)
