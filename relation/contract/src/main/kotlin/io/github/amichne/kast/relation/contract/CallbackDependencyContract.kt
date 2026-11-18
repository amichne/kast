package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

/** Content identity for the exact bounded binary class supplying the compiler contract. */
@JvmInline
value class DependencyClassDigest private constructor(val value: String) {
    companion object {
        fun parse(value: String): Refinement<DependencyClassDigest, CallbackInvocationFlowFailure> =
            if (value.length == SHA256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' })
                Refinement.Refined(DependencyClassDigest(value))
            else Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
    }
}

enum class CallbackDependencyContractProvenance {
    KOTLIN_BINARY_CONTRACT,
    REVIEWED_KOTLIN_2_4_10_INT_ARRAY_FOR_EACH,
    REVIEWED_KOTLIN_2_4_10_ITERABLE_FOR_EACH,
}

enum class CallbackDependencyInvocationKind {
    EXACTLY_ONCE,
    AT_MOST_ONCE,
    AT_LEAST_ONCE,
    UNKNOWN,
    ZERO_OR_MORE,
}

/** Static in-place evidence for the selected dependency parameter. Provenance identifies its authority. */
@ConsistentCopyVisibility
data class CallbackDependencyContract
private constructor(
    val basis: SemanticReadIdentity,
    val occurrence: RelationOccurrence,
    val owner: RelationCallableBody,
    val target: CompilerGroundedSymbolEvidence,
    val position: ValueArgumentPosition,
    val classDigest: DependencyClassDigest,
    val provenance: CallbackDependencyContractProvenance,
    val invocationKind: CallbackDependencyInvocationKind,
) {
    private fun admit(): Refinement<CallbackDependencyContract, CallbackInvocationFlowFailure> {
        if (occurrence.file != owner.file || !owner.range.containsValueRange(occurrence.range))
            return Refinement.Rejected(CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_OWNER)
        val signature =
            target.signature as? CanonicalCompilerSignature.Function
                ?: return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
        val file =
            target.file as? SymbolDiscoveryFileIdentity.External
                ?: return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
        if (
            !file.url.value.startsWith("jar://") ||
                !file.url.value.endsWith(".class") ||
                position.value !in signature.valueParameters.indices
        )
            return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
        return Refinement.Refined(this)
    }

    companion object {
        fun fromCompiler(
            basis: SemanticReadIdentity,
            occurrence: RelationOccurrence,
            owner: RelationCallableBody,
            target: CompilerGroundedSymbolEvidence,
            position: ValueArgumentPosition,
            classDigest: DependencyClassDigest,
            provenance: CallbackDependencyContractProvenance,
            invocationKind: CallbackDependencyInvocationKind,
        ): Refinement<CallbackDependencyContract, CallbackInvocationFlowFailure> {
            if (
                provenance != CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT ||
                    invocationKind == CallbackDependencyInvocationKind.ZERO_OR_MORE
            )
                return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT)
            return CallbackDependencyContract(
                    basis = basis,
                    occurrence = occurrence,
                    owner = owner,
                    target = target,
                    position = position,
                    classDigest = classDigest,
                    provenance = provenance,
                    invocationKind = invocationKind,
                )
                .admit()
        }

        fun fromReviewedIteration(
            basis: SemanticReadIdentity,
            occurrence: RelationOccurrence,
            owner: RelationCallableBody,
            operation: ReviewedIteration,
        ): Refinement<CallbackDependencyContract, CallbackInvocationFlowFailure> =
            CallbackDependencyContract(
                    basis = basis,
                    occurrence = occurrence,
                    owner = owner,
                    target = operation.target,
                    position = operation.position,
                    classDigest = operation.classDigest,
                    provenance = operation.provenance,
                    invocationKind = CallbackDependencyInvocationKind.ZERO_OR_MORE,
                )
                .admit()
    }
}

private const val SHA256_HEX_LENGTH = 64
