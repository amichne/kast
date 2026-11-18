package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Kotlin 2.4.10 _Arrays.kt invokes action for each element within the selected call. */
class ReviewedIntArrayIteration
private constructor(
    val target: CompilerGroundedSymbolEvidence,
    val position: ValueArgumentPosition,
    val classDigest: DependencyClassDigest,
) {
    companion object {
        private const val BINARY = "4ec0293bc3751423b203f1d8493251c57c42e73eb6377a6b8560d0974ff0a6df"
        private const val FACADE = "872436d952bd6216482465e7213427dde8afe9f7d57cdfd3093bb1fcaf3341eb"

        fun admit(
            target: CompilerGroundedSymbolEvidence,
            position: ValueArgumentPosition,
            classDigest: DependencyClassDigest,
            archive: DependencyArtifactDigest,
        ): Refinement<ReviewedIntArrayIteration, CallbackInvocationFlowCause> {
            val signature = target.signature as? CanonicalCompilerSignature.Function
            val file = target.file as? SymbolDiscoveryFileIdentity.External
            if (archive.value != BINARY || file?.matchesClass(classDigest) != true)
                return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN)
            if (position.value != 0 || signature?.isIntArrayIteration() != true)
                return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN)
            return Refinement.Refined(ReviewedIntArrayIteration(target, position, classDigest))
        }

        private fun CanonicalCompilerSignature.Function.isIntArrayIteration(): Boolean =
            qualifiedIdentity.value == "kotlin.collections.forEach" &&
                (receiver as? CanonicalCompilerReceiver.Present)?.type?.value == "kotlin/IntArray" &&
                contextReceivers.isEmpty() &&
                typeParameterCount.value == 0 &&
                valueParameters.singleOrNull()?.value == "(kotlin/Int)->kotlin/Unit"

        private fun SymbolDiscoveryFileIdentity.External.matchesClass(digest: DependencyClassDigest): Boolean =
            digest.value == FACADE && url.value.endsWith("!/kotlin/collections/ArraysKt.class")
    }
}
