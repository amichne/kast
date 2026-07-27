package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.CallHierarchyStats
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.result.TypeHierarchyNode
import io.github.amichne.kast.api.contract.result.TypeHierarchyStats
import io.github.amichne.kast.api.protocol.ApiErrorResponse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class KastWriteAndValidateFailureQuery(
    val type: String? = null,
    val workspaceRoot: String,
    val filePath: String,
)

@Serializable
sealed interface KastWriteAndValidateQuery

@Serializable
@SerialName("CREATE_FILE_REQUEST")
data class KastWriteAndValidateCreateFileQuery(
    val workspaceRoot: String,
    val filePath: String,
) : KastWriteAndValidateQuery

@Serializable
@SerialName("INSERT_AT_OFFSET_REQUEST")
data class KastWriteAndValidateInsertAtOffsetQuery(
    val workspaceRoot: String,
    val filePath: String,
    val offset: Int,
) : KastWriteAndValidateQuery

@Serializable
@SerialName("REPLACE_RANGE_REQUEST")
data class KastWriteAndValidateReplaceRangeQuery(
    val workspaceRoot: String,
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
) : KastWriteAndValidateQuery
