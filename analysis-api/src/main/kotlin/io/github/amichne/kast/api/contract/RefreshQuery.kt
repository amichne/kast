@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class RefreshQuery(
    @DocField(description = "Absolute paths of files to refresh. Empty for a full workspace refresh.", defaultValue = "emptyList()")
    val filePaths: List<String> = emptyList(),
)
