@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackBodySupply
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Maps a nested body's direct supply without enumerating or activating the receiving body. */
internal class IntellijCallbackOwnerBindingReader(private val context: IntellijCallbackFlowContext) {
    fun read(
        literal: KtFunctionLiteral,
        body: RelationCallableBody.Anonymous,
        enclosing: RelationEndpoint,
    ): Refinement<CallbackBodyBinding, CallbackInvocationFlowFailure> {
        val lambda = literal.parent as? KtLambdaExpression
        val obligations = linkedSetOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        return when (
            val supply = lambda?.let(::classifyCallbackLambdaSupply) ?: IntellijCallbackLambdaSupply.Unsupported
        ) {
            is IntellijCallbackLambdaSupply.Argument -> readArgument(supply, body, enclosing, obligations)
            is IntellijCallbackLambdaSupply.Invocation -> unavailableSource(body, supply, obligations)
            is IntellijCallbackLambdaSupply.Returned -> unavailableSource(body, supply, obligations)
            IntellijCallbackLambdaSupply.Stored ->
                unavailable(body, CallbackBodySupply.Stored, CallbackInvocationFlowCause.STORED_CALLBACK, obligations)
            IntellijCallbackLambdaSupply.Unsupported ->
                unavailable(
                    body,
                    CallbackBodySupply.Unsupported,
                    CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                    obligations,
                )
        }
    }

    private fun readArgument(
        argument: IntellijCallbackLambdaSupply.Argument,
        body: RelationCallableBody.Anonymous,
        enclosing: RelationEndpoint,
        obligations: MutableSet<CallbackInvocationFlowCause>,
    ): Refinement<CallbackBodyBinding, CallbackInvocationFlowFailure> {
        val call =
            supplyingCallbackCall(argument.argument)
                ?: return unavailable(
                    body,
                    CallbackBodySupply.Unsupported,
                    CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                    obligations,
                )
        val occurrence =
            context.occurrence(call.valueInvocationExpression())
                ?: return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        val supply = CallbackBodySupply.Invocation(occurrence)
        val bound =
            when (val permitted = context.permit()) {
                is Refinement.Rejected -> Refinement.Rejected(permitted.failure)
                is Refinement.Refined -> binding(call, argument.expression, enclosing, obligations)
            }
        return when (bound) {
            is Refinement.Refined ->
                CallbackBodyBinding.fromCompiler(body, supply, CallbackBindingEvidence.Bound(bound.value), obligations)
            is Refinement.Rejected -> unavailable(body, supply, bound.failure, obligations)
        }
    }

    private fun unavailableSource(
        body: RelationCallableBody.Anonymous,
        supply: IntellijCallbackLambdaSupply,
        obligations: Set<CallbackInvocationFlowCause>,
    ): Refinement<CallbackBodyBinding, CallbackInvocationFlowFailure> {
        val element =
            when (supply) {
                is IntellijCallbackLambdaSupply.Invocation -> supply.call.valueInvocationExpression()
                is IntellijCallbackLambdaSupply.Returned -> supply.occurrence
                else -> return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
            }
        val occurrence =
            context.occurrence(element)
                ?: return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        return when (supply) {
            is IntellijCallbackLambdaSupply.Invocation ->
                unavailable(
                    body,
                    CallbackBodySupply.Invocation(occurrence),
                    CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                    obligations,
                )
            is IntellijCallbackLambdaSupply.Returned ->
                unavailable(
                    body,
                    CallbackBodySupply.Returned(occurrence),
                    CallbackInvocationFlowCause.RETURNED_CALLBACK,
                    obligations,
                )
            else -> Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        }
    }

    private fun binding(
        call: KtCallElement,
        expression: KtExpression,
        enclosing: RelationEndpoint,
        obligations: MutableSet<CallbackInvocationFlowCause>,
    ): Refinement<CallbackArgumentBinding, CallbackInvocationFlowCause> {
        val mapped =
            when (val result = nativeArgumentBinding(call, expression)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return Refinement.Rejected(result.failure.flowCause())
            }
        val function =
            mapped.declaration as? KtNamedFunction
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        when (val admitted = admission(function)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> obligations += admitted.failure
        }
        val target =
            when (val result = context.bindingTarget(function)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        if (target.file !is SymbolDiscoveryFileIdentity.Workspace)
            return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        return detachBinding(call, enclosing, target, mapped)
    }

    private fun detachBinding(
        call: KtCallElement,
        enclosing: RelationEndpoint,
        target: RelationEndpoint,
        mapped: NativeArgument,
    ): Refinement<CallbackArgumentBinding, CallbackInvocationFlowCause> {
        val function = mapped.declaration as KtNamedFunction
        val nativeParameter =
            function.valueParameters.getOrNull(mapped.position.value)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        if (!analyze(nativeParameter) { nativeParameter.symbol.returnType is KaFunctionType })
            return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val parameter =
            context.occurrence(nativeParameter)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val owner =
            context.owner(call)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val range =
            context.range(call.valueInvocationExpression())
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val invocation =
            when (val result = ValueInvocation.fromCompiler(enclosing, range, target)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        return when (
            val result =
                CallbackArgumentBinding.fromCompiler(
                    invocation = invocation,
                    invocationOwner = owner,
                    position = mapped.position,
                    parameter = parameter,
                )
        ) {
            is Refinement.Refined -> result
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun admission(function: KtNamedFunction): Refinement<Unit, CallbackInvocationFlowCause> {
        val file =
            function.containingFile.virtualFile
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        if (!context.scope.nativeScope.contains(file))
            return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        return when (
            context.scope.request.searchConstraints.packageName.admitPackage { function.relationPackageEvidence() }
        ) {
            IntellijRelationPackageAdmission.ADMITTED -> Refinement.Refined(Unit)
            IntellijRelationPackageAdmission.OUTSIDE_SCOPE ->
                Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
            IntellijRelationPackageAdmission.UNSUPPORTED ->
                Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun unavailable(
        body: RelationCallableBody.Anonymous,
        supply: CallbackBodySupply,
        cause: CallbackInvocationFlowCause,
        obligations: Set<CallbackInvocationFlowCause>,
    ) = CallbackBodyBinding.fromCompiler(body, supply, CallbackBindingEvidence.Unavailable(cause), obligations + cause)

    private fun NativeArgumentBindingFailure.flowCause(): CallbackInvocationFlowCause =
        when (this) {
            NativeArgumentBindingFailure.EXTERNAL_CALL -> CallbackInvocationFlowCause.EXTERNAL_CALLABLE
            NativeArgumentBindingFailure.UNRESOLVED_REFERENCE -> CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING
        }
}
