@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocationOperation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** A direct source receiver enters its exact compiler receiver formal without asserting a result dependency. */
internal fun IntellijRelationAllowance.nativeValueSourceReceiver(
    source: ValueSite,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val role = source.role as? ValueRole.Receiver ?: return receiverRejected()
    if (role.call.operation is ValueInvocationOperation.Getter) {
        return when (val position = nativeGetterReceiverFormal(source, scope, projection)) {
            is Refinement.Refined -> nativeSourceEntry(source, role.call, position.value)
            is Refinement.Rejected -> position
        }
    }
    val function =
        when (val value = projection.subject(scope, role.call.callable)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration as? KtNamedFunction
            is IntellijRelationSubjectLookup.Rejected -> null
        } ?: return receiverRejected()
    if (function.bodyExpression == null) return Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
    if (
        nativeValueBodySelection(
            function = function,
            source = source,
            callable = role.call.callable,
            scope = scope,
            projection = projection,
        ) != NativeValueBodySelection.DIRECT
    )
        return receiverRejected()
    return when (val position = nativeValueReceiverFormal(source, scope, projection)) {
        is Refinement.Refined -> nativeSourceEntry(source, role.call, position.value)
        is Refinement.Rejected -> position
    }
}

/** The static call establishes its receiver slot independently of the selected execution body. */
internal fun IntellijRelationAllowance.nativeValueReceiverFormal(
    source: ValueSite,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueFormalPosition.Receiver, ValueFlowUnsupportedCause> {
    val role = source.role as? ValueRole.Receiver ?: return receiverRejected()
    if (role.call.operation is ValueInvocationOperation.Getter)
        return nativeGetterReceiverFormal(source, scope, projection)
    val function =
        when (val value = projection.subject(scope, role.call.callable)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration as? KtNamedFunction
            is IntellijRelationSubjectLookup.Rejected -> null
        } ?: return receiverRejected()
    val host =
        when (val value = projection.subject(scope, role.call.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration
            is IntellijRelationSubjectLookup.Rejected -> return receiverRejected()
        }
    val expression = host.exactValueElement(role.call.range) ?: return receiverRejected()
    val syntax = expression.nativeValueCallSyntax(function) ?: return receiverRejected()
    when (val admitted = admitReceiverCall(syntax, role, function, scope, projection)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    val position =
        nativeReceiverPosition(
            role = role,
            range = source.range,
            syntax = syntax,
            function = function,
            projection = projection,
        ) ?: return receiverRejected()
    if (position != role.kind) return receiverRejected()
    return Refinement.Refined(ValueFormalPosition.Receiver(position))
}

private fun IntellijRelationAllowance.admitReceiverCall(
    syntax: NativeValueCallSyntax,
    role: ValueRole.Receiver,
    function: KtNamedFunction,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<Unit, ValueFlowUnsupportedCause> =
    when (syntax) {
        is NativeValueCallSyntax.Explicit -> Refinement.Refined(Unit)
        is NativeValueCallSyntax.Component -> admitComponentReceiver(syntax, role, function, scope, projection)
        is NativeValueCallSyntax.Loop ->
            when (
                val result =
                    nativeLoopInvocationResult(syntax.loop, role.call, scope, projection) { permitDestructuring(scope) }
            ) {
                is Refinement.Refined -> Refinement.Refined(Unit)
                is Refinement.Rejected -> result
            }
    }

private fun IntellijRelationAllowance.admitComponentReceiver(
    syntax: NativeValueCallSyntax.Component,
    role: ValueRole.Receiver,
    function: KtNamedFunction,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val admission =
        when (
            val admitted =
                syntax.admit(
                    enclosing = role.call.enclosing,
                    scope = scope,
                    projection = projection,
                    permit = { permitDestructuring(scope) },
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    return when (val selected = admission.target(syntax.entry)) {
        is Refinement.Rejected -> selected
        is Refinement.Refined ->
            if (selected.value == NativeComponentTarget.Source(function, role.kind)) Refinement.Refined(Unit)
            else receiverRejected()
    }
}

internal fun nativeReceiverPosition(
    role: ValueRole.Receiver,
    range: ExactDeclarationTextRange,
    syntax: NativeValueCallSyntax,
    function: KtNamedFunction,
    projection: IntellijK2RelationProjection,
): ValueReceiverKind? =
    projection.observation.observedAnalyze(syntax.element) {
        val resolved = resolveNativeValueCall(syntax) ?: return@observedAnalyze null
        val symbol = resolved.signature.symbol
        if (symbol.psi?.originalElement != function.originalElement) return@observedAnalyze null
        when (syntax) {
            is NativeValueCallSyntax.Explicit ->
                matchedReceiverPosition(
                    kind = role.kind,
                    range = range,
                    call = syntax.call,
                    extension = resolved.extensionReceiver,
                    dispatch = resolved.dispatchReceiver,
                )
            is NativeValueCallSyntax.Component ->
                if (range == sourceValueRange(syntax.entry)) nativeComponentReceiverKind(syntax.entry) else null
            is NativeValueCallSyntax.Loop ->
                loopReceiverPosition(range, syntax, resolved.extensionReceiver, resolved.dispatchReceiver)
        }
    }

private fun loopReceiverPosition(
    range: ExactDeclarationTextRange,
    syntax: NativeValueCallSyntax.Loop,
    extension: KaReceiverValue?,
    dispatch: KaReceiverValue?,
): ValueReceiverKind? =
    if (range == sourceValueRange(syntax.loop) && dispatch != null && extension == null) ValueReceiverKind.DISPATCH
    else null

private fun matchedReceiverPosition(
    kind: ValueReceiverKind,
    range: ExactDeclarationTextRange,
    call: KtCallElement,
    extension: KaReceiverValue?,
    dispatch: KaReceiverValue?,
): ValueReceiverKind? {
    val selected =
        when (kind) {
            ValueReceiverKind.EXTENSION -> extension
            ValueReceiverKind.DISPATCH -> dispatch
        }
    if (selected is KaImplicitReceiverValue)
        return kind.takeIf { range == sourceValueRange(call.valueInvocationExpression()) }
    val receiver = call.valueInvocationExpression() as? KtQualifiedExpression ?: return null
    if (sourceValueRange(receiver.receiverExpression) != range) return null
    return nativeExplicitReceiverKind(receiver.receiverExpression, extension, dispatch)
}

private fun receiverRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
