@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Generated accessors use field evidence; authored operators use the ordinary matched source-call machinery. */
internal sealed interface NativeComponentTarget {
    sealed interface Callable : NativeComponentTarget {
        val declaration: PsiNamedElement
        val receiver: ValueReceiverKind
    }

    data class Source(override val declaration: KtNamedFunction, override val receiver: ValueReceiverKind) : Callable

    data class Getter(override val declaration: KtProperty) : Callable {
        override val receiver: ValueReceiverKind = ValueReceiverKind.DISPATCH
    }

    data class Field(val parameter: KtParameter) : NativeComponentTarget
}

internal fun nativeComponentTarget(
    entry: KtDestructuringDeclarationEntry,
    projection: IntellijK2RelationProjection,
): Refinement<NativeComponentTarget, ValueFlowUnsupportedCause> =
    projection.observation.observedAnalyze(entry) {
        val resolved = entry.resolveCall()
        if (resolved is KaVariableAccessCall) return@observedAnalyze namedComponentTarget(resolved)
        val kind = nativeComponentReceiverKind(entry) ?: return@observedAnalyze componentRejected()
        val symbol = resolved?.signature?.symbol as? KaNamedFunctionSymbol ?: return@observedAnalyze componentRejected()
        if (symbol.returnType is KaErrorType) return@observedAnalyze componentRejected()
        if (symbol.origin == KaSymbolOrigin.SOURCE_MEMBER_GENERATED)
            return@observedAnalyze generatedComponentProperty(symbol).mapComponentField()
        val function = symbol.psi as? KtNamedFunction ?: return@observedAnalyze componentRejected()
        Refinement.Refined(NativeComponentTarget.Source(function, kind))
    }

private fun KaSession.namedComponentTarget(
    call: KaVariableAccessCall
): Refinement<NativeComponentTarget, ValueFlowUnsupportedCause> {
    val symbol = call.signature.symbol as? KaKotlinPropertySymbol ?: return componentRejected()
    if (call.kind !is KaVariableAccessCall.Kind.Read || call.extensionReceiver != null || call.dispatchReceiver == null)
        return componentRejected()
    val property = symbol.psi as? KtProperty
    return when {
        property?.getter?.bodyExpression != null && !property.hasDelegate() && symbol.returnType !is KaErrorType ->
            Refinement.Refined(NativeComponentTarget.Getter(property))
        else -> namedComponentProperty(symbol).mapComponentField()
    }
}

/** A resolved property must prove its own constructor storage; names and component ordinals are not that proof. */
private fun KaSession.namedComponentProperty(
    symbol: KaKotlinPropertySymbol
): Refinement<KtParameter, ValueFlowUnsupportedCause> =
    when (val parameter = symbol.psi) {
        is KtParameter ->
            if (
                symbol.returnType !is KaErrorType &&
                    (parameter.symbol as? KaValueParameterSymbol)?.generatedPrimaryConstructorProperty == symbol
            )
                Refinement.Refined(parameter)
            else Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
        // Authored getters require callable entry/return ownership, not a stored-field approximation.
        else -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    }

internal fun nativeComponentReceiver(
    entry: KtDestructuringDeclarationEntry,
    target: NativeComponentTarget.Callable,
    executionOwner: ValueExecutionOwner,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueRole.Receiver, ValueFlowUnsupportedCause> {
    val callable =
        when (
            val selected =
                nativeValueCallableEndpoint(
                    declaration = target.declaration,
                    enclosing = executionOwner.enclosing,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    return when (
        val invocation =
            if (target is NativeComponentTarget.Getter)
                ValueInvocation.getterFromCompiler(
                    enclosing = executionOwner.enclosing,
                    range = sourceValueRange(entry),
                    callable = callable,
                    executionOwner = executionOwner,
                )
            else
                ValueInvocation.fromCompiler(
                    enclosing = executionOwner.enclosing,
                    range = sourceValueRange(entry),
                    callable = callable,
                    executionOwner = executionOwner,
                )
    ) {
        is Refinement.Refined -> Refinement.Refined(ValueRole.Receiver(invocation.value, target.receiver))
        is Refinement.Rejected -> componentRejected()
    }
}

internal fun nativeComponentFieldRead(
    source: ValueSite,
    expression: KtExpression,
    parameter: KtParameter,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<NativeValueFieldRead, ValueFlowUnsupportedCause> =
    when (
        val field =
            nativeValueField(
                declaration = parameter,
                enclosing = source.enclosing,
                scope = scope,
                projection = projection,
            )
    ) {
        is Refinement.Refined -> sourceFieldTarget(source, expression, field.value)
        is Refinement.Rejected -> field
    }

internal sealed interface NativeGeneratedComponentRead {
    data object NotGenerated : NativeGeneratedComponentRead

    data class Field(val read: NativeValueFieldRead) : NativeGeneratedComponentRead
}

private sealed interface NativeGeneratedComponentTarget {
    data object NotGenerated : NativeGeneratedComponentTarget

    data class Field(val parameter: KtParameter) : NativeGeneratedComponentTarget
}

/** Explicit generated component calls must project the same field as positional destructuring. */
internal fun nativeGeneratedComponentRead(
    source: ValueSite,
    expression: KtExpression,
    call: KtCallElement,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<NativeGeneratedComponentRead, ValueFlowUnsupportedCause> {
    if (!COMPONENT_NAME.matches((call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName().orEmpty()))
        return Refinement.Refined(NativeGeneratedComponentRead.NotGenerated)
    val target = nativeGeneratedComponentTarget(expression, call, projection)
    val parameter =
        when (target) {
            is Refinement.Rejected -> return target
            is Refinement.Refined ->
                when (val selected = target.value) {
                    NativeGeneratedComponentTarget.NotGenerated ->
                        return Refinement.Refined(NativeGeneratedComponentRead.NotGenerated)
                    is NativeGeneratedComponentTarget.Field -> selected.parameter
                }
        }
    return when (
        val result =
            nativeComponentFieldRead(
                source = source,
                expression = call.valueInvocationExpression() as KtExpression,
                parameter = parameter,
                scope = scope,
                projection = projection,
            )
    ) {
        is Refinement.Refined -> Refinement.Refined(NativeGeneratedComponentRead.Field(result.value))
        is Refinement.Rejected -> result
    }
}

private fun nativeGeneratedComponentTarget(
    expression: KtExpression,
    call: KtCallElement,
    projection: IntellijK2RelationProjection,
): Refinement<NativeGeneratedComponentTarget, ValueFlowUnsupportedCause> =
    projection.observation.observedAnalyze(call) {
        val resolved = call.resolveCall() ?: return@observedAnalyze componentRejected<NativeGeneratedComponentTarget>()
        val symbol =
            resolved.signature.symbol as? KaNamedFunctionSymbol
                ?: return@observedAnalyze componentRejected<NativeGeneratedComponentTarget>()
        if (symbol.origin != KaSymbolOrigin.SOURCE_MEMBER_GENERATED)
            return@observedAnalyze Refinement.Refined(NativeGeneratedComponentTarget.NotGenerated)
        val qualified =
            call.valueInvocationExpression() as? KtQualifiedExpression
                ?: return@observedAnalyze componentRejected<NativeGeneratedComponentTarget>()
        if (
            qualified.receiverExpression !== expression ||
                nativeExplicitReceiverKind(expression, resolved.extensionReceiver, resolved.dispatchReceiver) !=
                    ValueReceiverKind.DISPATCH
        )
            return@observedAnalyze componentRejected<NativeGeneratedComponentTarget>()
        when (val property = generatedComponentProperty(symbol)) {
            is Refinement.Refined -> Refinement.Refined(NativeGeneratedComponentTarget.Field(property.value))
            is Refinement.Rejected -> property
        }
    }

/** Origin and owner prove compiler synthesis before its specified component ordinal selects constructor storage. */
private fun KaSession.generatedComponentProperty(
    symbol: KaNamedFunctionSymbol
): Refinement<KtParameter, ValueFlowUnsupportedCause> {
    if (
        symbol.origin != KaSymbolOrigin.SOURCE_MEMBER_GENERATED ||
            !symbol.isOperator ||
            symbol.valueParameters.isNotEmpty()
    )
        return componentRejected()
    val owner = symbol.containingDeclaration ?: return componentRejected()
    val klass = owner.psi as? KtClass ?: return componentRejected()
    if (!klass.isData() || klass.symbol != owner) return componentRejected()
    val index =
        COMPONENT_NAME.matchEntire(symbol.name.asString())?.groupValues?.get(1)?.toIntOrNull()
            ?: return componentRejected()
    val parameter = klass.primaryConstructorParameters.getOrNull(index - 1) ?: return componentRejected()
    val property =
        (parameter.symbol as? KaValueParameterSymbol)?.generatedPrimaryConstructorProperty ?: return componentRejected()
    if (property.containingDeclaration != owner) return componentRejected()
    return Refinement.Refined(parameter)
}

private fun Refinement<KtParameter, ValueFlowUnsupportedCause>.mapComponentField():
    Refinement<NativeComponentTarget, ValueFlowUnsupportedCause> =
    when (this) {
        is Refinement.Refined -> Refinement.Refined(NativeComponentTarget.Field(value))
        is Refinement.Rejected -> this
    }

private fun <T> componentRejected(): Refinement<T, ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)

private val COMPONENT_NAME = Regex("component([1-9][0-9]*)")
