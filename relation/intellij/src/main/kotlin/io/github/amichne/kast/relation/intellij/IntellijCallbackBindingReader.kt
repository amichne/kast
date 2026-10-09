@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackDefaultBinding
import io.github.amichne.kast.relation.contract.CallbackDirectInvocationBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter

internal data class PreparedCallbackFlow(
    val function: KtNamedFunction,
    val parameter: KtParameter,
    val target: RelationEndpoint,
    val origin: PreparedCallbackOrigin,
) {
    fun formal(): Refinement<CallbackParameterIdentity, CallbackInvocationFlowCause> =
        when (val supply = origin) {
            is PreparedCallbackOrigin.Default -> Refinement.Refined(supply.binding.parameter)
            is PreparedCallbackOrigin.Argument ->
                when (
                    val admitted =
                        CallbackParameterIdentity.fromCompiler(
                            target,
                            supply.binding.position,
                            supply.binding.parameter,
                        )
                ) {
                    is Refinement.Refined -> admitted
                    is Refinement.Rejected ->
                        Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                }
        }
}

internal sealed interface PreparedCallbackOrigin {
    data class Argument(val binding: CallbackArgumentBinding, val call: KtCallElement) : PreparedCallbackOrigin

    data class Default(val binding: CallbackDefaultBinding) : PreparedCallbackOrigin
}

internal val PreparedCallbackFlow.binding: CallbackBindingEvidence
    get() =
        when (val supply = origin) {
            is PreparedCallbackOrigin.Argument -> CallbackBindingEvidence.Bound(supply.binding)
            is PreparedCallbackOrigin.Default -> CallbackBindingEvidence.Default(supply.binding)
        }

internal sealed interface CallbackBindingPreparation {
    data class Prepared(val value: PreparedCallbackFlow) : CallbackBindingPreparation

    data class Direct(
        val binding: CallbackDirectInvocationBinding,
        val call: org.jetbrains.kotlin.psi.KtCallExpression,
    ) : CallbackBindingPreparation

    data class DependencyContract(val binding: io.github.amichne.kast.relation.contract.CallbackDependencyContract) :
        CallbackBindingPreparation

    data class Unavailable(val cause: CallbackInvocationFlowCause) : CallbackBindingPreparation

    data class ContractRejected(val cause: CallbackInvocationFlowFailure) : CallbackBindingPreparation
}

private data class MappedCallbackTarget(
    val function: KtNamedFunction,
    val parameter: KtParameter,
    val endpoint: RelationEndpoint,
)

/** Preserves the formal compiler mapping separately from the supplying call's actual callable owner. */
internal class IntellijCallbackBindingReader(
    private val context: IntellijCallbackFlowContext,
    private val lexicalOwner: CompilerGroundedSymbolEvidence,
) {
    fun prepare(literal: KtFunction): CallbackBindingPreparation =
        prepareSupply(classifyCallbackFunctionSupply(literal))

    fun prepareExpression(expression: org.jetbrains.kotlin.psi.KtExpression): CallbackBindingPreparation {
        var value = expression
        while (value.parent is org.jetbrains.kotlin.psi.KtParenthesizedExpression) value =
            value.parent as org.jetbrains.kotlin.psi.KtExpression
        return prepareSupply(classifyCallbackValueSupply(value))
    }

    private fun prepareSupply(supply: IntellijCallbackLambdaSupply): CallbackBindingPreparation {
        val argument =
            when (supply) {
                is IntellijCallbackLambdaSupply.Argument -> supply
                is IntellijCallbackLambdaSupply.DefaultParameter ->
                    return IntellijCallbackDeclaredSupplyReader(context).prepareDefault(supply)
                is IntellijCallbackLambdaSupply.Invocation ->
                    return IntellijCallbackDeclaredSupplyReader(context).prepareDirect(supply)
                IntellijCallbackLambdaSupply.Stored -> return unavailable(CallbackInvocationFlowCause.STORED_CALLBACK)
                is IntellijCallbackLambdaSupply.Returned ->
                    return unavailable(CallbackInvocationFlowCause.RETURNED_CALLBACK)
                IntellijCallbackLambdaSupply.Unsupported ->
                    return unavailable(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            }
        val call =
            supplyingCallbackCall(argument.argument)
                ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return unavailable(allowed.failure)
        }
        val mapped =
            when (val result = nativeArgumentBinding(call, argument.expression)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return unavailable(result.failure.flowCause())
            }
        val mappedFile = mapped.declaration.containingFile?.virtualFile
        if (mappedFile != null && !context.scope.nativeScope.contains(mappedFile))
            return readCallbackDependencyContract(context, call, argument.expression)
        val target =
            when (val result = target(mapped)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return unavailable(result.failure)
            }
        return bind(call, target, mapped.position)
    }

    private fun target(mapped: NativeArgument): Refinement<MappedCallbackTarget, CallbackInvocationFlowCause> {
        val function =
            mapped.declaration as? KtNamedFunction
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        val file =
            function.containingFile.virtualFile
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        if (!context.scope.nativeScope.contains(file))
            return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        val endpoint =
            when (val result = context.target(function)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        val parameter =
            function.valueParameters.getOrNull(mapped.position.value)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        if (!analyze(parameter) { parameter.symbol.returnType is KaFunctionType })
            return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return Refinement.Refined(MappedCallbackTarget(function, parameter, endpoint))
    }

    private fun bind(
        call: KtCallElement,
        target: MappedCallbackTarget,
        position: ValueArgumentPosition,
    ): CallbackBindingPreparation {
        val invocation =
            invocation(call, target.endpoint)
                ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val parameter =
            context.occurrence(target.parameter)
                ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val owner =
            context.owner(call) ?: return unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        return when (
            val admitted =
                CallbackArgumentBinding.fromCompiler(
                    invocation = invocation,
                    invocationOwner = owner,
                    position = position,
                    parameter = parameter,
                )
        ) {
            is Refinement.Refined ->
                CallbackBindingPreparation.Prepared(
                    PreparedCallbackFlow(
                        function = target.function,
                        parameter = target.parameter,
                        target = target.endpoint,
                        origin = PreparedCallbackOrigin.Argument(admitted.value, call),
                    )
                )
            is Refinement.Rejected -> CallbackBindingPreparation.ContractRejected(admitted.failure)
        }
    }

    private fun invocation(call: KtCallElement, target: RelationEndpoint): ValueInvocation? {
        val enclosing = context.endpoint(lexicalOwner) ?: return null
        val range = context.range(call.valueInvocationExpression()) ?: return null
        return when (val result = ValueInvocation.fromCompiler(enclosing, range, target)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> null
        }
    }

    private fun unavailable(cause: CallbackInvocationFlowCause) = CallbackBindingPreparation.Unavailable(cause)

    private fun NativeArgumentBindingFailure.flowCause(): CallbackInvocationFlowCause =
        when (this) {
            NativeArgumentBindingFailure.EXTERNAL_CALL -> CallbackInvocationFlowCause.EXTERNAL_CALLABLE
            NativeArgumentBindingFailure.UNRESOLVED_REFERENCE -> CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING
        }
}
