@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class ImportOptimizeQuery(
    @DocField(description = "Absolute paths of the files whose imports should be optimized.")
    val filePaths: List<String>,
)
