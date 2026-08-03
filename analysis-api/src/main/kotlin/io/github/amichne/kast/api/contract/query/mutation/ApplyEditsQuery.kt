@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.query

import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class ApplyEditsQuery(
    @DocField(description = "Text edits to apply, typically from a prior rename or code action.")
    val edits: List<TextEdit>,
    @DocField(description = "Expected file hashes for conflict detection before writing.")
    val fileHashes: List<FileHash>,
    @DocField(description = "Optional file create or delete operations to perform.", defaultValue = "emptyList()")
    val fileOperations: List<FileOperation> = emptyList(),
    @DocField(description = "Optional canonical UUID-v4 verified mutation attempt.")
    val mutationAttemptId: String? = null,
    @DocField(description = "One predeclared scratch set per verified file operation.", defaultValue = "emptyList()")
    val mutationScratchSets: List<MutationScratchSet> = emptyList(),
)
