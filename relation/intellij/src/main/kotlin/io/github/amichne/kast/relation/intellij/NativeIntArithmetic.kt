@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.IntArithmeticInput
import io.github.amichne.kast.relation.contract.IntArithmeticRule
import io.github.amichne.kast.relation.contract.IntBuiltinEvidence
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueIntArithmetic
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaBuiltinsModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiUtil

/** Syntax selects candidates only. Both syntaxes use the same selected callable and operand binding. */
internal class NativeIntArithmetic(
    private val source: ValueSite,
    private val observation: IntellijReadObservation,
    private val retained: NativeValueFlowTransfers,
) {
    fun read(input: KtExpression, result: KtExpression): Boolean {
        if (!result.isIntArithmeticCandidate()) return false
        return when (val selected = observation.observedAnalyze(result) { select(input, result) }) {
            is IntArithmeticSelection.Admitted -> {
                retained.emit(
                    result,
                    ValueRole.ExpressionResult,
                    ValueTransferKind.PRIMITIVE_DERIVATION,
                    selected.proof,
                )
                true
            }
            IntArithmeticSelection.Unresolved -> {
                retained.unresolvedAt(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE, result)
                true
            }
            IntArithmeticSelection.Other -> false
        }
    }

    private fun KaSession.select(input: KtExpression, result: KtExpression): IntArithmeticSelection {
        val call = resolveValueExpressionCall(result) ?: return IntArithmeticSelection.Unresolved
        if (call !is KaFunctionCall<*>) return IntArithmeticSelection.Other
        val symbol = call.signature.symbol as? KaNamedFunctionSymbol ?: return IntArithmeticSelection.Other
        val authority = intBuiltinEvidence(symbol) ?: return IntArithmeticSelection.Other
        val receiver = intMemberReceiver(call) ?: return IntArithmeticSelection.Other
        val slot = call.arithmeticInput(input, receiver) ?: return IntArithmeticSelection.Other
        val callable =
            (sourceLessCallable(symbol) as? IntellijK2ResolvedDeclaration.SourceLess)?.callable
                ?: return IntArithmeticSelection.Other
        return when (
            val admitted =
                ValueIntArithmetic.fromCompiler(
                    callable = callable,
                    authority = authority,
                    input = slot,
                    inputRange = source.range,
                    resultRange = sourceValueRange(result),
                    execution = source.executionOwner,
                )
        ) {
            is Refinement.Refined -> IntArithmeticSelection.Admitted(admitted.value)
            is Refinement.Rejected -> IntArithmeticSelection.Other
        }
    }
}

private sealed interface IntArithmeticSelection {
    data class Admitted(val proof: ValueIntArithmetic) : IntArithmeticSelection

    data object Unresolved : IntArithmeticSelection

    data object Other : IntArithmeticSelection
}

private fun KtExpression.isIntArithmeticCandidate(): Boolean =
    when (this) {
        is KtBinaryExpression -> operationToken in INT_ARITHMETIC_TOKENS
        is KtDotQualifiedExpression -> (selectorExpression as? KtCallExpression)?.isIntArithmeticCandidate() == true
        is KtCallExpression ->
            (calleeExpression as? KtNameReferenceExpression)?.getReferencedName() in INT_ARITHMETIC_NAMES
        else -> false
    }

private val INT_ARITHMETIC_TOKENS = setOf(KtTokens.MINUS, KtTokens.PLUS, KtTokens.MUL)
private val INT_ARITHMETIC_NAMES = IntArithmeticRule.entries.map { it.callableId.substringAfterLast('.') }.toSet()

private fun KaSession.intBuiltinEvidence(symbol: KaNamedFunctionSymbol): IntBuiltinEvidence? {
    if (
        !symbol.isOperator ||
            symbol.origin != KaSymbolOrigin.LIBRARY ||
            symbol.containingDeclaration != (builtinTypes.int as? KaClassType)?.symbol
    )
        return null
    return when (symbol.containingModule) {
        is KaBuiltinsModule -> IntBuiltinEvidence.K2_BUILTINS_MODULE
        is KaLibraryModule ->
            if (symbol.psi?.containingFile?.virtualFile?.url?.endsWith("!/kotlin/kotlin.kotlin_builtins") == true)
                IntBuiltinEvidence.K2_BUILTINS_METADATA
            else null
        else -> null
    }
}

private fun KaSession.intMemberReceiver(call: KaFunctionCall<*>): KaExplicitReceiverValue? {
    val receiver = call.dispatchReceiver as? KaExplicitReceiverValue ?: return null
    if (call.extensionReceiver != null) return null
    val result = call.signature.returnType
    if (!result.isIntType || result.isMarkedNullable) return null
    return receiver.takeIf { it.type.isIntType && !it.type.isMarkedNullable }
}

private fun KaFunctionCall<*>.arithmeticInput(
    input: KtExpression,
    receiver: KaExplicitReceiverValue,
): IntArithmeticInput? {
    if (sameArithmeticOperand(receiver.expression, input)) return IntArithmeticInput.DISPATCH_RECEIVER
    val parameter = signature.symbol.valueParameters.singleOrNull() ?: return null
    return if (
        valueArgumentMapping.entries.singleOrNull()?.let { (actual, formal) ->
            formal.symbol == parameter && sameArithmeticOperand(actual, input)
        } == true
    )
        IntArithmeticInput.ARGUMENT_0
    else null
}

private fun sameArithmeticOperand(first: KtExpression, second: KtExpression): Boolean =
    KtPsiUtil.deparenthesize(first) === KtPsiUtil.deparenthesize(second)
