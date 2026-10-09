@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackDefaultBinding
import io.github.amichne.kast.relation.contract.CallbackDirectInvocationBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Declared defaults and direct invocation have no fabricated explicit-argument mapping. */
internal class IntellijCallbackDeclaredSupplyReader(private val context: IntellijCallbackFlowContext) {
    fun prepareDefault(supply: IntellijCallbackLambdaSupply.DefaultParameter): CallbackBindingPreparation {
        val function =
            supply.parameter.parent.parent as? KtNamedFunction
                ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val position =
            when (val parsed = ValueArgumentPosition.parse(function.valueParameters.indexOf(supply.parameter))) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        val file =
            function.containingFile.virtualFile ?: return unavailable(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        if (!context.scope.nativeScope.contains(file)) return unavailable(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return unavailable(allowed.failure)
        }
        if (!defaultIsCallable(supply.parameter))
            return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val endpoint =
            when (val admitted = context.target(function)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unavailable(admitted.failure)
            }
        val parameter =
            context.occurrence(supply.parameter)
                ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val occurrence =
            context.occurrence(supply.expression)
                ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val identity =
            when (val admitted = CallbackParameterIdentity.fromCompiler(endpoint, position, parameter)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        return when (val admitted = CallbackDefaultBinding.fromCompiler(identity, occurrence)) {
            is Refinement.Refined ->
                CallbackBindingPreparation.Prepared(
                    PreparedCallbackFlow(
                        function,
                        supply.parameter,
                        endpoint,
                        PreparedCallbackOrigin.Default(admitted.value),
                    )
                )
            is Refinement.Rejected -> CallbackBindingPreparation.ContractRejected(admitted.failure)
        }
    }

    fun prepareDirect(supply: IntellijCallbackLambdaSupply.Invocation): CallbackBindingPreparation {
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return unavailable(allowed.failure)
        }
        if (!context.confirmsFunctionInvoke(supply.call))
            return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val occurrence =
            context.occurrence(supply.call.valueInvocationExpression())
                ?: return unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val owner =
            context.owner(supply.call) ?: return unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        return when (
            val admitted =
                CallbackDirectInvocationBinding.fromCompiler(
                    context.scope.request.subject.lease.identity,
                    occurrence,
                    owner,
                )
        ) {
            is Refinement.Refined -> CallbackBindingPreparation.Direct(admitted.value, supply.call)
            is Refinement.Rejected -> CallbackBindingPreparation.ContractRejected(admitted.failure)
        }
    }

    private fun defaultIsCallable(parameter: org.jetbrains.kotlin.psi.KtParameter): Boolean =
        context.observation.observedAnalyze(parameter) { parameter.symbol.returnType is KaFunctionType }

    private fun unavailable(cause: CallbackInvocationFlowCause) = CallbackBindingPreparation.Unavailable(cause)
}
