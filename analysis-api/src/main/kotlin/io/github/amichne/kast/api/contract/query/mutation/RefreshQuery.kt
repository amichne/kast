@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class RefreshQuery(
    @DocField(
        description = "Absolute paths of files to refresh. Empty with no external failure IDs for a full workspace refresh.",
        defaultValue = "emptyList()",
    )
    val filePaths: List<String> = emptyList(),
    @DocField(
        description = "Failure IDs to accept as unknown external graph boundaries. Mutually exclusive with filePaths.",
        defaultValue = "emptyList()",
    )
    val externalFailureIds: List<String> = emptyList(),
)
