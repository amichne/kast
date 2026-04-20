@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class ParameterInfo(
    @DocField(description = "Parameter name as declared in source.")
    val name: String,
    @DocField(description = "Fully qualified type of the parameter.")
    val type: String,
    @DocField(description = "Default value expression text, if any.")
    val defaultValue: String? = null,
    @DocField(description = "Whether this parameter is declared as vararg.", defaultValue = "false")
    val isVararg: Boolean = false,
)
