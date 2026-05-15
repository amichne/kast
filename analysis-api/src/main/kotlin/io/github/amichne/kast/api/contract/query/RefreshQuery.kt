@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class RefreshQuery(
    @DocField(description = "Absolute paths of files to refresh. Empty for a full workspace refresh.", defaultValue = "emptyList()")
    val filePaths: List<String> = emptyList(),
)
