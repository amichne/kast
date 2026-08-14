package io.github.amichne.kast.idea.backend.workspace

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.symbol.intellij.IntellijInvocationReferenceAdmission
import io.github.amichne.kast.symbol.intellij.IntellijNestedRelationTraversal
import io.github.amichne.kast.symbol.intellij.IntellijRelationSemanticPolicy
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression

internal data object IntellijKotlinRelationSemantics : IntellijRelationSemanticPolicy {
    /**
     * Proof transition: `PsiReference -> IntellijInvocationReferenceAdmission`.
     *
     * Establishes Kotlin/Java call or constructor syntax around the resolved reference. Callable
     * references, types, annotations outside call syntax, properties, and other value uses are
     * proven non-invocations. Unknown language PSI remains the closed unsupported state.
     */
    override fun invocation(reference: PsiReference): IntellijInvocationReferenceAdmission {
        val element = reference.element
        if (element is KtOperationReferenceExpression) {
            return IntellijInvocationReferenceAdmission.INVOCATION
        }
        element.parentsWithSelf().filterIsInstance<KtCallElement>().firstOrNull()?.let { call ->
            val callee = call.calleeExpression
            return if (callee != null && PsiTreeUtil.isAncestor(callee, element, false)) {
                IntellijInvocationReferenceAdmission.INVOCATION
            } else {
                IntellijInvocationReferenceAdmission.NON_INVOCATION
            }
        }
        if (element.containingFile is KtFile) {
            return IntellijInvocationReferenceAdmission.NON_INVOCATION
        }
        element.parentsWithSelf().filterIsInstance<PsiMethodCallExpression>()
            .firstOrNull()
            ?.let { call ->
                return if (PsiTreeUtil.isAncestor(call.methodExpression, element, false)) {
                    IntellijInvocationReferenceAdmission.INVOCATION
                } else {
                    IntellijInvocationReferenceAdmission.NON_INVOCATION
                }
            }
        element.parentsWithSelf().filterIsInstance<PsiNewExpression>().firstOrNull()?.let { call ->
            return if (
                call.classReference?.let { referenceElement ->
                    PsiTreeUtil.isAncestor(referenceElement, element, false)
                } == true
            ) {
                IntellijInvocationReferenceAdmission.INVOCATION
            } else {
                IntellijInvocationReferenceAdmission.NON_INVOCATION
            }
        }
        return if (element.containingFile is PsiJavaFile) {
            IntellijInvocationReferenceAdmission.NON_INVOCATION
        } else {
            IntellijInvocationReferenceAdmission.UNSUPPORTED
        }
    }

    /**
     * Proof transition: `PsiNamedElement + PsiElement -> IntellijNestedRelationTraversal`.
     *
     * Establishes that Kotlin functions/classes and Java methods/classes nested below the selected
     * subject own their own reference bodies and cannot contribute direct callees to the subject.
     */
    override fun nestedTraversal(
        subject: PsiNamedElement,
        element: PsiElement,
    ): IntellijNestedRelationTraversal =
        if (element === subject) {
            IntellijNestedRelationTraversal.DESCEND
        } else {
            when (element) {
                is KtNamedFunction,
                is KtClassOrObject,
                is PsiMethod,
                is PsiClass,
                    -> IntellijNestedRelationTraversal.SKIP_NESTED_DECLARATION
                else -> IntellijNestedRelationTraversal.DESCEND
            }
        }
}

private fun PsiElement.parentsWithSelf(): Sequence<PsiElement> =
    generateSequence(this) { element -> element.parent }
