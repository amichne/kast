package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiTypeElement
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import org.jetbrains.kotlin.psi.KtFileAnnotationList
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtTypeReference

/** Context observes syntax only; it grants neither target nor owner identity. */
internal fun PsiElement.referenceContext(): RelationReferenceContext {
    var context = RelationReferenceContext.CODE
    for (current in generateSequence(this as PsiElement?) { it.parent }) {
        when (current) {
            is KtImportDirective ->
                return if (current.aliasName == null) RelationReferenceContext.IMPORT
                else RelationReferenceContext.ALIASED_IMPORT
            is PsiImportStatementBase -> return RelationReferenceContext.IMPORT
            is KtFileAnnotationList -> return RelationReferenceContext.FILE_ANNOTATION
            is KtTypeReference,
            is PsiTypeElement -> context = RelationReferenceContext.TYPE
        }
    }
    return context
}

internal fun RelationReferenceContext.isFileScoped(): Boolean =
    when (this) {
        RelationReferenceContext.IMPORT,
        RelationReferenceContext.ALIASED_IMPORT,
        RelationReferenceContext.FILE_ANNOTATION -> true
        RelationReferenceContext.TYPE,
        RelationReferenceContext.CODE -> false
    }
