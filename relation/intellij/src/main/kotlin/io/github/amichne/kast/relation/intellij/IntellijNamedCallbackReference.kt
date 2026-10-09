@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackReferenceReceiver
import io.github.amichne.kast.relation.contract.CallbackReferenceReceivers
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlow
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.NamedCallbackReference
import io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Named callable reference creation and invocation evidence are separate compiler observations. */
internal class IntellijNamedCallbackReference(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(
        expression: KtCallableReferenceExpression,
        lexicalOwner: CompilerGroundedSymbolEvidence,
    ): Refinement<NamedCallbackReference, CallbackInvocationFlowCause> {
        val origin =
            when (val read = readOrigin(expression)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val flow =
            when (val read = readFlow(expression, lexicalOwner, origin)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        return when (
            val admitted = NamedCallbackReference.fromCompiler(origin.occurrence, origin.target, origin.receivers, flow)
        ) {
            is Refinement.Refined -> {
                if (flow is NamedCallbackReferenceFlow.Supplied) summaries.used(flow.summary)
                admitted
            }
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun readFlow(
        expression: KtCallableReferenceExpression,
        lexicalOwner: CompilerGroundedSymbolEvidence,
        origin: ImmutableCallbackValueOrigin.Named,
    ): Refinement<NamedCallbackReferenceFlow, CallbackInvocationFlowCause> {
        val flow =
            when (val prepared = IntellijCallbackBindingReader(context, lexicalOwner).prepareExpression(expression)) {
                is CallbackBindingPreparation.Unavailable ->
                    unavailableFlow(prepared.cause, expression, lexicalOwner, origin)
                is CallbackBindingPreparation.ContractRejected ->
                    return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
                is CallbackBindingPreparation.DependencyContract ->
                    NamedCallbackReferenceFlow.Unavailable(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
                is CallbackBindingPreparation.Direct -> NamedCallbackReferenceFlow.Direct(prepared.binding)
                is CallbackBindingPreparation.Prepared -> preparedFlow(prepared.value, expression, lexicalOwner, origin)
            }
        return Refinement.Refined(flow)
    }

    private fun unavailableFlow(
        cause: CallbackInvocationFlowCause,
        expression: KtCallableReferenceExpression,
        lexicalOwner: CompilerGroundedSymbolEvidence,
        origin: ImmutableCallbackValueOrigin.Named,
    ): NamedCallbackReferenceFlow {
        if (
            cause !in
                setOf(
                    CallbackInvocationFlowCause.STORED_CALLBACK,
                    CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                    CallbackInvocationFlowCause.RETURNED_CALLBACK,
                )
        )
            return NamedCallbackReferenceFlow.Unavailable(cause)
        return immutableFlow(
            IntellijImmutableCallbackFlowReader(context, summaries).read(expression, lexicalOwner, origin)
        )
    }

    private fun preparedFlow(
        prepared: PreparedCallbackFlow,
        expression: KtCallableReferenceExpression,
        lexicalOwner: CompilerGroundedSymbolEvidence,
        origin: ImmutableCallbackValueOrigin.Named,
    ): NamedCallbackReferenceFlow {
        if (prepared.origin is PreparedCallbackOrigin.Default)
            return immutableFlow(IntellijImmutableCallbackFlowReader(context, summaries).readDefault(prepared, origin))
        if (returnsCallable(prepared.function, context.observation))
            return immutableFlow(
                IntellijImmutableCallbackFlowReader(context, summaries).read(expression, lexicalOwner, origin)
            )
        return supplied(prepared)
    }

    private fun immutableFlow(
        read: Refinement<ImmutableCallbackInvocationFlow, CallbackInvocationFlowFailure>
    ): NamedCallbackReferenceFlow =
        when (read) {
            is Refinement.Refined -> NamedCallbackReferenceFlow.Immutable(read.value)
            is Refinement.Rejected ->
                NamedCallbackReferenceFlow.Unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }

    fun readOrigin(
        expression: KtCallableReferenceExpression
    ): Refinement<
        ImmutableCallbackValueOrigin.Named,
        CallbackInvocationFlowCause,
    > {
        when (val permitted = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return permitted
        }
        val resolved =
            when (val result = nativeReference(expression)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
        val file =
            resolved.declaration.containingFile?.virtualFile
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        if (!context.scope.nativeScope.contains(file))
            return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        val target =
            when (val admitted = context.target(resolved.declaration)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        val occurrence =
            context.occurrence(expression)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        return Refinement.Refined(
            ImmutableCallbackValueOrigin.Named(
                occurrence,
                target,
                resolved.receivers,
            )
        )
    }

    private fun nativeReference(
        expression: KtCallableReferenceExpression
    ): Refinement<NativeReference, CallbackInvocationFlowCause> =
        context.observation.observedAnalyze(expression) {
            val call =
                expression.resolveCall()
                    ?: return@observedAnalyze Refinement.Rejected(
                        CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING
                    )
            val symbol =
                call.signature.symbol as? KaNamedFunctionSymbol
                    ?: return@observedAnalyze Refinement.Rejected(
                        CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
                    )
            val declaration =
                symbol.psi as? KtNamedFunction
                    ?: return@observedAnalyze Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
            if (call.contextArguments.isNotEmpty())
                return@observedAnalyze Refinement.Rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            val dispatch =
                receiver(call.dispatchReceiver, symbol.containingDeclaration is KaClassSymbol)
                    ?: return@observedAnalyze Refinement.Rejected(
                        CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING
                    )
            val extension =
                receiver(call.extensionReceiver, symbol.receiverParameter != null)
                    ?: return@observedAnalyze Refinement.Rejected(
                        CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING
                    )
            Refinement.Refined(NativeReference(declaration, CallbackReferenceReceivers(dispatch, extension)))
        }

    private fun receiver(value: KaReceiverValue?, required: Boolean): CallbackReferenceReceiver? =
        when (value) {
            null -> if (required) CallbackReferenceReceiver.Unbound else CallbackReferenceReceiver.Absent
            is KaExplicitReceiverValue -> context.occurrence(value.expression)?.let(CallbackReferenceReceiver::Bound)
            is KaImplicitReceiverValue ->
                (value.symbol.psi as? com.intellij.psi.PsiNamedElement)
                    ?.let(context::receiverDeclaration)
                    ?.let(CallbackReferenceReceiver::Implicit)
            else -> null
        }

    private fun supplied(prepared: PreparedCallbackFlow): NamedCallbackReferenceFlow {
        val origin =
            prepared.origin as? PreparedCallbackOrigin.Argument
                ?: return NamedCallbackReferenceFlow.Unavailable(
                    CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
                )
        return when (val read = IntellijCallbackParameterSummaryReader(context, summaries).read(prepared)) {
            is CallbackParameterSummaryRead.Available ->
                NamedCallbackReferenceFlow.Supplied(origin.binding, read.summary)
            is CallbackParameterSummaryRead.Unavailable ->
                NamedCallbackReferenceFlow.Unavailable(read.cause, read.additional)
            is CallbackParameterSummaryRead.ContractRejected ->
                NamedCallbackReferenceFlow.Unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private data class NativeReference(val declaration: KtNamedFunction, val receivers: CallbackReferenceReceivers)
}
