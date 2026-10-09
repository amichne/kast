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
    KOTLIN_BINARY_CONTRACT
}

enum class CallbackDependencyInvocationKind {
    EXACTLY_ONCE
}

/** Declared static calls-in-place evidence. No dependency body or runtime invocation is fabricated. */
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
            return Refinement.Refined(
                CallbackDependencyContract(
                    basis,
                    occurrence,
                    owner,
                    target,
                    position,
                    classDigest,
                    provenance,
                    invocationKind,
                )
            )
        }
    }
}

private const val SHA256_HEX_LENGTH = 64
