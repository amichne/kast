package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.CallbackSupplierSelection
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.relation.contract.tracesSource
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Actual argument and factory-result arrivals retain their source and caller context. */
internal fun ImmutableCallbackEnumeration.binding(
    item: ImmutableCallbackPending,
    value: KtExpression,
    endpoint: RelationEndpoint.Resolved,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    when (val binding = IntellijCallbackBindingReader(context, endpoint.evidence).prepareExpression(value)) {
        is CallbackBindingPreparation.Unavailable -> obligations += binding.cause
        is CallbackBindingPreparation.ContractRejected -> return Refinement.Rejected(binding.cause)
        is CallbackBindingPreparation.DependencyContract ->
            obligations += CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
        is CallbackBindingPreparation.Direct ->
            retain(ImmutableCallbackInvocationUse.Direct(item.value, binding.binding))
        is CallbackBindingPreparation.Prepared -> return prepared(item, value, endpoint, binding.value)
    }
    return Refinement.Refined(Unit)
}

internal fun ImmutableCallbackEnumeration.prepared(
    item: ImmutableCallbackPending,
    value: KtExpression,
    endpoint: RelationEndpoint.Resolved,
    prepared: PreparedCallbackFlow,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val argument =
        prepared.origin as? PreparedCallbackOrigin.Argument
            ?: return obligation(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
    if (returnsCallable(prepared.function, context.observation)) return factoryArgument(item, argument, endpoint)
    val site =
        context.site(
            value,
            endpoint,
            ValueRole.Argument(argument.binding.invocation, argument.binding.position),
        )
    if (site == null) {
        obligations += CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING
        return Refinement.Refined(Unit)
    }
    val transported =
        when (val result = transport(item.value, site, ValueTransferKind.ARGUMENT)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> {
                obligations += result.failure
                return Refinement.Refined(Unit)
            }
        }
    val supplier =
        when (
            val result =
                CallbackParameterSupplier.fromCompiler(
                    argument.binding,
                    CallbackSupplierSelection.Explicit(site),
                    transported,
                )
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return Refinement.Rejected(CallbackInvocationFlowFailure.UNBOUND_INVOCATION)
        }
    when (val summary = IntellijCallbackParameterSummaryReader(context, summaries).read(prepared)) {
        is CallbackParameterSummaryRead.Available ->
            retain(ImmutableCallbackInvocationUse.Supplied(supplier, summary.summary))
        is CallbackParameterSummaryRead.Unavailable -> {
            obligations += summary.cause
            obligations += summary.additional
        }
        is CallbackParameterSummaryRead.ContractRejected -> return Refinement.Rejected(summary.cause)
    }
    return Refinement.Refined(Unit)
}

internal fun ImmutableCallbackEnumeration.factoryArgument(
    item: ImmutableCallbackPending,
    argument: PreparedCallbackOrigin.Argument,
    endpoint: RelationEndpoint.Resolved,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val call = argument.call as? KtCallExpression
    if (call == null) {
        obligations += CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
        return Refinement.Refined(Unit)
    }
    when (val returned = IntellijCallbackFactoryReturn(context, summaries).read(call, endpoint.evidence, emptySet())) {
        is Refinement.Rejected -> obligations += returned.failure
        is Refinement.Refined -> {
            val matching = returned.value.filter { it.tracesSource(origin, source) }
            if (matching.isEmpty()) retain(ImmutableCallbackInvocationUse.Unused(item.value))
            for (result in matching) pending.add(
                ImmutableCallbackPending(
                    call.valueInvocationExpression() as KtExpression,
                    result,
                    item.visited + item.expression,
                )
            )
        }
    }
    return Refinement.Refined(Unit)
}

internal fun ImmutableCallbackEnumeration.returned(
    item: ImmutableCallbackPending,
    owner: ContainingDeclaration.Found,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val factory = owner.declaration as? KtNamedFunction
    if (factory == null || !returnsCallable(factory, context.observation)) {
        obligations += CallbackInvocationFlowCause.RETURNED_CALLBACK
        return Refinement.Refined(Unit)
    }
    val calls =
        when (val found = callbackFactoryCalls(factory, context, summaries)) {
            is Refinement.Refined -> found.value
            is Refinement.Rejected -> {
                obligations += found.failure
                return Refinement.Refined(Unit)
            }
        }
    if (calls.isEmpty()) retain(ImmutableCallbackInvocationUse.Unused(item.value))
    for (call in calls) {
        val declaration =
            when (val found = call.nearestDeclaration()) {
                is ContainingDeclaration.Deferred -> found.enclosingDeclaration()
                is ContainingDeclaration.Found,
                ContainingDeclaration.Unsupported -> found
            }
                as? ContainingDeclaration.Found
        val caller = declaration?.let { context.receiverDeclaration(it.declaration) }
        if (caller == null) {
            obligations += CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE
            continue
        }
        when (val returned = IntellijCallbackFactoryReturn(context, summaries).read(call, caller, emptySet())) {
            is Refinement.Rejected -> obligations += returned.failure
            is Refinement.Refined ->
                returned.value
                    .filter { it.tracesSource(origin, source) }
                    .forEach { result ->
                        pending.add(
                            ImmutableCallbackPending(
                                call.valueInvocationExpression() as KtExpression,
                                result,
                                item.visited + item.expression,
                            )
                        )
                    }
        }
    }

    return Refinement.Refined(Unit)
}
