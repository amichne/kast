package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastResolveResponse {
    @Serializable
    enum class Source {
        @SerialName("compiler")
        COMPILER,
    }
}

@Serializable
@SerialName("RESOLVE_SUCCESS")
data class KastResolveSuccessResponse(
    val ok: Boolean = true,
    val source: KastResolveResponse.Source = KastResolveResponse.Source.COMPILER,
    val query: KastResolveQuery,
    val symbol: Symbol,
    val filePath: String,
    val offset: Int,
    val candidate: KastCandidate,
    val candidateCount: Int? = null,
    val alternatives: List<String>? = null,
    val context: KastResolveContext? = null,
    val logFile: String,
) : KastResolveResponse

@Serializable
@SerialName("RESOLVE_NOT_FOUND")
data class KastResolveNotFoundResponse(
    val ok: Boolean = true,
    val source: KastResolveResponse.Source = KastResolveResponse.Source.COMPILER,
    val query: KastResolveQuery,
    val logFile: String,
) : KastResolveResponse

@Serializable
@SerialName("RESOLVE_AMBIGUOUS")
data class KastResolveAmbiguousResponse(
    val ok: Boolean = true,
    val source: KastResolveResponse.Source = KastResolveResponse.Source.COMPILER,
    val query: KastResolveQuery,
    val candidates: List<Symbol>,
    val logFile: String,
) : KastResolveResponse

@Serializable
@SerialName("RESOLVE_FAILURE")
data class KastResolveFailureResponse(
    val ok: Boolean = false,
    val stage: String,
    val message: String,
    val query: KastResolveQuery,
    val logFile: String,
    val error: ApiErrorResponse? = null,
    val errorText: String? = null,
) : KastResolveResponse
