@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Exhaustive callers of a factory are inventory evidence; a callable reference escape is a finite failure. */
internal fun callbackFactoryCalls(
    function: KtNamedFunction,
    context: IntellijCallbackFlowContext,
    summaries: CallbackParameterSummaries,
): Refinement<List<KtCallExpression>, CallbackInvocationFlowCause> {
    val references =
        when (val collected = CallbackFactoryReferenceCollector(context, summaries).collect(function)) {
            is Refinement.Refined -> collected.value
            is Refinement.Rejected -> return collected
        }
    val calls = linkedSetOf<KtCallExpression>()
    for (reference in references) {
        when (val permit = context.permit()) {
            is Refinement.Rejected -> return permit
            is Refinement.Refined -> Unit
        }
        if (PsiTreeUtil.getParentOfType(reference.element, KtImportDirective::class.java, false) != null) continue
        val call =
            PsiTreeUtil.getParentOfType(reference.element, KtCallExpression::class.java, false)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        if (call.calleeExpression?.textRange?.contains(reference.element.textRange) != true)
            return Refinement.Rejected(CallbackInvocationFlowCause.PARAMETER_ESCAPES)
        val exact =
            context.observation.observedAnalyze(call) {
                val symbol = call.resolveCall()?.signature?.symbol as? KaNamedFunctionSymbol
                symbol?.psi?.originalElement == function.originalElement
            }
        if (exact) calls += call
        else return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
    }
    return Refinement.Refined(calls.toList())
}

/** Native processor booleans terminate enumeration; the closed rejection is retained separately. */
private class CallbackFactoryReferenceCollector(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    private val references = mutableListOf<PsiReference>()
    private var outcome: Refinement<Unit, CallbackInvocationFlowCause> = Refinement.Refined(Unit)

    fun collect(function: KtNamedFunction): Refinement<List<PsiReference>, CallbackInvocationFlowCause> {
        val admission =
            NativeRelationScopeAdmission(context.observation) {
                when (val admitted = context.admitNativeScope()) {
                    is Refinement.Refined -> IntellijRelationProviderEnumerationAdmission.READY
                    is Refinement.Rejected -> {
                        outcome = admitted
                        IntellijRelationProviderEnumerationAdmission.HALTED
                    }
                }
            }
        val exhausted =
            context.observation.forEachReference(function, context.scope.nativeScope, admission, false, ::process)
        return when (val admitted = outcome) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined ->
                if (exhausted) Refinement.Refined(references)
                else Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun process(reference: PsiReference): Boolean {
        val site =
            when (val admitted = context.providerSite { reference.element.containingFile?.virtualFile }) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return reject(admitted.failure)
            }
        when (site) {
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
            RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED -> return true
            RelationProviderScopeAdmission.UNAVAILABLE -> return reject(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
            RelationProviderScopeAdmission.ADMITTED -> Unit
        }
        when (val permit = context.permit()) {
            is Refinement.Rejected -> return reject(permit.failure)
            is Refinement.Refined -> Unit
        }
        when (val retained = summaries.retention.admit(FACTORY_REFERENCE_RETAINED_BYTES)) {
            is Refinement.Rejected -> return reject(retained.failure)
            is Refinement.Refined -> Unit
        }
        references += reference
        return true
    }

    private fun reject(cause: CallbackInvocationFlowCause): Boolean {
        outcome = Refinement.Rejected(cause)
        return false
    }
}

private const val FACTORY_REFERENCE_RETAINED_BYTES = 256L
