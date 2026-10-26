@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallBindingFailure
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueField
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValuePathSelection
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression

internal fun nativeValueSourceArgument(
    source: ValueSite,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val argument = source.role as? ValueRole.Argument ?: return sourceRejected()
    val callable =
        when (val native = projection.subject(scope, argument.call.callable)) {
            is IntellijRelationSubjectLookup.Found -> native.declaration as? KtNamedFunction
            is IntellijRelationSubjectLookup.Rejected -> null
        } ?: return sourceRejected()
    if (callable.bodyExpression == null) return Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
    if (
        nativeValueBodySelection(
            function = callable,
            source = source,
            callable = argument.call.callable,
            scope = scope,
            projection = projection,
        ) != NativeValueBodySelection.DIRECT
    )
        return sourceRejected()
    val parameter = callable.valueParameters.getOrNull(argument.position.value) ?: return sourceRejected()
    return when (val formal = nativeValueFormalParameter(argument, parameter)) {
        is Refinement.Refined -> nativeSourceEntry(source, argument.call, ValueFormalPosition.Parameter(formal.value))
        is Refinement.Rejected -> formal
    }
}

internal fun nativeValueFormalParameter(
    argument: ValueRole.Argument,
    parameter: KtParameter,
): Refinement<ValueFormalParameter, ValueFlowUnsupportedCause> =
    nativeValueFormalParameter(argument.call.callable, argument.position, parameter)

internal fun nativeValueFormalParameter(
    callable: RelationEndpoint,
    position: ValueArgumentPosition,
    parameter: KtParameter,
): Refinement<ValueFormalParameter, ValueFlowUnsupportedCause> {
    val name =
        when (val value = SymbolDiscoveryCandidateName.parse(parameter.name.orEmpty())) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return sourceRejected()
        }
    return when (
        val value =
            ValueFormalParameter.fromCompiler(
                callable = callable,
                position = position,
                name = name,
                range = sourceValueRange(parameter),
            )
    ) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> sourceRejected()
    }
}

internal fun nativeSourceEntry(
    source: ValueSite,
    call: ValueInvocation,
    formal: ValueFormalPosition,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val frame =
        when (val value = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(call.callable))) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return sourceRejected()
        }
    return nativeSourceEntry(source, frame, formal)
}

internal fun nativeSourceEntry(
    source: ValueSite,
    frame: ValueCallFrame,
    formal: ValueFormalPosition,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val target =
        when (val value = sourceFormalTarget(source, formal, frame)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    return sourceTransfer(
        source = source,
        target = target,
        kind = ValueTransferKind.SOURCE_CALL_ENTRY,
        operation = ValueSourceOperation.Enter(frame, formal),
    )
}

private fun sourceFormalTarget(
    source: ValueSite,
    formal: ValueFormalPosition,
    frame: ValueCallFrame,
): Refinement<ValueSite, ValueFlowUnsupportedCause> {
    val stack =
        when (val value = source.callStack.enter(frame)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return Refinement.Rejected(value.failure.nativeCallCause())
        }
    return sourceFormalTarget(source, formal, frame, stack)
}

internal fun sourceFormalTarget(
    source: ValueSite,
    formal: ValueFormalPosition,
    frame: ValueCallFrame,
    stack: ValueCallStack,
): Refinement<ValueSite, ValueFlowUnsupportedCause> {
    val range =
        when (formal) {
            is ValueFormalPosition.Parameter -> formal.parameter.range
            is ValueFormalPosition.Receiver -> frame.callee.range
        }
    return when (
        val value =
            ValueSite.fromCompiler(
                enclosing = frame.callee.enclosing,
                range = range,
                role = ValueRole.Formal(formal),
                executionOwner = frame.callee,
                valuePath = source.valuePath,
                callStack = stack,
                conditions = source.conditions,
            )
    ) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> sourceRejected()
    }
}

internal fun nativeValueFieldRead(
    source: ValueSite,
    expression: KtQualifiedExpression,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<NativeValueFieldRead, ValueFlowUnsupportedCause> {
    val selector = expression.selectorExpression ?: return sourceRejected()
    val declaration =
        projection.observation.observedAnalyze(selector) {
            val reference = selector.references.filterIsInstance<KtReference>().singleOrNull()
            (reference?.resolveToSymbol() as? KaKotlinPropertySymbol)?.psi as? PsiNamedElement
        } ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    val field =
        when (
            val value =
                nativeValueField(
                    declaration = declaration,
                    enclosing = source.enclosing,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    return sourceFieldTarget(source, expression, field)
}

internal fun sourceFieldTarget(
    source: ValueSite,
    expression: KtExpression,
    field: ValueField,
    conditions: ValueConditions = source.conditions,
): Refinement<NativeValueFieldRead, ValueFlowUnsupportedCause> {
    val selected =
        when (val value = source.valuePath.select(ValuePathSegment.Field(field))) {
            is ValuePathSelection.Selected -> value.path
            ValuePathSelection.DifferentField -> return Refinement.Refined(NativeValueFieldRead.DifferentField)
        }
    val target =
        when (
            val value =
                ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = sourceValueRange(expression),
                    role = ValueRole.ExpressionResult,
                    executionOwner = source.executionOwner,
                    valuePath = selected,
                    callStack = source.callStack,
                    conditions = conditions,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return sourceRejected()
        }
    return when (
        val value =
            sourceTransfer(
                source = source,
                target = target,
                kind = ValueTransferKind.FIELD_READ,
                operation = ValueSourceOperation.ReadField(field),
            )
    ) {
        is Refinement.Refined -> Refinement.Refined(NativeValueFieldRead.Transfer(value.value))
        is Refinement.Rejected -> value
    }
}

internal fun nativeValueField(
    declaration: PsiNamedElement,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueField, ValueFlowUnsupportedCause> {
    val endpoint =
        when (
            val value =
                nativeValueCallableEndpoint(
                    declaration = declaration,
                    enclosing = enclosing,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val kotlin =
        declaration as? KtNamedDeclaration ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    if (!kotlin.hasStoredValueField(projection))
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    return when (val value = ValueField.fromCompiler(endpoint)) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> sourceRejected()
    }
}

private fun KtNamedDeclaration.hasStoredValueField(projection: IntellijK2RelationProjection): Boolean =
    projection.observation.observedAnalyze(this) {
        val property =
            when (val declaration = this@hasStoredValueField) {
                is KtParameter -> (declaration.symbol as? KaValueParameterSymbol)?.generatedPrimaryConstructorProperty
                is KtProperty -> declaration.symbol as? KaKotlinPropertySymbol
                else -> null
            }
        property != null &&
            property.modality == KaSymbolModality.FINAL &&
            property.getter?.modality == KaSymbolModality.FINAL &&
            property.isVal &&
            !property.isDelegated &&
            property.hasBackingField &&
            property.getter?.isNotDefault == false
    }

internal fun sourceTransfer(
    source: ValueSite,
    target: ValueSite,
    kind: ValueTransferKind,
    operation: ValueSourceOperation,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> =
    when (
        val value =
            ValueTransfer.fromCompiler(
                source = source,
                target = target,
                kind = kind,
                evidence = ValueSourceTransfer.fromCompiler(operation),
            )
    ) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> sourceRejected()
    }

internal fun sourceValueRange(element: PsiElement): ExactDeclarationTextRange =
    when (val value = ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset)) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("A live Kotlin PSI element has a nonnegative range")
    }

private fun sourceRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)

internal fun ValueCallBindingFailure.nativeCallCause(): ValueFlowUnsupportedCause =
    when (this) {
        ValueCallBindingFailure.DEPTH_EXCEEDED -> ValueFlowUnsupportedCause.CALL_CONTEXT_DEPTH_EXCEEDED
        ValueCallBindingFailure.RECURSIVE_CALL -> ValueFlowUnsupportedCause.RECURSIVE_SOURCE_CALL
        ValueCallBindingFailure.BASIS_MISMATCH,
        ValueCallBindingFailure.CALLABLE_MISMATCH,
        ValueCallBindingFailure.INVALID_PARAMETER,
        ValueCallBindingFailure.PARAMETER_OUTSIDE_CALLABLE,
        ValueCallBindingFailure.RETURN_MISMATCH,
        ValueCallBindingFailure.IMPLEMENTATION_PROOF_MISMATCH,
        ValueCallBindingFailure.SOURCE_OWNERSHIP_MISMATCH -> ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE
    }
