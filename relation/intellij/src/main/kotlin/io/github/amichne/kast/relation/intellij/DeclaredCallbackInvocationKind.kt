@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractInvocationKind

/** One declared effect belongs to one compiler-selected formal. Missing evidence never becomes UNKNOWN. */
internal fun admitDeclaredCallbackInvocationKind(
    effects: List<KaContractInvocationKind>
): Refinement<CallbackDependencyInvocationKind, CallbackInvocationFlowCause> {
    if (effects.size != 1) return Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN)
    return when (effects.single()) {
        KaContractInvocationKind.EXACTLY_ONCE -> Refinement.Refined(CallbackDependencyInvocationKind.EXACTLY_ONCE)
        KaContractInvocationKind.AT_MOST_ONCE -> Refinement.Refined(CallbackDependencyInvocationKind.AT_MOST_ONCE)
        KaContractInvocationKind.AT_LEAST_ONCE -> Refinement.Refined(CallbackDependencyInvocationKind.AT_LEAST_ONCE)
        KaContractInvocationKind.UNKNOWN -> Refinement.Refined(CallbackDependencyInvocationKind.UNKNOWN)
        KaContractInvocationKind.ZERO,
        KaContractInvocationKind.MORE_THAN_ONCE ->
            Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN)
    }
}
