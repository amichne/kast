package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.RelationCursorInvalidReason
import io.github.amichne.kast.api.contract.result.RelationCursorStaleReason
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastReferencesResponse

@Serializable
@SerialName("AVAILABLE")
data class KastReferencesAvailableResponse(
    val subject: SymbolIdentity,
    val references: List<ReferenceOccurrence>,
    val cardinality: ResultCardinality,
    val page: PageInfo? = null,
    val searchScope: SearchScope? = null,
    val declaration: Symbol? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
) : KastReferencesResponse

@Serializable
@SerialName("SUBJECT_NOT_FOUND")
data class KastReferencesSubjectNotFoundResponse(
    val selector: KastExactSymbolSelector,
) : KastReferencesResponse

@Serializable
@SerialName("SUBJECT_IDENTITY_MISMATCH")
data class KastReferencesSubjectIdentityMismatchResponse(
    val selector: KastExactSymbolSelector,
    val actual: SymbolIdentity,
) : KastReferencesResponse

@Serializable
@SerialName("UNSUPPORTED_SUBJECT_KIND")
data class KastReferencesUnsupportedSubjectKindResponse(
    val selector: KastExactSymbolSelector,
    val subject: SymbolIdentity,
) : KastReferencesResponse

@Serializable
@SerialName("DEGRADED")
data class KastReferencesDegradedResponse(
    val selector: KastExactSymbolSelector,
    val subject: SymbolIdentity,
    val reason: KastReferencesDegradedReason,
) : KastReferencesResponse

@Serializable
@SerialName("CURSOR_STALE")
data class KastReferencesCursorStaleResponse(
    val selector: KastExactSymbolSelector,
    val reason: RelationCursorStaleReason,
) : KastReferencesResponse

@Serializable
@SerialName("CURSOR_INVALID")
data class KastReferencesCursorInvalidResponse(
    val selector: KastExactSymbolSelector,
    val reason: RelationCursorInvalidReason,
) : KastReferencesResponse
