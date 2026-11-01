package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

sealed interface ValueRole {
    data object ExpressionResult : ValueRole

    data class LoopResult(val result: ValueLoopResult) : ValueRole

    data class StringConversionResult(val invocation: ValueInvocation) : ValueRole

    data object LocalBinding : ValueRole

    data object LocalRead : ValueRole

    data class Argument(val call: ValueInvocation, val position: ValueArgumentPosition) : ValueRole

    data class Receiver(val call: ValueInvocation, val kind: ValueReceiverKind) : ValueRole

    data class Formal(val position: ValueFormalPosition) : ValueRole

    data object Return : ValueRole

    data object PropertyAssignment : ValueRole
}

data class ValueSiteIdentity
internal constructor(
    val owner: ValueDeclarationIdentity,
    val basis: SemanticReadIdentity,
    val range: ExactDeclarationTextRange,
    val role: ValueRole,
    val execution: ValueExecutionIdentity,
    val valuePath: ValueAccessPath,
    val callStack: ValueCallStack,
    val conditions: ValueConditions,
    val localVersion: ValueLocalVersion,
)

enum class ValueSiteFailure {
    ANCHOR_OUTSIDE_OWNER,
    INVOCATION_OWNER_MISMATCH,
    ARGUMENT_OUTSIDE_INVOCATION,
    INVALID_ARGUMENT_POSITION,
    EXECUTION_OWNER_MISMATCH,
    ANCHOR_OUTSIDE_EXECUTION_OWNER,
    RECEIVER_OUTSIDE_INVOCATION,
    STATE_BASIS_MISMATCH,
    CONDITIONS_NOT_PRESERVED,
    FORMAL_OWNER_MISMATCH,
}

/** Detached value occurrence. A range parse alone never establishes this native semantic fact. */
class ValueSite
private constructor(
    val enclosing: RelationEndpoint,
    val range: ExactDeclarationTextRange,
    val role: ValueRole,
    val executionOwner: ValueExecutionOwner,
    val valuePath: ValueAccessPath,
    val callStack: ValueCallStack,
    val conditions: ValueConditions,
    val localVersion: ValueLocalVersion = ValueLocalVersion.Unversioned,
) {
    val retainedBytes: Long
        get() = valueSiteStorageBytes(this)

    val basis: SemanticReadIdentity = enclosing.lease.identity
    val identity =
        ValueSiteIdentity(
            enclosing.valueDeclarationIdentity(),
            basis,
            range,
            role,
            executionOwner.identity,
            valuePath,
            callStack,
            conditions,
            localVersion,
        )

    /** Carries already admitted provenance through an occurrence without another compiler lookup. */
    fun withState(
        valuePath: ValueAccessPath = this.valuePath,
        callStack: ValueCallStack = this.callStack,
        conditions: ValueConditions = this.conditions,
    ): Refinement<ValueSite, ValueSiteFailure> =
        when {
            !valuePath.matchesBasis(basis) || !callStack.matchesBasis(basis) || !conditions.matchesBasis(basis) ->
                Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH)
            conditions.facts.take(this.conditions.facts.size) != this.conditions.facts ->
                Refinement.Rejected(ValueSiteFailure.CONDITIONS_NOT_PRESERVED)
            else ->
                Refinement.Refined(
                    ValueSite(enclosing, range, role, executionOwner, valuePath, callStack, conditions, localVersion)
                )
        }

    internal fun shareCallableEvidence(proofs: ValueFlowProofSharing): ValueSite {
        val owner = proofs.executionOwner(executionOwner)
        val sharedRole = role.shareInvocationEvidence(proofs)
        return if (sharedRole === role && owner === executionOwner) this
        else ValueSite(enclosing, range, sharedRole, owner, valuePath, callStack, conditions, localVersion)
    }

    companion object {
        internal fun invocationResult(invocation: ValueInvocation): ValueSite =
            ValueSite(
                invocation.enclosing,
                invocation.range,
                ValueRole.ExpressionResult,
                invocation.executionOwner,
                ValueAccessPath.Whole,
                ValueCallStack.Empty,
                ValueConditions.Unconditional,
            )

        /** Called after K2 confirms this role in this exact containing declaration on its admitted basis. */
        fun fromCompiler(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            role: ValueRole,
            executionOwner: ValueExecutionOwner = ValueExecutionOwner.declaration(enclosing),
            valuePath: ValueAccessPath = ValueAccessPath.Whole,
            callStack: ValueCallStack = ValueCallStack.Empty,
            conditions: ValueConditions = ValueConditions.Unconditional,
            localVersion: ValueLocalVersion = ValueLocalVersion.Unversioned,
        ): Refinement<ValueSite, ValueSiteFailure> {
            if (!localVersion.admitsSite(role, range, executionOwner))
                return Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH)
            if (!enclosing.range.containsValueRange(range))
                return Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER)
            if (!executionOwner.belongsTo(enclosing))
                return Refinement.Rejected(ValueSiteFailure.EXECUTION_OWNER_MISMATCH)
            if (!executionOwner.range.containsValueRange(range))
                return Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
            if (
                !valuePath.matchesBasis(enclosing.lease.identity) ||
                    !callStack.matchesBasis(enclosing.lease.identity) ||
                    !conditions.matchesBasis(enclosing.lease.identity)
            )
                return Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH)
            when (val admitted = admitRole(enclosing, range, role, executionOwner)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(
                ValueSite(enclosing, range, role, executionOwner, valuePath, callStack, conditions, localVersion)
            )
        }

        private fun admitRole(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            role: ValueRole,
            executionOwner: ValueExecutionOwner,
        ): Refinement<Unit, ValueSiteFailure> =
            when (role) {
                is ValueRole.StringConversionResult -> role.admitResult(enclosing, range, executionOwner)
                is ValueRole.LoopResult ->
                    if (
                        role.result.invocation.matchesOwner(enclosing, executionOwner) &&
                            role.result.invocation.range == range
                    )
                        Refinement.Refined(Unit)
                    else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                is ValueRole.Argument ->
                    when {
                        !role.call.matchesOwner(enclosing, executionOwner) ->
                            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                        !role.call.range.containsValueRange(range) ->
                            Refinement.Rejected(ValueSiteFailure.ARGUMENT_OUTSIDE_INVOCATION)
                        role.position.value !in role.call.operation.valueParameters.indices ->
                            Refinement.Rejected(ValueSiteFailure.INVALID_ARGUMENT_POSITION)
                        else -> Refinement.Refined(Unit)
                    }
                is ValueRole.Receiver ->
                    when {
                        !role.call.matchesOwner(enclosing, executionOwner) ->
                            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                        !role.call.range.containsValueRange(range) ->
                            Refinement.Rejected(ValueSiteFailure.RECEIVER_OUTSIDE_INVOCATION)
                        else -> Refinement.Refined(Unit)
                    }
                is ValueRole.Formal -> admitFormal(role.position, range, executionOwner)
                ValueRole.ExpressionResult,
                ValueRole.LocalBinding,
                ValueRole.LocalRead,
                ValueRole.Return,
                ValueRole.PropertyAssignment -> Refinement.Refined(Unit)
            }

        private fun ValueRole.StringConversionResult.admitResult(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            executionOwner: ValueExecutionOwner,
        ): Refinement<Unit, ValueSiteFailure> =
            if (invocation.matchesOwner(enclosing, executionOwner) && invocation.range == range)
                Refinement.Refined(Unit)
            else Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)

        private fun admitFormal(
            position: ValueFormalPosition,
            range: ExactDeclarationTextRange,
            executionOwner: ValueExecutionOwner,
        ): Refinement<Unit, ValueSiteFailure> =
            when (position) {
                is ValueFormalPosition.Parameter ->
                    if (position.parameter.executionOwner == executionOwner && position.parameter.range == range)
                        Refinement.Refined(Unit)
                    else Refinement.Rejected(ValueSiteFailure.FORMAL_OWNER_MISMATCH)
                is ValueFormalPosition.Receiver -> Refinement.Refined(Unit)
            }

        private fun ValueInvocation.matchesOwner(
            enclosing: RelationEndpoint,
            executionOwner: ValueExecutionOwner,
        ): Boolean =
            this.enclosing.valueDeclarationIdentity() == enclosing.valueDeclarationIdentity() &&
                basis == enclosing.lease.identity &&
                this.executionOwner == executionOwner
    }

    override fun equals(other: Any?): Boolean = other is ValueSite && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}

internal fun ExactDeclarationTextRange.containsValueRange(other: ExactDeclarationTextRange): Boolean =
    other.startInclusive >= startInclusive && other.endExclusive <= endExclusive

/** Search policy qualifies coverage; it is never part of a declaration or invocation's identity. */
private fun RelationEndpoint.valueDeclarationIdentity(): ValueDeclarationIdentity = valueIdentity

private fun ValueRole.shareInvocationEvidence(proofs: ValueFlowProofSharing): ValueRole =
    when (val current = this) {
        is ValueRole.StringConversionResult -> {
            val shared = current.invocation.shareCallableEvidence(proofs)
            if (shared === current.invocation) current else ValueRole.StringConversionResult(shared)
        }
        is ValueRole.Argument -> {
            val shared = current.call.shareCallableEvidence(proofs)
            if (shared === current.call) current else ValueRole.Argument(shared, current.position)
        }
        is ValueRole.Receiver -> {
            val shared = current.call.shareCallableEvidence(proofs)
            if (shared === current.call) current else ValueRole.Receiver(shared, current.kind)
        }
        is ValueRole.Formal,
        is ValueRole.LoopResult,
        ValueRole.ExpressionResult,
        ValueRole.LocalBinding,
        ValueRole.LocalRead,
        ValueRole.Return,
        ValueRole.PropertyAssignment -> current
    }
