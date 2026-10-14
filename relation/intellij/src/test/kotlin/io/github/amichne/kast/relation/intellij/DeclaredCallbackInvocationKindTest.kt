@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractInvocationKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeclaredCallbackInvocationKindTest {
    @Test
    fun `each declared stdlib effect keeps its exact kind`() {
        val expected =
            mapOf(
                KaContractInvocationKind.EXACTLY_ONCE to CallbackDependencyInvocationKind.EXACTLY_ONCE,
                KaContractInvocationKind.AT_MOST_ONCE to CallbackDependencyInvocationKind.AT_MOST_ONCE,
                KaContractInvocationKind.AT_LEAST_ONCE to CallbackDependencyInvocationKind.AT_LEAST_ONCE,
                KaContractInvocationKind.UNKNOWN to CallbackDependencyInvocationKind.UNKNOWN,
            )
        expected.forEach { (native, kind) ->
            assertEquals(Refinement.Refined(kind), admitDeclaredCallbackInvocationKind(listOf(native)))
        }
    }

    @Test
    fun `missing duplicate and contradictory effects cannot establish an invocation kind`() {
        for (effects in
            listOf(
                emptyList(),
                listOf(KaContractInvocationKind.EXACTLY_ONCE, KaContractInvocationKind.EXACTLY_ONCE),
                listOf(KaContractInvocationKind.EXACTLY_ONCE, KaContractInvocationKind.AT_MOST_ONCE),
                listOf(KaContractInvocationKind.ZERO),
                listOf(KaContractInvocationKind.MORE_THAN_ONCE),
            )) {
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN),
                admitDeclaredCallbackInvocationKind(effects),
            )
        }
    }
}
