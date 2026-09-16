package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiUtilCore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal enum class CallOwnershipFailure(val limitation: RelationLimitation) {
    UNSUPPORTED_BOUNDARY(RelationLimitation.UNSUPPORTED_ITEM),
    UNRESOLVED_ARGUMENT_MAPPING(RelationLimitation.UNRESOLVED_TARGET),
}

internal sealed interface ContainingDeclaration {
    data class Found(val declaration: PsiNamedElement) : ContainingDeclaration

    data class Deferred(val boundary: PsiElement) : ContainingDeclaration

    data object Unsupported : ContainingDeclaration
}

internal sealed interface SupportedContainingDeclaration {
    data class Found(val projection: IntellijRelationDeclarationProjection.Projected) : SupportedContainingDeclaration

    data object Unsupported : SupportedContainingDeclaration

    data object Unresolved : SupportedContainingDeclaration
}

internal sealed interface OccurrenceProvenance {
    data class Found(val provenance: RelationProvenance) : OccurrenceProvenance

    data object Unsupported : OccurrenceProvenance
}

internal sealed interface KotlinCallReferences {
    data class Found(val references: List<KtReference>) : KotlinCallReferences

    data object Unresolved : KotlinCallReferences
}

internal sealed interface CalleeProviderItem {
    val owner: ContainingDeclaration

    data class Unresolved(val call: KtCallElement, override val owner: ContainingDeclaration) : CalleeProviderItem

    data class Reference(val reference: KtReference, override val owner: ContainingDeclaration) : CalleeProviderItem
}

internal fun CalleeProviderItem.descriptor(): RelationProviderItemDescriptor =
    when (this) {
        is CalleeProviderItem.Unresolved ->
            providerItemDescriptor(
                call,
                call.textRange.shiftLeft(call.textRange.startOffset),
                "unresolved-call",
            )
        is CalleeProviderItem.Reference ->
            providerItemDescriptor(
                reference.element,
                reference.rangeInElement,
                "callee-reference:${reference.javaClass.name}",
            )
    }

internal enum class ProviderTermination {
    TERMINAL,
    HALTED,
}

internal enum class ProviderItemDisposition {
    READY,
    SKIPPED,
    HALTED,
}

internal fun providerItemDescriptor(
    element: PsiElement,
    relativeRange: com.intellij.openapi.util.TextRange,
    discriminator: String,
): RelationProviderItemDescriptor {
    val file = PsiUtilCore.getVirtualFile(element)?.url ?: "detached:${element.containingFile?.name}"
    val start = element.textRange.startOffset + relativeRange.startOffset
    val end = element.textRange.startOffset + relativeRange.endOffset
    val raw = "$discriminator\u0000$file\u0000$start\u0000$end"
    return when (val parsed = RelationProviderItemDescriptor.parse(raw)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("A native provider descriptor is never blank")
    }
}

/** Local initializers execute in their enclosing callable; nested callable bodies keep their own owner. */
internal fun PsiElement.nearestDeclaration(
    observation: IntellijReadObservation = IntellijReadObservation.None
): ContainingDeclaration =
    lexicalDeclaration().also { owner ->
        when (owner) {
            is ContainingDeclaration.Found -> observation.count(IntellijReadCounter.RELATION_CALL_OWNERS_FOUND)
            is ContainingDeclaration.Deferred,
            ContainingDeclaration.Unsupported -> {
                observation.count(IntellijReadCounter.RELATION_CALL_OWNERS_UNAVAILABLE)
                observation.terminated(IntellijReadTermination.RELATION_CALL_OWNER_UNSUPPORTED)
            }
        }
    }

private fun PsiElement.lexicalDeclaration(): ContainingDeclaration {
    for (element in generateSequence(this as PsiElement?) { it.parent }) {
        when (element) {
            is org.jetbrains.kotlin.psi.KtFunctionLiteral,
            is org.jetbrains.kotlin.psi.KtPropertyAccessor -> return ContainingDeclaration.Deferred(element)
            is org.jetbrains.kotlin.psi.KtProperty -> if (!element.isLocal) return ContainingDeclaration.Found(element)
            is org.jetbrains.kotlin.psi.KtParameter -> Unit
            is KtNamedDeclaration -> return ContainingDeclaration.Found(element)
            is com.intellij.psi.PsiMember -> if (element is PsiNamedElement) return ContainingDeclaration.Found(element)
        }
    }
    return ContainingDeclaration.Unsupported
}

/** A deferred body qualifies its lexical owner but cannot authorize an edge from that owner. */
internal fun ContainingDeclaration.Deferred.enclosingDeclaration(): ContainingDeclaration {
    var current: ContainingDeclaration = this
    while (current is ContainingDeclaration.Deferred) {
        current = current.boundary.parent.nearestDeclaration()
    }
    return current
}

/** Both call directions consume the same retained K2 ownership admission. */
internal fun PsiElement.nearestSupportedCallable(
    projection: IntellijK2RelationProjection
): SupportedContainingDeclaration =
    when (val owner = projection.callOwner(nearestDeclaration())) {
        is Refinement.Refined ->
            when (val projected = projection.project(owner.value.declaration)) {
                is IntellijRelationDeclarationProjection.Projected -> SupportedContainingDeclaration.Found(projected)
                IntellijRelationDeclarationProjection.Unsupported -> SupportedContainingDeclaration.Unsupported
            }
        is Refinement.Rejected ->
            when (owner.failure) {
                CallOwnershipFailure.UNSUPPORTED_BOUNDARY -> SupportedContainingDeclaration.Unsupported
                CallOwnershipFailure.UNRESOLVED_ARGUMENT_MAPPING -> SupportedContainingDeclaration.Unresolved
            }
    }

/**
 * Proof transition: `(PsiElement, IntellijK2RelationProjection) -> SupportedContainingDeclaration`.
 *
 * A found result carries the nearest containing named declaration that has already proved it can become a detached
 * compiler-grounded relation endpoint. Unsupported local declarations are refined past instead of obscuring a supported
 * enclosing caller. Live PSI remains request-local.
 */
internal fun PsiElement.nearestSupportedDeclaration(
    projection: IntellijK2RelationProjection
): SupportedContainingDeclaration =
    generateSequence(this as PsiElement?) { it.parent }
        .filterIsInstance<PsiNamedElement>()
        .filter { it is KtNamedDeclaration || it is com.intellij.psi.PsiMember }
        // A constructor parameter's type occurrence belongs to its constructor, even when
        // K2 also exposes the generated property as an independently discoverable declaration.
        .filterNot { it is org.jetbrains.kotlin.psi.KtParameter }
        .map(projection::project)
        .filterIsInstance<IntellijRelationDeclarationProjection.Projected>()
        .firstOrNull()
        ?.let(SupportedContainingDeclaration::Found) ?: SupportedContainingDeclaration.Unsupported

internal fun KtCallElement.calleeReferences(): KotlinCallReferences {
    val references = calleeExpression?.references?.filterIsInstance<KtReference>().orEmpty()
    return if (references.isEmpty()) {
        KotlinCallReferences.Unresolved
    } else {
        KotlinCallReferences.Found(references)
    }
}

internal fun resumable(limitation: RelationLimitation) = IntellijRelationTermination.Resumable(setOf(limitation))
