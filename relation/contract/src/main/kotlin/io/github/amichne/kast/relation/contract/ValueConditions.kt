package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.util.Collections

enum class ValueCastKind {
    UNSAFE,
    SAFE,
}

/** The subject of a conditional fact, without recursively embedding its preceding conditions. */
@ConsistentCopyVisibility
data class ValueConditionOrigin
private constructor(
    val execution: ValueExecutionOwner,
    val range: ExactDeclarationTextRange,
    val role: ValueRole,
    val valuePath: ValueAccessPath,
    val callStack: ValueCallStack,
) {
    val basis: SemanticReadIdentity
        get() = execution.enclosing.lease.identity

    fun matches(site: ValueSite): Boolean = this == fromSite(site)

    companion object {
        internal fun fromSite(site: ValueSite): ValueConditionOrigin =
            ValueConditionOrigin(site.executionOwner, site.range, site.role, site.valuePath, site.callStack)
    }
}

/** A use-site fact is conditional on execution reaching that use; it is not a runtime reachability proof. */
sealed interface ValueCondition {
    val origin: ValueConditionOrigin
    val useRange: ExactDeclarationTextRange

    /**
     * Prerequisite that the cast completes preserving its operand. Unsafe failure is abrupt; safe failure produces
     * null, not the operand. Types describe the compiler operand and target, not a runtime generic check.
     */
    @ConsistentCopyVisibility
    data class CastSuccess
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val kind: ValueCastKind,
        val originalType: CanonicalCompilerType,
        val targetType: CanonicalCompilerType,
    ) : ValueCondition {
        companion object {
            /** Native K2 identifies the operand and entire cast, including any parenthesized operand. */
            fun fromCompiler(
                source: ValueSite,
                useRange: ExactDeclarationTextRange,
                kind: ValueCastKind,
                originalType: CanonicalCompilerType,
                targetType: CanonicalCompilerType,
            ): Refinement<CastSuccess, ValueSiteFailure> =
                when {
                    !source.executionOwner.range.containsValueRange(useRange) ->
                        Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
                    !useRange.containsValueRange(source.range) ->
                        Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER)
                    else ->
                        Refinement.Refined(
                            CastSuccess(
                                ValueConditionOrigin.fromSite(source),
                                useRange,
                                kind,
                                originalType,
                                targetType,
                            )
                        )
                }
        }
    }

    @ConsistentCopyVisibility
    data class StableReceiver
    private constructor(
        override val origin: ValueConditionOrigin,
        override val useRange: ExactDeclarationTextRange,
        val kind: ValueReceiverKind,
        val originalType: CanonicalCompilerType,
        val refinedType: CanonicalCompilerType,
    ) : ValueCondition {
        companion object {
            /** Native K2 establishes stability, receiver identity and both types at this exact use. */
            fun fromCompiler(
                source: ValueSite,
                useRange: ExactDeclarationTextRange,
                kind: ValueReceiverKind,
                originalType: CanonicalCompilerType,
                refinedType: CanonicalCompilerType,
            ): Refinement<StableReceiver, ValueSiteFailure> =
                when {
                    source.role != ValueRole.Formal(ValueFormalPosition.Receiver(kind)) ->
                        Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                    !source.executionOwner.range.containsValueRange(useRange) ->
                        Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
                    else ->
                        Refinement.Refined(
                            StableReceiver(
                                ValueConditionOrigin.fromSite(source),
                                useRange,
                                kind,
                                originalType,
                                refinedType,
                            )
                        )
                }
        }
    }
}

/** Ordered, immutable prerequisites. Reobserving the same fact does not grow the path state. */
class ValueConditions private constructor(val facts: List<ValueCondition>) {
    fun requiring(condition: ValueCondition): Refinement<ValueConditions, ValueSiteFailure> =
        when {
            !matchesBasis(condition.origin.basis) -> Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH)
            condition in facts -> Refinement.Refined(this)
            else -> Refinement.Refined(ValueConditions(Collections.unmodifiableList(facts + condition)))
        }

    fun matchesBasis(basis: SemanticReadIdentity): Boolean = facts.all { it.origin.basis == basis }

    internal fun admits(source: ValueSite, target: ValueSite, kind: ValueTransferKind): Boolean {
        if (this == target.conditions) return kind != ValueTransferKind.CAST_RESULT
        if (target.conditions.facts.size != facts.size + 1) return false
        if (target.conditions.facts.dropLast(1) != facts) return false
        val added = target.conditions.facts.last()
        return when (added) {
            is ValueCondition.StableReceiver ->
                kind == ValueTransferKind.FIELD_READ && added.origin.matches(source) && added.useRange == target.range
            is ValueCondition.CastSuccess ->
                kind == ValueTransferKind.CAST_RESULT && added.origin.matches(source) && added.useRange == target.range
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueConditions && facts == other.facts

    override fun hashCode(): Int = facts.hashCode()

    companion object {
        val Unconditional = ValueConditions(emptyList())
    }
}
