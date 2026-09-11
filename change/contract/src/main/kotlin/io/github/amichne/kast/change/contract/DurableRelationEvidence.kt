package io.github.amichne.kast.change.contract

import io.github.amichne.kast.relation.contract.RelationMeaning
import kotlinx.serialization.Serializable

@Serializable
enum class AddDeclarationRelationMeaning {
    REFERENCES,
    CALLERS,
    CALLEES,
    IMPLEMENTATIONS,
    INHERITORS,
    OVERRIDES,
    TYPE_USES,
}

@Serializable
@JvmInline
value class ChangePlanningEvidenceProjection private constructor(val value: String) {
    companion object {
        internal fun fromProven(value: String): ChangePlanningEvidenceProjection =
            ChangePlanningEvidenceProjection(value)
    }
}

@Serializable
@JvmInline
value class StableRelationEvidenceDigest private constructor(val value: String) {
    companion object {
        internal fun fromProven(value: String): StableRelationEvidenceDigest = StableRelationEvidenceDigest(value)
    }
}

@Serializable
data class DurableAddDeclarationRelationEvidence
internal constructor(
    val meaning: AddDeclarationRelationMeaning,
    val projection: ChangePlanningEvidenceProjection,
    val stableDigest: StableRelationEvidenceDigest,
)

fun AddDeclarationRelationMeaning.domain(): RelationMeaning =
    when (this) {
        AddDeclarationRelationMeaning.REFERENCES -> RelationMeaning.References
        AddDeclarationRelationMeaning.CALLERS -> RelationMeaning.Callers
        AddDeclarationRelationMeaning.CALLEES -> RelationMeaning.Callees
        AddDeclarationRelationMeaning.IMPLEMENTATIONS -> RelationMeaning.Implementations
        AddDeclarationRelationMeaning.INHERITORS -> RelationMeaning.Inheritors
        AddDeclarationRelationMeaning.OVERRIDES -> RelationMeaning.Overrides
        AddDeclarationRelationMeaning.TYPE_USES -> RelationMeaning.TypeUses
    }
