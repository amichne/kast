package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
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
    val operation: ValueInvocationOperation,
) {
    val signature: CanonicalCompilerSignature
        get() = operation.signature

    val basis: SemanticReadIdentity = enclosing.lease.identity
    val identity =
        ValueInvocationIdentity(
            enclosing.valueIdentity,
            basis,
            range,
            callable.valueIdentity,
            executionOwner.identity,
        )

    /** The exact native-confirmed invocation carries its result anchor; deriving it performs no semantic work. */
    fun resultSite(): ValueSite = ValueSite.invocationResult(this)

    internal fun shareCallableEvidence(proofs: ValueFlowProofSharing): ValueInvocation {
        val shared = proofs.callable(this)
        val owner = proofs.executionOwner(executionOwner)
        return if (shared.endpoint === callable && shared.signature === signature && owner === executionOwner) this
        else ValueInvocation(enclosing, range, shared.endpoint, owner, shared.operation)
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
            return if (signature is CanonicalCompilerCallableSignature)
                create(
                    enclosing = enclosing,
                    range = range,
                    callable = callable,
                    executionOwner = executionOwner,
                    operation = ValueInvocationOperation.Function(signature),
                )
            else Refinement.Rejected(ValueInvocationFailure.NOT_CALLABLE)
        }

        /** The native boundary must verify a selected property read and its getter, never a setter. */
        fun getterFromCompiler(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            callable: RelationEndpoint,
            executionOwner: ValueExecutionOwner = ValueExecutionOwner.declaration(enclosing),
        ): Refinement<ValueInvocation, ValueInvocationFailure> {
            val signature = callable.signature
            return if (signature is CanonicalCompilerSignature.Property)
                create(
                    enclosing = enclosing,
                    range = range,
                    callable = callable,
                    executionOwner = executionOwner,
                    operation = ValueInvocationOperation.Getter(signature),
                )
            else Refinement.Rejected(ValueInvocationFailure.NOT_CALLABLE)
        }

        private fun create(
            enclosing: RelationEndpoint,
            range: ExactDeclarationTextRange,
            callable: RelationEndpoint,
            executionOwner: ValueExecutionOwner,
            operation: ValueInvocationOperation,
        ): Refinement<ValueInvocation, ValueInvocationFailure> {
            return when {
                enclosing.lease.identity != callable.lease.identity ->
                    Refinement.Rejected(ValueInvocationFailure.BASIS_MISMATCH)
                !enclosing.range.containsValueRange(range) ->
                    Refinement.Rejected(ValueInvocationFailure.ANCHOR_OUTSIDE_OWNER)
                !executionOwner.belongsTo(enclosing) ->
                    Refinement.Rejected(ValueInvocationFailure.EXECUTION_OWNER_MISMATCH)
                !executionOwner.range.containsValueRange(range) ->
                    Refinement.Rejected(ValueInvocationFailure.ANCHOR_OUTSIDE_EXECUTION_OWNER)
                else -> Refinement.Refined(ValueInvocation(enclosing, range, callable, executionOwner, operation))
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is ValueInvocation && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()
}
