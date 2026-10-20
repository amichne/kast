package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

enum class ValueArgumentPositionFailure {
    NEGATIVE
}

/** A resolved formal parameter position, independent of the lexical argument order. */
@JvmInline
value class ValueArgumentPosition private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<ValueArgumentPosition, ValueArgumentPositionFailure> =
            if (raw < 0) Refinement.Rejected(ValueArgumentPositionFailure.NEGATIVE)
            else Refinement.Refined(ValueArgumentPosition(raw))
    }
}

/** Invocation identity includes its enclosing declaration and exact anchor, never just its callable. */
data class ValueDeclarationIdentity
internal constructor(
    val compiler: CompilerSymbolIdentity,
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
) {
    companion object {
        fun fromCompiler(
            evidence: io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
        ): ValueDeclarationIdentity = ValueDeclarationIdentity(evidence.compilerIdentity, evidence.file, evidence.range)
    }
}

data class ValueInvocationIdentity
internal constructor(
    val owner: ValueDeclarationIdentity,
    val basis: SemanticReadIdentity,
    val range: ExactDeclarationTextRange,
    val callable: ValueDeclarationIdentity,
    val execution: ValueExecutionIdentity,
)

enum class ValueInvocationFailure {
    BASIS_MISMATCH,
    ANCHOR_OUTSIDE_OWNER,
    NOT_CALLABLE,
    EXECUTION_OWNER_MISMATCH,
    ANCHOR_OUTSIDE_EXECUTION_OWNER,
}

class ValueInvocation
private constructor(
    val enclosing: RelationEndpoint,
    val range: ExactDeclarationTextRange,
    val callable: RelationEndpoint,
    val executionOwner: ValueExecutionOwner,
    val signature: CanonicalCompilerCallableSignature,
) {
    val basis: SemanticReadIdentity = enclosing.lease.identity
    val identity =
        ValueInvocationIdentity(
            enclosing.valueDeclarationIdentity(),
            basis,
            range,
            callable.valueDeclarationIdentity(),
            executionOwner.identity,
        )

    /** The exact native-confirmed invocation carries its result anchor; deriving it performs no semantic work. */
    fun resultSite(): ValueSite = ValueSite.invocationResult(this)

    internal fun shareCallableEvidence(
        callables: MutableMap<RelationEndpoint.Resolved, RelationEndpoint.Resolved>
    ): ValueInvocation {
        val resolved = callable as? RelationEndpoint.Resolved ?: return this
        val shared = callables.getOrPut(resolved) { resolved }
        // Full endpoint equality includes authority, scope, constraints and every compiler evidence field.
        return if (shared === callable) this else ValueInvocation(enclosing, range, shared, executionOwner, signature)
    }

    companion object {
        /** Only the native K2 boundary may supply resolved call and enclosing declaration evidence. */
        fun fromCompiler(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            callable: RelationEndpoint,
            executionOwner: ValueExecutionOwner = ValueExecutionOwner.declaration(enclosing),
        ): Refinement<ValueInvocation, ValueInvocationFailure> {
            val signature = callable.signature
            return when {
                enclosing.lease.identity != callable.lease.identity ->
                    Refinement.Rejected(ValueInvocationFailure.BASIS_MISMATCH)
                !enclosing.range.containsValueRange(range) ->
                    Refinement.Rejected(ValueInvocationFailure.ANCHOR_OUTSIDE_OWNER)
                signature !is CanonicalCompilerCallableSignature ->
                    Refinement.Rejected(ValueInvocationFailure.NOT_CALLABLE)
                !executionOwner.belongsTo(enclosing) ->
                    Refinement.Rejected(ValueInvocationFailure.EXECUTION_OWNER_MISMATCH)
                !executionOwner.range.containsValueRange(range) ->
                    Refinement.Rejected(ValueInvocationFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
                else -> Refinement.Refined(ValueInvocation(enclosing, range, callable, executionOwner, signature))
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueInvocation && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}

sealed interface ValueRole {
    data object ExpressionResult : ValueRole

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
        )

    /** Carries already admitted provenance through an occurrence without another compiler lookup. */
    fun withState(
        valuePath: ValueAccessPath = this.valuePath,
        callStack: ValueCallStack = this.callStack,
    ): Refinement<ValueSite, ValueSiteFailure> =
        if (!valuePath.matchesBasis(basis) || !callStack.matchesBasis(basis))
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH)
        else Refinement.Refined(ValueSite(enclosing, range, role, executionOwner, valuePath, callStack))

    internal fun shareCallableEvidence(
        callables: MutableMap<RelationEndpoint.Resolved, RelationEndpoint.Resolved>
    ): ValueSite =
        when (val current = role) {
            is ValueRole.Argument -> {
                val shared = current.call.shareCallableEvidence(callables)
                if (shared === current.call) this
                else
                    ValueSite(
                        enclosing,
                        range,
                        ValueRole.Argument(shared, current.position),
                        executionOwner,
                        valuePath,
                        callStack,
                    )
            }
            is ValueRole.Receiver -> {
                val shared = current.call.shareCallableEvidence(callables)
                if (shared === current.call) this
                else
                    ValueSite(
                        enclosing,
                        range,
                        ValueRole.Receiver(shared, current.kind),
                        executionOwner,
                        valuePath,
                        callStack,
                    )
            }
            is ValueRole.Formal,
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> this
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
            )

        /** Called after K2 confirms this role in this exact containing declaration on its admitted basis. */
        fun fromCompiler(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            role: ValueRole,
            executionOwner: ValueExecutionOwner = ValueExecutionOwner.declaration(enclosing),
            valuePath: ValueAccessPath = ValueAccessPath.Whole,
            callStack: ValueCallStack = ValueCallStack.Empty,
        ): Refinement<ValueSite, ValueSiteFailure> {
            if (!enclosing.range.containsValueRange(range))
                return Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER)
            if (!executionOwner.belongsTo(enclosing))
                return Refinement.Rejected(ValueSiteFailure.EXECUTION_OWNER_MISMATCH)
            if (!executionOwner.range.containsValueRange(range))
                return Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
            if (!valuePath.matchesBasis(enclosing.lease.identity) || !callStack.matchesBasis(enclosing.lease.identity))
                return Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH)
            when (val admitted = admitRole(enclosing, range, role, executionOwner)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            return Refinement.Refined(ValueSite(enclosing, range, role, executionOwner, valuePath, callStack))
        }

        private fun admitRole(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            role: ValueRole,
            executionOwner: ValueExecutionOwner,
        ): Refinement<Unit, ValueSiteFailure> =
            when (role) {
                is ValueRole.Argument ->
                    when {
                        !role.call.matchesOwner(enclosing, executionOwner) ->
                            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                        !role.call.range.containsValueRange(range) ->
                            Refinement.Rejected(ValueSiteFailure.ARGUMENT_OUTSIDE_INVOCATION)
                        role.position.value !in role.call.signature.valueParameters.indices ->
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
