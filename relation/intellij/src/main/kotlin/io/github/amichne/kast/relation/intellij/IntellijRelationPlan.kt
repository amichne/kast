package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTypeReference

internal enum class IntellijExactReferenceShape {
    ANY,
    CALL,
    TYPE,
}

internal enum class IntellijDefinitionRelation {
    IMPLEMENTATIONS,
    INHERITORS,
    OVERRIDES,
}

internal sealed interface IntellijRelationPlanKind {
    data class ExactReferences(
        val shape: IntellijExactReferenceShape,
    ) : IntellijRelationPlanKind

    data object ClassConstructionCallers : IntellijRelationPlanKind
    data object Callees : IntellijRelationPlanKind

    data class Definitions(
        val relation: IntellijDefinitionRelation,
    ) : IntellijRelationPlanKind

    companion object {
        /**
         * Proof transition: `(RelationMeaning, CompilerSymbolKind) -> IntellijRelationPlanKind`.
         *
         * Establishes one request-local enumeration and K2-confirmation policy. A classlike caller
         * request gains constructor-ownership confirmation; every other reference request retains
         * exact-symbol confirmation. Raw meaning and kind extraction ends at relation planning.
         */
        fun derive(
            meaning: RelationMeaning,
            subjectKind: CompilerSymbolKind,
        ): IntellijRelationPlanKind = when (meaning) {
            RelationMeaning.References -> ExactReferences(IntellijExactReferenceShape.ANY)
            RelationMeaning.Callers -> if (subjectKind == CompilerSymbolKind.CLASSLIKE) {
                ClassConstructionCallers
            } else {
                ExactReferences(IntellijExactReferenceShape.CALL)
            }
            RelationMeaning.TypeUses -> ExactReferences(IntellijExactReferenceShape.TYPE)
            RelationMeaning.Callees -> Callees
            RelationMeaning.Implementations -> Definitions(
                IntellijDefinitionRelation.IMPLEMENTATIONS,
            )
            RelationMeaning.Inheritors -> Definitions(IntellijDefinitionRelation.INHERITORS)
            RelationMeaning.Overrides -> Definitions(IntellijDefinitionRelation.OVERRIDES)
        }
    }
}

internal sealed interface IntellijRelationPlan {
    val subject: KtNamedDeclaration

    data class References(
        override val subject: KtNamedDeclaration,
        val endpoint: RelationEndpoint,
        val confirmation: IntellijReferenceConfirmationPlan,
    ) : IntellijRelationPlan

    data class Callees(
        override val subject: KtNamedDeclaration,
    ) : IntellijRelationPlan

    data class Definitions(
        override val subject: KtNamedDeclaration,
        val relation: IntellijDefinitionRelation,
    ) : IntellijRelationPlan
}

internal sealed interface IntellijReferenceConfirmationPlan {
    data class ExactSymbol(
        val shape: IntellijExactReferenceShape,
    ) : IntellijReferenceConfirmationPlan

    data object ClassConstruction : IntellijReferenceConfirmationPlan
}

internal sealed interface IntellijRelationReferenceAdmission {
    data object Skipped : IntellijRelationReferenceAdmission

    sealed interface Admitted : IntellijRelationReferenceAdmission {
        val reference: KtReference

        data class ExactSymbol(
            override val reference: KtReference,
            val endpoint: RelationEndpoint,
        ) : Admitted

        class ClassConstruction private constructor(
            override val reference: KtReference,
            val selectedClass: KtNamedDeclaration,
        ) : Admitted {
            companion object {
                /**
                 * Proof transition: `(KtReference, KtNamedDeclaration) ->
                 * IntellijRelationReferenceAdmission`.
                 *
                 * An admitted result establishes that the reference occupies the callee range of
                 * a Kotlin call. Skipped is the closed non-call shape. Raw PSI remains inside the
                 * request-local relation adapter and may be read only by K2 confirmation.
                 */
                fun admit(
                    reference: KtReference,
                    selectedClass: KtNamedDeclaration,
                ): IntellijRelationReferenceAdmission = if (reference.element.isCallCallee()) {
                    ClassConstruction(reference, selectedClass)
                } else {
                    Skipped
                }
            }
        }
    }
}

/**
 * Proof transition: `(IntellijRelationSubjectLookup.Found, RelationRequest) ->
 * IntellijRelationPlan`.
 *
 * Establishes a closed request-local plan only after exact PSI and K2 subject revalidation. The
 * plan preserves the selected declaration and exact endpoint through enumeration and confirmation.
 * Live PSI may be extracted only by the request-local search and K2 projection.
 */
internal fun IntellijRelationSubjectLookup.Found.plan(request: RelationRequest): IntellijRelationPlan =
    when (val kind = IntellijRelationPlanKind.derive(request.meaning, evidence.kind)) {
        is IntellijRelationPlanKind.ExactReferences -> IntellijRelationPlan.References(
            declaration,
            request.subject,
            IntellijReferenceConfirmationPlan.ExactSymbol(kind.shape),
        )
        IntellijRelationPlanKind.ClassConstructionCallers -> IntellijRelationPlan.References(
            declaration,
            request.subject,
            IntellijReferenceConfirmationPlan.ClassConstruction,
        )
        IntellijRelationPlanKind.Callees -> IntellijRelationPlan.Callees(declaration)
        is IntellijRelationPlanKind.Definitions -> IntellijRelationPlan.Definitions(
            declaration,
            kind.relation,
        )
    }

/**
 * Proof transition: `(IntellijRelationPlan.References, KtReference) ->
 * IntellijRelationReferenceAdmission`.
 *
 * Admitted variants carry the exact proof required by their K2 confirmation policy. Skipped is a
 * closed nonmatching PSI shape. Raw PSI remains inside the request-local relation adapter.
 */
internal fun IntellijRelationPlan.References.admit(
    reference: KtReference,
): IntellijRelationReferenceAdmission = when (val plan = confirmation) {
    is IntellijReferenceConfirmationPlan.ExactSymbol -> if (
        plan.shape.admits(reference.element)
    ) {
        IntellijRelationReferenceAdmission.Admitted.ExactSymbol(reference, endpoint)
    } else {
        IntellijRelationReferenceAdmission.Skipped
    }
    IntellijReferenceConfirmationPlan.ClassConstruction ->
        IntellijRelationReferenceAdmission.Admitted.ClassConstruction.admit(reference, subject)
}

private fun IntellijExactReferenceShape.admits(element: PsiElement): Boolean = when (this) {
    IntellijExactReferenceShape.ANY -> true
    IntellijExactReferenceShape.CALL -> element.isCallCallee()
    IntellijExactReferenceShape.TYPE ->
        PsiTreeUtil.getParentOfType(element, KtTypeReference::class.java, false) != null
}

private fun PsiElement.isCallCallee(): Boolean {
    val call = PsiTreeUtil.getParentOfType(this, KtCallElement::class.java, false) ?: return false
    return call.calleeExpression?.textRange?.contains(textRange) == true
}
