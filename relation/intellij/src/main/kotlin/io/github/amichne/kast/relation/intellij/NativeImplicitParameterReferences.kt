@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LocalReferenceTraversal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/** An implicit parameter has no declaration PSI; each candidate resolves against its freshly acquired K2 symbol. */
internal class NativeImplicitParameterReferences(
    private val literal: KtFunctionLiteral,
    private val formal: ValueFormalParameter,
    private val observation: IntellijReadObservation,
) {
    fun admit(): Refinement<Unit, ValueFlowUnsupportedCause> =
        observation.observedAnalyze(literal) {
            val parameter = literal.symbol.valueParameters.getOrNull(formal.position.value)
            if (parameter?.isImplicitLambdaParameter == true && parameter.name.asString() == formal.name.value)
                Refinement.Refined(Unit)
            else Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        }

    fun enumerate(visit: LocalReferenceTraversal<PsiReference>) {
        PsiTreeUtil.processElements(literal) { element ->
            if (!visit.admitTraversal()) return@processElements false
            val name = element as? KtNameReferenceExpression ?: return@processElements true
            if (name.getReferencedName() != formal.name.value) return@processElements true
            for (reference in name.references) {
                if (reference is KtReference && !visit(reference)) return@processElements false
            }
            true
        }
    }

    fun confirm(reference: KtReference): LocalBindingReferenceConfirmation =
        observation.observedAnalyze(reference.element) {
            val parameter = literal.symbol.valueParameters.getOrNull(formal.position.value)
            val resolved = reference.resolveToSymbol()
            when {
                parameter?.isImplicitLambdaParameter != true || parameter.name.asString() != formal.name.value ->
                    LocalBindingReferenceConfirmation.UNRESOLVED
                resolved == null -> LocalBindingReferenceConfirmation.UNRESOLVED
                resolved == parameter -> LocalBindingReferenceConfirmation.EXACT_BINDING
                else -> LocalBindingReferenceConfirmation.OTHER_BINDING
            }
        }
}
