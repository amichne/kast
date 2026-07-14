package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastReferencesResponse

@Serializable
@SerialName("REFERENCES_SUCCESS")
data class KastReferencesSuccessResponse(
    val ok: Boolean = true,
    val query: KastReferencesQuery,
    val symbol: Symbol,
    val filePath: String,
    val offset: Int,
    val references: List<Location>,
    val cardinality: ResultCardinality,
    val page: PageInfo? = null,
    val searchScope: SearchScope? = null,
    val declaration: Symbol? = null,
    val candidateCount: Int? = null,
    val alternatives: List<String>? = null,
    val logFile: String,
) : KastReferencesResponse

@Serializable
@SerialName("REFERENCES_FAILURE")
data class KastReferencesFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastReferencesQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastReferencesResponse
