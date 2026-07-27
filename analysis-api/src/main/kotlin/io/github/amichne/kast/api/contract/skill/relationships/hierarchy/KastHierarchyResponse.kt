package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.result.RelationCursorInvalidReason
import io.github.amichne.kast.api.contract.result.RelationCursorStaleReason
import io.github.amichne.kast.api.contract.result.RelationTraversalPageInfo
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.TypeHierarchyRelation
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KastHierarchyResponse

@Serializable
@SerialName("AVAILABLE")
data class KastHierarchyAvailableResponse(
    val subject: SymbolIdentity,
    val records: List<TypeHierarchyRelation>,
    val page: RelationTraversalPageInfo,
    val schemaVersion: Int = SCHEMA_VERSION,
) : KastHierarchyResponse

@Serializable
@SerialName("SUBJECT_NOT_FOUND")
data class KastHierarchySubjectNotFoundResponse(
    val selector: KastExactSymbolSelector,
) : KastHierarchyResponse

@Serializable
@SerialName("SUBJECT_IDENTITY_MISMATCH")
data class KastHierarchySubjectIdentityMismatchResponse(
    val selector: KastExactSymbolSelector,
    val actual: SymbolIdentity,
) : KastHierarchyResponse

@Serializable
@SerialName("UNSUPPORTED_SUBJECT_KIND")
data class KastHierarchyUnsupportedSubjectKindResponse(
    val selector: KastExactSymbolSelector,
    val subject: SymbolIdentity,
) : KastHierarchyResponse

@Serializable
@SerialName("DEGRADED")
data class KastHierarchyDegradedResponse(
    val selector: KastExactSymbolSelector,
    val subject: SymbolIdentity,
    val reason: KastHierarchyDegradedReason,
    @Serializable(with = RelationshipResultEvidence.LimitedSerializer::class)
    val evidence: RelationshipResultEvidence.Limited,
) : KastHierarchyResponse

@Serializable
@SerialName("CURSOR_STALE")
data class KastHierarchyCursorStaleResponse(
    val selector: KastExactSymbolSelector,
    val reason: RelationCursorStaleReason,
    @Serializable(with = RelationshipResultEvidence.LimitedSerializer::class)
    val evidence: RelationshipResultEvidence.Limited,
) : KastHierarchyResponse

@Serializable
@SerialName("CURSOR_INVALID")
data class KastHierarchyCursorInvalidResponse(
    val selector: KastExactSymbolSelector,
    val reason: RelationCursorInvalidReason,
    @Serializable(with = RelationshipResultEvidence.LimitedSerializer::class)
    val evidence: RelationshipResultEvidence.Limited,
) : KastHierarchyResponse
