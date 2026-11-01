@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueStringConversionOrigin
import io.github.amichne.kast.relation.contract.ValueStringConversionSelection
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Only the applicable Any member can supply implicit string conversion. Extensions supply no candidate. */
internal fun nativeStringConversionDeclaration(
    expression: KtExpression,
    projection: IntellijK2RelationProjection,
): Refinement<KtNamedFunction, NativeStringConversionFailure> =
    projection.observation.observedAnalyze(expression) {
        val type = expression.expressionType as? KaClassType ?: return@observedAnalyze conversionRejected()
        if (type.isMarkedNullable || type.classId.asSingleFqName().asString() == "kotlin.String")
            return@observedAnalyze conversionRejected()
        val owner = type.symbol as? KaClassSymbol ?: return@observedAnalyze conversionRejected()
        sourceStringConversion(owner)
    }

private fun KaSession.sourceStringConversion(
    owner: KaClassSymbol
): Refinement<KtNamedFunction, NativeStringConversionFailure> {
    val selected =
        owner.memberScope
            .callables(Name.identifier("toString"))
            .filterIsInstance<KaNamedFunctionSymbol>()
            .filter { it.valueParameters.isEmpty() && it.receiverParameter == null }
            .singleOrNull() ?: return conversionRejected()
    if (!isAnyMember(selected)) return conversionRejected()
    return admittedStringConversion(selected)
}

private fun KaSession.isStringResult(selected: KaNamedFunctionSymbol): Boolean {
    val result = selected.returnType as? KaClassType ?: return false
    return !result.isMarkedNullable && result.classId.asSingleFqName().asString() == "kotlin.String"
}

private fun KaSession.admittedStringConversion(
    selected: KaNamedFunctionSymbol
): Refinement<KtNamedFunction, NativeStringConversionFailure> {
    val selection =
        when (
            val signature =
                selected.functionSignature(
                    selected.callableId?.asSingleFqName()?.asString() ?: return conversionRejected()
                )
        ) {
            is Refinement.Rejected -> return conversionRejected()
            is Refinement.Refined ->
                when (
                    val target =
                        ValueStringConversionSelection.fromCompiler(
                            signature.value
                                as? io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature.Function
                                ?: return conversionRejected(),
                            conversionOrigin(selected.origin),
                        )
                ) {
                    is Refinement.Refined -> target.value
                    is Refinement.Rejected -> return conversionRejected()
                }
        }
    if (selected.origin != KaSymbolOrigin.SOURCE)
        return Refinement.Rejected(
            NativeStringConversionFailure.Selected(conversionAuthorityFailure(selected.origin), selection)
        )
    val function =
        selected.psi as? KtNamedFunction
            ?: return Refinement.Rejected(
                NativeStringConversionFailure.Selected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, selection)
            )
    return if (isStringResult(selected)) Refinement.Refined(function)
    else
        Refinement.Rejected(
            NativeStringConversionFailure.Selected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, selection)
        )
}

private fun KaSession.isAnyMember(selected: KaNamedFunctionSymbol): Boolean =
    selected.callableId?.asSingleFqName()?.asString() == "kotlin.Any.toString" ||
        selected.allOverriddenSymbols.any { it.callableId?.asSingleFqName()?.asString() == "kotlin.Any.toString" }

private fun conversionAuthorityFailure(origin: KaSymbolOrigin): ValueFlowUnsupportedCause =
    if (origin == KaSymbolOrigin.LIBRARY || origin == KaSymbolOrigin.JAVA_LIBRARY)
        ValueFlowUnsupportedCause.EXTERNAL_CALL
    else ValueFlowUnsupportedCause.UNMODELED_CALL

internal fun nativeStringConversionReceiver(
    source: ValueSite,
    expression: KtExpression,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    retained: NativeValueFlowTransfers,
) {
    val function =
        when (val read = nativeStringConversionDeclaration(expression, projection)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> {
                retained.unresolvedStringConversion(read.failure, expression)
                return
            }
        }
    val endpoint =
        when (val read = nativeValueCallableEndpoint(function, source.enclosing, scope, projection)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> {
                retained.unresolvedAt(read.failure, expression.parent)
                return
            }
        }
    when (
        val invocation =
            ValueInvocation.fromCompiler(
                enclosing = source.enclosing,
                range = sourceValueRange(expression),
                callable = endpoint,
                executionOwner = source.executionOwner,
            )
    ) {
        is Refinement.Refined ->
            retained.emit(
                expression,
                ValueRole.Receiver(invocation.value, ValueReceiverKind.DISPATCH),
                ValueTransferKind.RECEIVER,
            )
        is Refinement.Rejected ->
            retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, expression.parent)
    }
}

private fun conversionRejected(): Refinement.Rejected<NativeStringConversionFailure> =
    Refinement.Rejected(NativeStringConversionFailure.Unavailable(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION))

internal sealed interface NativeStringConversionFailure {
    val cause: ValueFlowUnsupportedCause

    data class Unavailable(override val cause: ValueFlowUnsupportedCause) : NativeStringConversionFailure

    data class Selected(override val cause: ValueFlowUnsupportedCause, val selection: ValueStringConversionSelection) :
        NativeStringConversionFailure
}

private fun conversionOrigin(origin: KaSymbolOrigin): ValueStringConversionOrigin =
    when (origin) {
        KaSymbolOrigin.SOURCE -> ValueStringConversionOrigin.SOURCE
        KaSymbolOrigin.SOURCE_MEMBER_GENERATED -> ValueStringConversionOrigin.SOURCE_MEMBER_GENERATED
        KaSymbolOrigin.LIBRARY -> ValueStringConversionOrigin.LIBRARY
        KaSymbolOrigin.JAVA_SOURCE -> ValueStringConversionOrigin.JAVA_SOURCE
        KaSymbolOrigin.JAVA_LIBRARY -> ValueStringConversionOrigin.JAVA_LIBRARY
        KaSymbolOrigin.SAM_CONSTRUCTOR -> ValueStringConversionOrigin.SAM_CONSTRUCTOR
        KaSymbolOrigin.TYPEALIASED_CONSTRUCTOR -> ValueStringConversionOrigin.TYPEALIASED_CONSTRUCTOR
        KaSymbolOrigin.INTERSECTION_OVERRIDE -> ValueStringConversionOrigin.INTERSECTION_OVERRIDE
        KaSymbolOrigin.SUBSTITUTION_OVERRIDE -> ValueStringConversionOrigin.SUBSTITUTION_OVERRIDE
        KaSymbolOrigin.DELEGATED -> ValueStringConversionOrigin.DELEGATED
        KaSymbolOrigin.JAVA_SYNTHETIC_PROPERTY -> ValueStringConversionOrigin.JAVA_SYNTHETIC_PROPERTY
        KaSymbolOrigin.PROPERTY_BACKING_FIELD -> ValueStringConversionOrigin.PROPERTY_BACKING_FIELD
        KaSymbolOrigin.PLUGIN -> ValueStringConversionOrigin.PLUGIN
        KaSymbolOrigin.JS_DYNAMIC -> ValueStringConversionOrigin.JS_DYNAMIC
        KaSymbolOrigin.NATIVE_FORWARD_DECLARATION -> ValueStringConversionOrigin.NATIVE_FORWARD_DECLARATION
    }
