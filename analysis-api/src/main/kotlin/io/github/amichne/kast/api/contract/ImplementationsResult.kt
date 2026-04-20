@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class ImplementationsResult(
    @DocField(description = "The interface or abstract class symbol that was queried.")
    val declaration: Symbol,
    @DocField(description = "Concrete implementations or subclasses found.")
    val implementations: List<Symbol>,
    @DocField(description = "True when all implementations were found within maxResults.", defaultValue = "true")
    val exhaustive: Boolean = true,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
