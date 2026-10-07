@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackDefaultBinding
import io.github.amichne.kast.relation.contract.CallbackFactoryCaptureContent
import io.github.amichne.kast.relation.contract.CallbackFactoryCaptureSelection
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

internal data class NativeFactoryArgument(val expression: KtExpression, val default: Boolean, val callable: Boolean)

internal data class NativeFactoryValueContext(
    val invocation: ValueInvocation,
    val lexicalOwner: CompilerGroundedSymbolEvidence,
    val activeFactories: Set<KtNamedFunction>,
    val active: Set<KtNamedFunction>,
)

internal class IntellijFactoryCaptureValues(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
    private val scope: NativeFactoryValueContext,
) {
    private val invocation = scope.invocation
    private val factory = invocation.callable as io.github.amichne.kast.relation.contract.RelationEndpoint.Resolved
    private val enclosing = invocation.enclosing
    private val lexicalOwner = scope.lexicalOwner
    private val activeFactories = scope.activeFactories
    private val active = scope.active
    private val resolver = IntellijImmutableCallbackValueResolver(context, summaries)

    fun content(
        argument: NativeFactoryArgument,
        site: ValueSite,
    ): Refinement<CallbackFactoryCaptureContent, CallbackInvocationFlowCause> {
        if (!argument.callable) return Refinement.Refined(CallbackFactoryCaptureContent.Scalar)
        val values =
            when (
                val found =
                    resolver.resolve(
                        argument.expression,
                        if (argument.default) factory.evidence else lexicalOwner,
                        if (argument.default) active else activeFactories,
                    )
            ) {
                is Refinement.Refined -> found.value
                is Refinement.Rejected -> return found
            }
        val supplied = mutableListOf<ImmutableCallbackValue>()
        for (value in values) when (val moved = selectedValue(value, site, argument)) {
            is Refinement.Refined -> supplied += moved.value
            is Refinement.Rejected -> return moved
        }
        return Refinement.Refined(CallbackFactoryCaptureContent.Callable(supplied, emptyList()))
    }

    private fun selectedValue(
        value: ImmutableCallbackValue,
        site: ValueSite,
        argument: NativeFactoryArgument,
    ): Refinement<ImmutableCallbackValue, CallbackInvocationFlowCause> {
        if (argument.default && value.destination == site) return Refinement.Refined(value)
        val kind = if (argument.default) ValueTransferKind.BRANCH_ALTERNATIVE else ValueTransferKind.ARGUMENT
        return transport(value, site, kind)
    }

    fun selection(
        argument: NativeFactoryArgument,
        binding: CallbackArgumentBinding,
        site: ValueSite,
    ): Refinement<CallbackFactoryCaptureSelection, CallbackInvocationFlowCause> {
        val selection =
            if (argument.default) {
                val formal =
                    when (
                        val admitted =
                            CallbackParameterIdentity.fromCompiler(factory, binding.position, binding.parameter)
                    ) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected ->
                            return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                    }
                val occurrence =
                    context.occurrence(argument.expression)
                        ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                val declaration =
                    when (val admitted = CallbackDefaultBinding.fromCompiler(formal, occurrence)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected ->
                            return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                    }
                CallbackFactoryCaptureSelection.Default(declaration)
            } else CallbackFactoryCaptureSelection.Explicit(site)
        return Refinement.Refined(selection)
    }

    fun transparent(
        position: ValueArgumentPosition,
        argument: KtExpression,
    ): Refinement<List<ImmutableCallbackValue>, CallbackInvocationFlowCause> {
        val site =
            context.site(argument, enclosing, ValueRole.Argument(invocation, position))
                ?: return rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val supplied =
            when (val read = resolver.resolve(argument, lexicalOwner, activeFactories)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val results = mutableListOf<ImmutableCallbackValue>()
        for (value in supplied) {
            val bound =
                when (val moved = transport(value, site, ValueTransferKind.ARGUMENT)) {
                    is Refinement.Refined -> moved.value
                    is Refinement.Rejected -> return moved
                }
            val result =
                when (val moved = transport(bound, invocation.resultSite(), ValueTransferKind.WRAPPER_RETURN)) {
                    is Refinement.Refined -> moved.value
                    is Refinement.Rejected -> return moved
                }
            when (val retained = summaries.retention.admit(result.retainedBytes)) {
                is Refinement.Refined -> results += result
                is Refinement.Rejected -> return retained
            }
        }
        return Refinement.Refined(results)
    }

    private fun rejected(cause: CallbackInvocationFlowCause) = Refinement.Rejected(cause)
}
