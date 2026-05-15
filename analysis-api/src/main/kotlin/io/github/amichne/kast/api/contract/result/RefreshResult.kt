@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class RefreshResult(
    @DocField(description = "Absolute paths of files whose state was refreshed.")
    val refreshedFiles: List<String>,
    @DocField(description = "Absolute paths of files that were removed from the workspace.", defaultValue = "emptyList()")
    val removedFiles: List<String> = emptyList(),
    @DocField(description = "True when a full workspace refresh was performed.")
    val fullRefresh: Boolean,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
)
