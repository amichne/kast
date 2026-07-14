package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.result.ImplementationRelation
import io.github.amichne.kast.api.contract.result.RelationCursorInvalidReason
import io.github.amichne.kast.api.contract.result.RelationCursorStaleReason
import io.github.amichne.kast.api.contract.result.RelationTraversalPageInfo
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastImplementationsResponse

@Serializable
@SerialName("AVAILABLE")
data class KastImplementationsAvailableResponse(
    val subject: SymbolIdentity,
    val records: List<ImplementationRelation>,
    val page: RelationTraversalPageInfo,
    val schemaVersion: Int = SCHEMA_VERSION,
) : KastImplementationsResponse

@Serializable
@SerialName("SUBJECT_NOT_FOUND")
data class KastImplementationsSubjectNotFoundResponse(
    val selector: KastExactSymbolSelector,
) : KastImplementationsResponse

@Serializable
@SerialName("SUBJECT_IDENTITY_MISMATCH")
data class KastImplementationsSubjectIdentityMismatchResponse(
    val selector: KastExactSymbolSelector,
    val actual: SymbolIdentity,
) : KastImplementationsResponse

@Serializable
@SerialName("UNSUPPORTED_SUBJECT_KIND")
data class KastImplementationsUnsupportedSubjectKindResponse(
    val selector: KastExactSymbolSelector,
    val subject: SymbolIdentity,
) : KastImplementationsResponse

@Serializable
@SerialName("DEGRADED")
data class KastImplementationsDegradedResponse(
    val selector: KastExactSymbolSelector,
    val subject: SymbolIdentity,
    val reason: KastImplementationsDegradedReason,
) : KastImplementationsResponse

@Serializable
@SerialName("CURSOR_STALE")
data class KastImplementationsCursorStaleResponse(
    val selector: KastExactSymbolSelector,
    val reason: RelationCursorStaleReason,
) : KastImplementationsResponse

@Serializable
@SerialName("CURSOR_INVALID")
data class KastImplementationsCursorInvalidResponse(
    val selector: KastExactSymbolSelector,
    val reason: RelationCursorInvalidReason,
) : KastImplementationsResponse
