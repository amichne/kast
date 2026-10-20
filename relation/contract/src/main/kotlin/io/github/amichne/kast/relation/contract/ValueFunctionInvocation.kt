package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

sealed interface ValueCallIdentity

/** Source calls and builtin function invocations preserve different callable evidence. */
sealed interface ValueCallOccurrence {
    val enclosing: RelationEndpoint
    val range: ExactDeclarationTextRange
    val executionOwner: ValueExecutionOwner
    val basis: SemanticReadIdentity
    val signature: CanonicalCompilerCallableSignature
    val identity: ValueCallIdentity

    fun resultSite(): ValueSite = ValueSite.invocationResult(this)
}

data class ValueFunctionInvocationIdentity
internal constructor(
    val owner: ValueDeclarationIdentity,
    val basis: SemanticReadIdentity,
    val range: ExactDeclarationTextRange,
    val callable: SourceLessCallable,
    val execution: ValueExecutionIdentity,
) : ValueCallIdentity

enum class ValueFunctionInvocationFailure {
    NOT_BUILTIN_INVOKE,
    OWNER_MISMATCH,
    ANCHOR_OUTSIDE_EXECUTION_OWNER,
}

class ValueFunctionInvocation
private constructor(
    override val enclosing: RelationEndpoint,
    override val range: ExactDeclarationTextRange,
    val callable: SourceLessCallable,
    override val executionOwner: ValueExecutionOwner,
    override val signature: CanonicalCompilerSignature.Function,
) : ValueCallOccurrence {
    override val basis = enclosing.lease.identity
    override val identity =
        ValueFunctionInvocationIdentity(enclosing.valueIdentity, basis, range, callable, executionOwner.identity)

    companion object {
        fun fromCompiler(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            callable: SourceLessCallable,
            executionOwner: ValueExecutionOwner = ValueExecutionOwner.declaration(enclosing),
        ): Refinement<ValueFunctionInvocation, ValueFunctionInvocationFailure> {
            val signature =
                callable.signature as? CanonicalCompilerSignature.Function
                    ?: return Refinement.Rejected(ValueFunctionInvocationFailure.NOT_BUILTIN_INVOKE)
            val arity = signature.valueParameters.size
            if (
                signature.qualifiedIdentity.value != "kotlin.Function$arity.invoke" ||
                    callable.origin != SourceLessCallableOrigin.LIBRARY
            )
                return Refinement.Rejected(ValueFunctionInvocationFailure.NOT_BUILTIN_INVOKE)
            if (!executionOwner.belongsTo(enclosing))
                return Refinement.Rejected(ValueFunctionInvocationFailure.OWNER_MISMATCH)
            if (!executionOwner.range.containsValueRange(range))
                return Refinement.Rejected(ValueFunctionInvocationFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
            return Refinement.Refined(ValueFunctionInvocation(enclosing, range, callable, executionOwner, signature))
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueFunctionInvocation && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}
