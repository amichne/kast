@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsResult(
    @DocField(description = "List of compilation diagnostics found in the requested files.")
    val diagnostics: List<Diagnostic>,
    @DocField(description = "Pagination metadata when results are truncated.")
    val page: PageInfo? = null,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
