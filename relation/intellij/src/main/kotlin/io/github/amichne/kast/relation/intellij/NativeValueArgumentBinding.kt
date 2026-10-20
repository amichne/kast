@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression

internal data class NativeArgument(
    val declaration: PsiNamedElement,
    val position: ValueArgumentPosition,
    val origin: KaSymbolOrigin,
)

internal enum class NativeArgumentBindingFailure {
    UNRESOLVED_REFERENCE,
    EXTERNAL_CALL,
}

/** K2 owns the actual-to-formal position for both declared calls and genuine builtin function invocations. */
internal fun nativeArgumentBinding(
    call: KtCallElement,
    expression: KtExpression,
    observation: IntellijReadObservation,
): Refinement<NativeArgument, NativeArgumentBindingFailure> =
    observation.observedAnalyze(call) {
        val resolved =
            call.resolveCall()
                ?: return@observedAnalyze Refinement.Rejected(NativeArgumentBindingFailure.UNRESOLVED_REFERENCE)
        val callable =
            when (val candidate = resolved.signature.symbol) {
                is KaNamedFunctionSymbol -> candidate
                is KaConstructorSymbol -> candidate
                else -> return@observedAnalyze Refinement.Rejected(NativeArgumentBindingFailure.UNRESOLVED_REFERENCE)
            }
        val parameter =
            resolved.valueArgumentMapping[expression]?.symbol
                ?: return@observedAnalyze Refinement.Rejected(NativeArgumentBindingFailure.UNRESOLVED_REFERENCE)
        val position =
            when (val value = ValueArgumentPosition.parse(callable.valueParameters.indexOf(parameter))) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected ->
                    return@observedAnalyze Refinement.Rejected(NativeArgumentBindingFailure.UNRESOLVED_REFERENCE)
            }
        val declaration =
            callable.psi as? PsiNamedElement
                ?: return@observedAnalyze Refinement.Rejected(NativeArgumentBindingFailure.EXTERNAL_CALL)
        Refinement.Refined(NativeArgument(declaration, position, callable.origin))
    }
