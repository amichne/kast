package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** The language rule describes a conservative dependency on either operand on normal completion. */
enum class IntArithmeticRule(val callableId: String) {
    INT_MINUS_V1("kotlin.Int.minus"),
    INT_PLUS_V1("kotlin.Int.plus"),
    INT_TIMES_V1("kotlin.Int.times");

    internal val signature: CanonicalCompilerSignature =
        when (
            val shape =
                CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = callableId,
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("kotlin/Int"),
                    rawTypeParameterCount = 0,
                )
        ) {
            is Refinement.Refined -> shape.value
            is Refinement.Rejected -> error("Invalid builtin rule signature")
        }
}

enum class IntArithmeticInput {
    DISPATCH_RECEIVER,
    ARGUMENT_0,
}

enum class IntBuiltinEvidence(val moduleKind: SourceLessCallableModuleKind) {
    K2_BUILTINS_MODULE(SourceLessCallableModuleKind.BUILTINS),
    K2_BUILTINS_METADATA(SourceLessCallableModuleKind.LIBRARY);

    internal fun admits(callable: SourceLessCallable): Boolean =
        callable.kind == CompilerSymbolKind.FUNCTION &&
            callable.origin == SourceLessCallableOrigin.LIBRARY &&
            moduleKind == callable.moduleKind
}

/** Selection comes from K2. The versioned rule supplies the dependency semantics. */
@ConsistentCopyVisibility
data class ValueIntArithmetic
private constructor(
    val callable: SourceLessCallable,
    val authority: IntBuiltinEvidence,
    val rule: IntArithmeticRule,
    val input: IntArithmeticInput,
    val inputRange: ExactDeclarationTextRange,
    val resultRange: ExactDeclarationTextRange,
    val execution: ValueExecutionOwner,
) : ValueTransferEvidence {
    val basis
        get() = execution.basis

    internal val storageBytes: Long
        get() =
            2048L
                .addBytes(valueExecutionStorageBytes(execution))
                .addBytes(callable.moduleName.value.length.toLong().multiplyBytes(2))
                .addBytes(callable.signature.canonicalEncoding().value.length.toLong().multiplyBytes(2))

    internal fun admitsArithmetic(source: ValueSite, target: ValueSite, kind: ValueTransferKind): Boolean =
        kind == ValueTransferKind.PRIMITIVE_DERIVATION &&
            source.executionOwner == execution &&
            target.executionOwner == execution &&
            source.identity.owner == target.identity.owner &&
            source.range == inputRange &&
            target.range == resultRange &&
            source.valuePath.segments.isEmpty() &&
            target.valuePath.segments.isEmpty() &&
            source.callStack == target.callStack

    internal fun shareCallableEvidence(proofs: ValueFlowProofSharing): ValueIntArithmetic {
        val sharedExecution = proofs.executionOwner(execution)
        val sharedCallable = proofs.builtinCallable(callable)
        return if (execution === sharedExecution && callable === sharedCallable) this
        else copy(execution = sharedExecution, callable = sharedCallable)
    }

    companion object {
        fun fromCompiler(
            callable: SourceLessCallable,
            authority: IntBuiltinEvidence,
            input: IntArithmeticInput,
            inputRange: ExactDeclarationTextRange,
            resultRange: ExactDeclarationTextRange,
            execution: ValueExecutionOwner,
        ): Refinement<ValueIntArithmetic, ValueTransferFailure> {
            val rule =
                IntArithmeticRule.entries.singleOrNull { it.signature == callable.signature }
                    ?: return Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            if (!authority.admits(callable) || inputRange == resultRange || !resultRange.containsValueRange(inputRange))
                return Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
            return Refinement.Refined(
                ValueIntArithmetic(callable, authority, rule, input, inputRange, resultRange, execution)
            )
        }
    }
}
