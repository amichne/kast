package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.result.CallRelation
import io.github.amichne.kast.api.contract.result.RelationCursorInvalidReason
import io.github.amichne.kast.api.contract.result.RelationCursorStaleReason
import io.github.amichne.kast.api.contract.result.RelationTraversalPageInfo
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastCallersResponse

@Serializable
@SerialName("AVAILABLE")
data class KastCallersAvailableResponse(
    val subject: SymbolIdentity,
    val records: List<CallRelation>,
    val page: RelationTraversalPageInfo,
    val schemaVersion: Int = SCHEMA_VERSION,
) : KastCallersResponse

@Serializable
@SerialName("SUBJECT_NOT_FOUND")
data class KastCallersSubjectNotFoundResponse(
    val selector: KastExactSymbolSelector,
) : KastCallersResponse

@Serializable
@SerialName("SUBJECT_IDENTITY_MISMATCH")
data class KastCallersSubjectIdentityMismatchResponse(
    val selector: KastExactSymbolSelector,
    val actual: SymbolIdentity,
) : KastCallersResponse

@Serializable
@SerialName("UNSUPPORTED_SUBJECT_KIND")
data class KastCallersUnsupportedSubjectKindResponse(
    val selector: KastExactSymbolSelector,
    val subject: SymbolIdentity,
) : KastCallersResponse

@Serializable
@SerialName("DEGRADED")
data class KastCallersDegradedResponse(
    val selector: KastExactSymbolSelector,
    val subject: SymbolIdentity,
    val reason: KastCallDegradedReason,
    @Serializable(with = RelationshipResultEvidence.LimitedSerializer::class)
    val evidence: RelationshipResultEvidence.Limited,
) : KastCallersResponse

@Serializable
@SerialName("CURSOR_STALE")
data class KastCallersCursorStaleResponse(
    val selector: KastExactSymbolSelector,
    val reason: RelationCursorStaleReason,
    @Serializable(with = RelationshipResultEvidence.LimitedSerializer::class)
    val evidence: RelationshipResultEvidence.Limited,
) : KastCallersResponse

@Serializable
@SerialName("CURSOR_INVALID")
data class KastCallersCursorInvalidResponse(
    val selector: KastExactSymbolSelector,
    val reason: RelationCursorInvalidReason,
    @Serializable(with = RelationshipResultEvidence.LimitedSerializer::class)
    val evidence: RelationshipResultEvidence.Limited,
) : KastCallersResponse
