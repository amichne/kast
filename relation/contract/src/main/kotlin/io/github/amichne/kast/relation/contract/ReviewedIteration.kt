package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** The Kotlin 2.4.10 implementations call the selected action within their loop. */
class ReviewedIteration
private constructor(
    val target: CompilerGroundedSymbolEvidence,
    val position: ValueArgumentPosition,
    val classDigest: DependencyClassDigest,
    val provenance: CallbackDependencyContractProvenance,
) {
    companion object {
        private const val BINARY = "4ec0293bc3751423b203f1d8493251c57c42e73eb6377a6b8560d0974ff0a6df"

        fun admit(
            target: CompilerGroundedSymbolEvidence,
            position: ValueArgumentPosition,
            classDigest: DependencyClassDigest,
            archive: DependencyArtifactDigest,
        ): Refinement<ReviewedIteration, CallbackInvocationFlowCause> {
            if (archive.value != BINARY || position.value != 0) return unproven()
            val signature = target.signature as? CanonicalCompilerSignature.Function ?: return unproven()
            val file = target.file as? SymbolDiscoveryFileIdentity.External ?: return unproven()
            if (
                signature.qualifiedIdentity.value != "kotlin.collections.forEach" ||
                    signature.contextReceivers.isNotEmpty()
            )
                return unproven()
            val model =
                IterationModel.entries.singleOrNull { it.matches(signature, file, classDigest) } ?: return unproven()
            return Refinement.Refined(
                ReviewedIteration(
                    target = target,
                    position = position,
                    classDigest = classDigest,
                    provenance = model.provenance,
                )
            )
        }

        private fun unproven() = Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN)
    }
}

private enum class IterationModel(
    val provenance: CallbackDependencyContractProvenance,
    val receiver: String,
    val formal: String,
    val typeParameters: Int,
    val facade: String,
    val digest: String,
) {
    INT_ARRAY(
        CallbackDependencyContractProvenance.REVIEWED_KOTLIN_2_4_10_INT_ARRAY_FOR_EACH,
        "kotlin/IntArray",
        "(kotlin/Int)->kotlin/Unit",
        0,
        "ArraysKt.class",
        "872436d952bd6216482465e7213427dde8afe9f7d57cdfd3093bb1fcaf3341eb",
    ),
    ITERABLE(
        CallbackDependencyContractProvenance.REVIEWED_KOTLIN_2_4_10_ITERABLE_FOR_EACH,
        "kotlin/collections/Iterable<T>",
        "(T)->kotlin/Unit",
        1,
        "CollectionsKt.class",
        "7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878",
    );

    fun matches(
        signature: CanonicalCompilerSignature.Function,
        file: SymbolDiscoveryFileIdentity.External,
        classDigest: DependencyClassDigest,
    ): Boolean {
        if (classDigest.value != digest || !file.url.value.endsWith("!/kotlin/collections/$facade")) return false
        return (signature.receiver as? CanonicalCompilerReceiver.Present)?.type?.value == receiver &&
            signature.typeParameterCount.value == typeParameters &&
            signature.valueParameters.singleOrNull()?.value == formal
    }
}
