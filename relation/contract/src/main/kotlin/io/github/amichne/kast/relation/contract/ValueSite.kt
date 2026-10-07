package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
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
)

enum class ValueInvocationFailure {
    BASIS_MISMATCH,
    ANCHOR_OUTSIDE_OWNER,
    NOT_CALLABLE,
}

class ValueInvocation
private constructor(
    val enclosing: RelationEndpoint,
    val range: ExactDeclarationTextRange,
    val callable: RelationEndpoint,
) {
    val basis: SemanticReadIdentity = enclosing.lease.identity
    val identity =
        ValueInvocationIdentity(enclosing.valueDeclarationIdentity(), basis, range, callable.valueDeclarationIdentity())

    /** The exact native-confirmed invocation carries its result anchor; deriving it performs no semantic work. */
    fun resultSite(): ValueSite = ValueSite.invocationResult(this)

    internal fun shareCallableEvidence(
        callables: MutableMap<RelationEndpoint.Resolved, RelationEndpoint.Resolved>
    ): ValueInvocation {
        val resolved = callable as? RelationEndpoint.Resolved ?: return this
        val shared = callables.getOrPut(resolved) { resolved }
        // Full endpoint equality includes authority, scope, constraints and every compiler evidence field.
        return if (shared === resolved) this else ValueInvocation(enclosing, range, shared)
    }

    companion object {
        /** Only the native K2 boundary may supply resolved call and enclosing declaration evidence. */
        fun fromCompiler(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            callable: RelationEndpoint,
        ): Refinement<ValueInvocation, ValueInvocationFailure> =
            when {
                enclosing.lease.identity != callable.lease.identity ->
                    Refinement.Rejected(ValueInvocationFailure.BASIS_MISMATCH)
                !enclosing.range.containsValueRange(range) ->
                    Refinement.Rejected(ValueInvocationFailure.ANCHOR_OUTSIDE_OWNER)
                callable.signature !is CanonicalCompilerSignature.Function ->
                    Refinement.Rejected(ValueInvocationFailure.NOT_CALLABLE)
                else -> Refinement.Refined(ValueInvocation(enclosing, range, callable))
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

    data object Return : ValueRole

    data object PropertyAssignment : ValueRole
}

data class ValueSiteIdentity
internal constructor(
    val owner: ValueDeclarationIdentity,
    val basis: SemanticReadIdentity,
    val range: ExactDeclarationTextRange,
    val role: ValueRole,
)

enum class ValueSiteFailure {
    ANCHOR_OUTSIDE_OWNER,
    INVOCATION_OWNER_MISMATCH,
    ARGUMENT_OUTSIDE_INVOCATION,
    INVALID_ARGUMENT_POSITION,
}

/** Detached value occurrence. A range parse alone never establishes this native semantic fact. */
class ValueSite
private constructor(
    val enclosing: RelationEndpoint,
    val range: ExactDeclarationTextRange,
    val role: ValueRole,
) {
    val retainedBytes: Long
        get() = valueSiteStorageBytes(this)

    val basis: SemanticReadIdentity = enclosing.lease.identity
    val identity = ValueSiteIdentity(enclosing.valueDeclarationIdentity(), basis, range, role)

    internal fun shareCallableEvidence(
        callables: MutableMap<RelationEndpoint.Resolved, RelationEndpoint.Resolved>
    ): ValueSite =
        when (val current = role) {
            is ValueRole.Argument -> {
                val shared = current.call.shareCallableEvidence(callables)
                if (shared === current.call) this
                else ValueSite(enclosing, range, ValueRole.Argument(shared, current.position))
            }
            ValueRole.ExpressionResult,
            ValueRole.LocalBinding,
            ValueRole.LocalRead,
            ValueRole.Return,
            ValueRole.PropertyAssignment -> this
        }

    companion object {
        internal fun invocationResult(invocation: ValueInvocation): ValueSite =
            ValueSite(invocation.enclosing, invocation.range, ValueRole.ExpressionResult)

        /** Called after K2 confirms this role in this exact containing declaration on its admitted basis. */
        fun fromCompiler(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            role: ValueRole,
        ): Refinement<ValueSite, ValueSiteFailure> {
            if (!enclosing.range.containsValueRange(range))
                return Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER)
            when (role) {
                is ValueRole.Argument -> {
                    if (
                        role.call.enclosing.valueDeclarationIdentity() != enclosing.valueDeclarationIdentity() ||
                            role.call.basis != enclosing.lease.identity
                    )
                        return Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH)
                    if (!role.call.range.containsValueRange(range))
                        return Refinement.Rejected(ValueSiteFailure.ARGUMENT_OUTSIDE_INVOCATION)
                    val signature = role.call.callable.signature as CanonicalCompilerSignature.Function
                    if (role.position.value !in signature.valueParameters.indices)
                        return Refinement.Rejected(ValueSiteFailure.INVALID_ARGUMENT_POSITION)
                }
                ValueRole.ExpressionResult,
                ValueRole.LocalBinding,
                ValueRole.LocalRead,
                ValueRole.Return,
                ValueRole.PropertyAssignment -> Unit
            }
            return Refinement.Refined(ValueSite(enclosing, range, role))
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueSite && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}

internal fun ExactDeclarationTextRange.containsValueRange(other: ExactDeclarationTextRange): Boolean =
    other.startInclusive >= startInclusive && other.endExclusive <= endExclusive

/** Search policy qualifies coverage; it is never part of a declaration or invocation's identity. */
private fun RelationEndpoint.valueDeclarationIdentity(): ValueDeclarationIdentity = valueIdentity
