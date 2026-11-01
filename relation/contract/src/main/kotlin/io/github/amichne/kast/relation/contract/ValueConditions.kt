package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.util.Collections

class ValueConditions private constructor(val facts: List<ValueCondition>) {
    fun requiring(condition: ValueCondition): Refinement<ValueConditions, ValueSiteFailure> =
        when {
            !matchesBasis(condition.origin.basis) -> Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH)
            condition in facts -> Refinement.Refined(this)
            else -> Refinement.Refined(ValueConditions(Collections.unmodifiableList(facts + condition)))
        }

    fun matchesBasis(basis: SemanticReadIdentity): Boolean = facts.all { it.origin.basis == basis }

    internal fun admits(source: ValueSite, target: ValueSite, kind: ValueTransferKind): Boolean =
        admits(source, target.identity, kind)

    internal fun admits(source: ValueSite, target: ValueSiteIdentity, kind: ValueTransferKind): Boolean {
        if (this == target.conditions) return kind != ValueTransferKind.CAST_RESULT
        if (target.conditions.facts.size != facts.size + 1) return false
        if (target.conditions.facts.dropLast(1) != facts) return false
        val added = target.conditions.facts.last()
        if (!added.origin.matches(source) || added.useRange != target.range) return false
        return when (added) {
            is ReviewedGroupMembership,
            is ReviewedGroupKeyRepresentative,
            is ReviewedPredicateAccepts,
            is ReviewedCallbackResultNonNull,
            is ValueCondition.ReviewedCollectionNormalResult,
            is ValueCondition.ReviewedEntryKeyUnique,
            is ValueCondition.ReviewedMappedKeySurvives,
            is ValueCondition.ReviewedPreservedKeySurvives,
            is ValueCondition.ReviewedEntryComponentStable,
            is ValueCondition.ReviewedEntryPrefixNormal,
            is ValueCondition.ReviewedCallbackNormalReturn -> false
            is ValueCondition.DeclaredCallbackResult ->
                kind == ValueTransferKind.SOURCE_CALL_RETURN &&
                    source.executionOwner == ValueExecutionOwner.declaredCallback(added.binding)
            is ValueCondition.StableLocal ->
                kind == ValueTransferKind.LOCAL_READ && added.version == source.localVersion
            is ValueCondition.StableReceiver -> kind == ValueTransferKind.FIELD_READ
            is ValueCondition.CastSuccess -> kind == ValueTransferKind.CAST_RESULT
            is ValueCondition.StringContentNonNull -> kind == ValueTransferKind.STRING_CONTENT
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueConditions && facts == other.facts

    override fun hashCode(): Int = facts.hashCode()

    companion object {
        val Unconditional = ValueConditions(emptyList())
    }
}
