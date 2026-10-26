@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCaptureInvocation
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter

/** The supplier stack stays active while the actual value enters the callback's exact formal input. */
internal fun ValueSite.nativeCallbackInputTransfer(
    binding: NativeCallbackArgumentBinding.Mapped,
    frame: ValueCallFrame,
    supplier: CallbackParameterSupplier,
    execution: NativeValueExecution,
    observation: IntellijReadObservation,
): Refinement<ValueTransfer, NativeCallbackInputFailure> {
    val source = this
    if (source.role != ValueRole.ExpressionResult && source.role != ValueRole.LocalRead) return callbackInputRejected()
    val invocation =
        when (val admitted = callbackInputInvocation(source, binding, supplier, execution.owner)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val parameter =
        when (val admitted = callbackInputParameter(execution, binding.position, observation)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val target =
        when (
            val admitted =
                ValueSite.fromCompiler(
                    enclosing = execution.owner.enclosing,
                    range = parameter.range,
                    role = ValueRole.Formal(ValueFormalPosition.Parameter(parameter)),
                    executionOwner = execution.owner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                    conditions = source.conditions,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return callbackInputRejected()
        }
    return when (
        val admitted =
            sourceTransfer(
                source,
                target,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueSourceOperation.CallbackArgumentEntry(invocation, frame, parameter, binding.argumentRange),
            )
    ) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> callbackInputRejected(admitted.failure)
    }
}

private fun callbackInputInvocation(
    source: ValueSite,
    binding: NativeCallbackArgumentBinding.Mapped,
    supplier: CallbackParameterSupplier,
    owner: ValueExecutionOwner,
): Refinement<ValueCaptureInvocation, NativeCallbackInputFailure> {
    val context = owner.context
    if (context !is ValueExecutionContext.Callback) return callbackInputRejected()
    val invocation = context.selectedInvocation
    if (invocation.body != context.body || invocation.supply != supplier.binding) return callbackInputRejected()
    if (
        invocation.invocation.occurrence.range != binding.invocationRange ||
            invocation.invocation.occurrence.file != source.enclosing.file
    )
        return callbackInputRejected()
    val invokingOwner = invocation.invocation.owner
    if (invokingOwner !is RelationCallableBody.Named) return callbackInputRejected()
    return if (
        invokingOwner.file == source.enclosing.file &&
            invokingOwner.range == source.enclosing.range &&
            invokingOwner.compilerIdentity == source.enclosing.compilerIdentity
    )
        Refinement.Refined(invocation)
    else callbackInputRejected()
}

private fun callbackInputParameter(
    execution: NativeValueExecution,
    position: ValueArgumentPosition,
    observation: IntellijReadObservation,
): Refinement<ValueFormalParameter, NativeCallbackInputFailure> {
    val literal = execution.nativeOwner as? KtFunction
    if (literal == null) return callbackInputRejected()
    return observation.observedAnalyze(literal) {
        callbackInputParameter(literal, execution.owner, position)
    }
}

private fun KaSession.callbackInputParameter(
    literal: KtFunction,
    owner: ValueExecutionOwner,
    position: ValueArgumentPosition,
): Refinement<ValueFormalParameter, NativeCallbackInputFailure> {
    val symbol =
        when (literal) {
            is KtFunctionLiteral -> literal.symbol
            is KtNamedFunction -> literal.symbol
            else -> null
        }
            as? KaFunctionSymbol
    if (symbol == null || symbol.receiverParameter != null || symbol.contextReceivers.isNotEmpty())
        return callbackInputRejected()
    val selected = symbol.valueParameters.getOrNull(position.value)
    val parameter = selected?.psi as? KtParameter
    if (selected == null || parameter == null) return callbackInputRejected()
    val name =
        when (val parsed = SymbolDiscoveryCandidateName.parse(selected.name.asString())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return callbackInputRejected()
        }
    return when (
        val admitted =
            ValueFormalParameter.fromCompiler(
                callable = owner.enclosing,
                position = position,
                name = name,
                range = sourceValueRange(parameter),
                executionOwner = owner,
            )
    ) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> callbackInputRejected()
    }
}
