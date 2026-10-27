package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** A compiler-declared result effect is independent of the calls-in-place invocation kind. */
enum class DeclaredCallbackResultEffect {
    RETURNS_RESULT_OF,
    UNSPECIFIED,
}

enum class DeclaredCallbackValueFailure {
    BASIS_MISMATCH,
    OWNER_MISMATCH,
}

/** Exact binary contract and selected source supplier; no dependency-body invocation is asserted. */
class DeclaredCallbackValueBinding
private constructor(
    val enclosing: RelationEndpoint,
    val body: RelationCallableBody.Anonymous,
    val contract: CallbackDependencyContract,
    val resultEffect: DeclaredCallbackResultEffect,
) {
    val basis = enclosing.lease.identity

    val retainedBytes: Long
        get() =
            8192L
                .addBytes(enclosing.detachedTextUnits().multiplyBytes(2))
                .addBytes(body.signature.canonicalEncoding().value.length.toLong().multiplyBytes(2))
                .addBytes(body.file.stableValue.length.toLong().multiplyBytes(2))
                .addBytes(body.compilerIdentity.value.length.toLong().multiplyBytes(2))
                .addBytes(contract.target.signature.canonicalEncoding().value.length.toLong().multiplyBytes(2))
                .addBytes(contract.target.file.stableValue.length.toLong().multiplyBytes(2))
                .addBytes(contract.target.name.value.length.toLong().multiplyBytes(2))
                .addBytes(contract.target.compilerIdentity.value.length.toLong().multiplyBytes(2))

    internal fun admitsReturn(source: ValueSite): Boolean =
        source.role == ValueRole.Return &&
            source.basis == basis &&
            source.executionOwner == ValueExecutionOwner.declaredCallback(this) &&
            resultEffect == DeclaredCallbackResultEffect.RETURNS_RESULT_OF

    internal fun resultTransfer(source: ValueSite): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
        val condition =
            when (val admitted = ValueCondition.DeclaredCallbackResult.fromCompiler(source, this)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            }
        val conditions =
            when (val admitted = source.conditions.requiring(condition)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            }
        val target =
            when (
                val admitted =
                    ValueSite.fromCompiler(
                        enclosing = enclosing,
                        range = contract.occurrence.range,
                        role = ValueRole.ExpressionResult,
                        executionOwner = ValueExecutionOwner.declaration(enclosing),
                        valuePath = source.valuePath,
                        callStack = source.callStack,
                        conditions = conditions,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            }
        return when (
            val admitted =
                ValueTransfer.fromCompiler(
                    source,
                    target,
                    ValueTransferKind.SOURCE_CALL_RETURN,
                    ValueSourceTransfer.fromCompiler(ValueSourceOperation.DeclaredCallbackResult(this)),
                )
        ) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
        }
    }

    companion object {
        /** The compiler boundary selects this anonymous argument for the contract's exact formal. */
        fun admit(
            enclosing: RelationEndpoint,
            body: RelationCallableBody.Anonymous,
            contract: CallbackDependencyContract,
            resultEffect: DeclaredCallbackResultEffect,
        ): Refinement<DeclaredCallbackValueBinding, DeclaredCallbackValueFailure> =
            when {
                contract.basis != enclosing.lease.identity ->
                    Refinement.Rejected(DeclaredCallbackValueFailure.BASIS_MISMATCH)
                enclosing.file !is SymbolDiscoveryFileIdentity.Workspace ||
                    contract.owner !is RelationCallableBody.Named ||
                    !enclosing.matchesCallbackRoot(contract.owner.evidence) ||
                    body.file != enclosing.file ||
                    contract.occurrence.file != enclosing.file ||
                    !enclosing.range.containsValueRange(contract.occurrence.range) ||
                    !contract.occurrence.range.containsValueRange(body.range) ->
                    Refinement.Rejected(DeclaredCallbackValueFailure.OWNER_MISMATCH)
                else -> Refinement.Refined(DeclaredCallbackValueBinding(enclosing, body, contract, resultEffect))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is DeclaredCallbackValueBinding &&
            enclosing.valueIdentity == other.enclosing.valueIdentity &&
            basis == other.basis &&
            body == other.body &&
            contract == other.contract &&
            resultEffect == other.resultEffect

    override fun hashCode(): Int =
        31 *
            (31 * (31 * (31 * enclosing.valueIdentity.hashCode() + basis.hashCode()) + body.hashCode()) +
                contract.hashCode()) + resultEffect.hashCode()
}
