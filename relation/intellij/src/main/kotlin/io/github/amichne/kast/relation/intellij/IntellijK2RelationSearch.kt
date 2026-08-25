package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Path

/** Request-local K2-confirmed implementation of all seven closed one-hop relation meanings. */
internal class IntellijK2RelationSearch(
    private val project: Project,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val cancellationCheck: () -> Unit = ProgressManager::checkCanceled,
) {
    /**
     * Proof transition: `(RelationRequest, exact K2 subject, IntellijRelationCollector) ->
     * IntellijRelationTermination`.
     *
     * Dispatches exactly one closed meaning. Native searches enumerate candidates, while K2
     * target/override/subtype resolution is the sole admission authority. Terminal means the
     * provider and collector both exhausted the requested hop; all uncertainty is returned as a
     * closed incomplete termination. Live query, PSI, VFS, and K2 values remain request-local.
     */
    fun read(
        request: RelationRequest,
        plan: IntellijRelationPlan,
        collector: IntellijRelationCollector,
    ): IntellijRelationTermination {
        if (DumbService.isDumb(project)) {
            return incomplete(RelationLimitation.DUMB_MODE_TRANSITION)
        }
        val state = SearchState(request, plan.subject, collector)
        return when (plan) {
            is IntellijRelationPlan.References -> state.references(plan)
            is IntellijRelationPlan.Callees -> state.callees()
            is IntellijRelationPlan.Definitions -> state.definitions(plan.relation)
        }
    }

    private inner class SearchState(
        private val request: RelationRequest,
        private val subject: KtNamedDeclaration,
        private val collector: IntellijRelationCollector,
    ) {
        private var skipRemaining = request.position.workOffset.value
        private val limitations = linkedSetOf<RelationLimitation>()

        fun references(plan: IntellijRelationPlan.References): IntellijRelationTermination {
            val terminal = ReferencesSearch.search(subject, scope.nativeScope, false)
                .forEach(Processor { reference ->
                    cancellationCheck()
                    if (skip()) return@Processor true
                    val kotlinReference = reference as? KtReference
                    if (kotlinReference == null) {
                        return@Processor incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                    }
                    val admitted = when (val admission = plan.admit(kotlinReference)) {
                        IntellijRelationReferenceAdmission.Skipped -> return@Processor true
                        is IntellijRelationReferenceAdmission.Admitted -> admission
                    }
                    when (projection.confirmTarget(admitted)) {
                        IntellijK2TargetConfirmation.DIFFERENT_SYMBOL -> when (admitted) {
                            is IntellijRelationReferenceAdmission.Admitted.ClassConstruction ->
                                return@Processor true
                            is IntellijRelationReferenceAdmission.Admitted.ExactSymbol ->
                                return@Processor incompleteItem(
                                    RelationLimitation.UNRESOLVED_TARGET,
                                )
                        }
                        IntellijK2TargetConfirmation.UNRESOLVED ->
                            return@Processor incompleteItem(RelationLimitation.UNRESOLVED_TARGET)
                        IntellijK2TargetConfirmation.EXACT_SUBJECT -> Unit
                    }
                    val related = when (
                        val containing = reference.element.nearestSupportedDeclaration(projection)
                    ) {
                        is SupportedContainingDeclaration.Found -> containing.projection
                        SupportedContainingDeclaration.Unsupported ->
                            return@Processor incompleteItem(
                                RelationLimitation.UNSUPPORTED_ITEM,
                            )
                    }
                    emit(related, reference.element, reference.rangeInElement)
                })
            return termination(ProviderTermination.from(terminal))
        }

        fun definitions(relation: IntellijDefinitionRelation): IntellijRelationTermination {
            val terminal = DefinitionsScopedSearch.search(subject, scope.nativeScope, false)
                .forEach(Processor { definition ->
                    cancellationCheck()
                    if (skip()) return@Processor true
                    val candidate = definition as? KtNamedDeclaration
                                    ?: return@Processor incompleteItem(
                                        RelationLimitation.UNSUPPORTED_ITEM,
                                    )
                    when (projection.confirmDefinition(subject, candidate, relation)) {
                        IntellijK2DefinitionConfirmation.DIFFERENT_RELATION -> true
                        IntellijK2DefinitionConfirmation.UNSUPPORTED ->
                            incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                        IntellijK2DefinitionConfirmation.CONFIRMED ->
                            emit(candidate, candidate, candidate.textRange.shiftLeft(candidate.textRange.startOffset))
                    }
                })
            return termination(ProviderTermination.from(terminal))
        }

        fun callees(): IntellijRelationTermination {
            val calls = PsiTreeUtil.findChildrenOfType(subject, KtCallElement::class.java)
                .filter { call ->
                    when (val containing = call.nearestDeclaration()) {
                        is ContainingDeclaration.Found -> containing.declaration === subject
                        ContainingDeclaration.Unsupported -> false
                    }
                }
                .sortedBy { it.textRange.startOffset }
            for (call in calls) {
                cancellationCheck()
                val references = when (val resolved = call.calleeReferences()) {
                    is KotlinCallReferences.Found -> resolved.references
                    KotlinCallReferences.Unresolved -> {
                        if (!incompleteItem(RelationLimitation.UNRESOLVED_TARGET)) {
                            return termination(ProviderTermination.HALTED)
                        }
                        continue
                    }
                }
                for (reference in references) {
                    if (skip()) continue
                    when (val resolved = projection.resolve(reference)) {
                        IntellijK2ResolvedDeclaration.Unresolved ->
                            if (!incompleteItem(RelationLimitation.UNRESOLVED_TARGET)) {
                                return termination(ProviderTermination.HALTED)
                            }
                        is IntellijK2ResolvedDeclaration.Found -> {
                            val file = resolved.declaration.containingFile?.virtualFile
                            if (file == null || !scope.nativeScope.contains(file)) {
                                if (!incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)) {
                                    return termination(ProviderTermination.HALTED)
                                }
                            } else if (!emit(resolved.declaration, reference.element, reference.rangeInElement)) {
                                return termination(ProviderTermination.HALTED)
                            }
                        }
                    }
                }
            }
            return termination(ProviderTermination.TERMINAL)
        }

        private fun emit(
            related: KtNamedDeclaration,
            occurrenceElement: PsiElement,
            relativeRange: com.intellij.openapi.util.TextRange,
        ): Boolean = when (val result = projection.project(related)) {
            is IntellijRelationDeclarationProjection.Projected ->
                emit(result, occurrenceElement, relativeRange)
            IntellijRelationDeclarationProjection.Unsupported ->
                incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
        }

        private fun emit(
            related: IntellijRelationDeclarationProjection.Projected,
            occurrenceElement: PsiElement,
            relativeRange: com.intellij.openapi.util.TextRange,
        ): Boolean {
            val endpoint = when (
                val resolved = RelationEndpoint.resolve(
                    request.subject.lease,
                    request.subject.scope,
                    related.evidence,
                )
            ) {
                is Refinement.Refined -> resolved.value
                is Refinement.Rejected ->
                    return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            }
            val occurrenceFile = PsiUtilCore.getVirtualFile(occurrenceElement)
                                 ?: return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            val detachedFile = when (val result = projection.detach(occurrenceFile)) {
                is IntellijDetachedRelationFile.Found -> result.identity
                IntellijDetachedRelationFile.Unsupported ->
                    return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            }
            val start = occurrenceElement.textRange.startOffset + relativeRange.startOffset
            val occurrence = when (
                val result = RelationOccurrence.fromBoundary(
                    detachedFile,
                    start,
                    occurrenceElement.textRange.startOffset + relativeRange.endOffset,
                )
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected ->
                    return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            }
            val provenance = when (val result = occurrenceFile.provenance()) {
                is OccurrenceProvenance.Found -> result.provenance
                OccurrenceProvenance.Unsupported ->
                    return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            }
            val subjectEndpoint = request.subject
            val (source, target) = if (request.meaning == RelationMeaning.Callees) {
                subjectEndpoint to endpoint
            } else {
                endpoint to subjectEndpoint
            }
            val fact = when (
                val result = RelationFact.create(
                    request,
                    source,
                    target,
                    occurrence,
                    provenance,
                )
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected ->
                    return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            }
            return collector.accept(fact)
        }

        private fun skip(): Boolean = if (skipRemaining > 0L) {
            skipRemaining -= 1L
            true
        } else {
            false
        }

        private fun incompleteItem(limitation: RelationLimitation): Boolean {
            limitations += limitation
            return collector.examineIncomplete(limitation)
        }

        private fun termination(provider: ProviderTermination): IntellijRelationTermination =
            if (provider == ProviderTermination.TERMINAL && limitations.isEmpty()) {
                IntellijRelationTermination.Terminal
            } else {
                IntellijRelationTermination.Incomplete(
                    limitations.ifEmpty { setOf(RelationLimitation.PROVIDER_INCOMPLETE) },
                )
            }
    }

    private fun com.intellij.openapi.vfs.VirtualFile.provenance(): OccurrenceProvenance {
        if (ProjectFileIndex.getInstance(project).isInLibrary(this)) {
            return OccurrenceProvenance.Found(RelationProvenance.K2_PROJECT_LIBRARY)
        }
        val path = when (val classified = relationNativePath(this)) {
            is IntellijRelationNativePath.Absolute -> classified.value
            IntellijRelationNativePath.Relative,
            IntellijRelationNativePath.Unavailable,
                -> return OccurrenceProvenance.Unsupported
        }
        val owners = scope.sourceRoots.filter { root ->
            path.startsWith(Path.of(root.sourceRoot.value))
        }
        val depth = owners.maxOfOrNull { Path.of(it.sourceRoot.value).nameCount }
                    ?: return OccurrenceProvenance.Unsupported
        val provenance = owners
                             .filter { Path.of(it.sourceRoot.value).nameCount == depth }
                             .map { it.provenance }
                             .distinct()
                             .singleOrNull() ?: return OccurrenceProvenance.Unsupported
        return when (provenance) {
            WorkspaceSourceRootProvenance.AUTHORED ->
                OccurrenceProvenance.Found(RelationProvenance.K2_AUTHORED_SOURCE)
            WorkspaceSourceRootProvenance.GENERATED ->
                OccurrenceProvenance.Found(RelationProvenance.K2_GENERATED_SOURCE)
            WorkspaceSourceRootProvenance.UNKNOWN -> OccurrenceProvenance.Unsupported
        }
    }
}

private sealed interface ContainingDeclaration {
    data class Found(val declaration: KtNamedDeclaration) : ContainingDeclaration
    data object Unsupported : ContainingDeclaration
}

private sealed interface SupportedContainingDeclaration {
    data class Found(
        val projection: IntellijRelationDeclarationProjection.Projected,
    ) : SupportedContainingDeclaration

    data object Unsupported : SupportedContainingDeclaration
}

private sealed interface OccurrenceProvenance {
    data class Found(val provenance: RelationProvenance) : OccurrenceProvenance
    data object Unsupported : OccurrenceProvenance
}

private sealed interface KotlinCallReferences {
    data class Found(val references: List<KtReference>) : KotlinCallReferences
    data object Unresolved : KotlinCallReferences
}

private enum class ProviderTermination {
    TERMINAL,
    HALTED,
    ;

    companion object {
        fun from(terminal: Boolean): ProviderTermination =
            if (terminal) TERMINAL else HALTED
    }
}

private fun PsiElement.nearestDeclaration(): ContainingDeclaration =
    generateSequence(this as PsiElement?) { it.parent }
        .filterIsInstance<KtNamedDeclaration>()
        .firstOrNull()
        ?.let(ContainingDeclaration::Found)
    ?: ContainingDeclaration.Unsupported

/**
 * Proof transition: `(PsiElement, IntellijK2RelationProjection) ->
 * SupportedContainingDeclaration`.
 *
 * A found result carries the nearest containing named declaration that has already proved it can
 * become a detached compiler-grounded relation endpoint. Unsupported local declarations are
 * refined past instead of obscuring a supported enclosing caller. Live PSI remains request-local.
 */
private fun PsiElement.nearestSupportedDeclaration(
    projection: IntellijK2RelationProjection,
): SupportedContainingDeclaration = generateSequence(this as PsiElement?) { it.parent }
    .filterIsInstance<KtNamedDeclaration>()
    .map(projection::project)
    .filterIsInstance<IntellijRelationDeclarationProjection.Projected>()
    .firstOrNull()
    ?.let(SupportedContainingDeclaration::Found)
    ?: SupportedContainingDeclaration.Unsupported

private fun KtCallElement.calleeReferences(): KotlinCallReferences {
    val references = calleeExpression?.references?.filterIsInstance<KtReference>().orEmpty()
    return if (references.isEmpty()) {
        KotlinCallReferences.Unresolved
    } else {
        KotlinCallReferences.Found(references)
    }
}

private fun incomplete(limitation: RelationLimitation) =
    IntellijRelationTermination.Incomplete(setOf(limitation))
