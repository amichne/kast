package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.intellij.read.IntellijGeneratedSourceState
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileClassification
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileIndexClassifier
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Request-local K2-confirmed implementation of all seven closed one-hop relation meanings. */
internal class IntellijK2RelationSearch(
    private val project: Project,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val cancellationCheck: () -> Unit = ProgressManager::checkCanceled,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: ReadLimits = ReadLimits.Default,
) {
    /**
     * Proof transition: `(RelationRequest, exact K2 subject, IntellijRelationCollector) ->
     * IntellijRelationTermination`.
     *
     * Dispatches exactly one closed meaning. Native searches enumerate candidates, while K2 target/override/subtype
     * resolution is the sole admission authority. Terminal means the provider and collector both exhausted the
     * requested hop; all uncertainty is returned as a closed incomplete termination. Live query, PSI, VFS, and K2
     * values remain request-local.
     */
    fun read(
        request: RelationRequest,
        plan: IntellijRelationPlan,
        collector: IntellijRelationCollector,
    ): IntellijRelationTermination {
        if (DumbService.isDumb(project)) {
            return resumable(RelationLimitation.DUMB_MODE_TRANSITION)
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
        private val subject: PsiNamedElement,
        private val collector: IntellijRelationCollector,
    ) {
        private val limitations = linkedSetOf<RelationLimitation>()

        fun references(plan: IntellijRelationPlan.References): IntellijRelationTermination {
            val references = mutableListOf<PsiReference>()
            val providerExhausted =
                ReferencesSearch.search(subject, scope.nativeScope, false)
                    .forEach(
                        Processor { reference ->
                            cancellationCheck()
                            if (!providerCandidateReady()) return@Processor false
                            references += reference
                            true
                        }
                    )
            if (!providerExhausted || !providerEnumerationReady()) {
                return termination(ProviderTermination.HALTED)
            }
            val ordered = references.canonicalRelationProviderOrder { reference ->
                providerItemDescriptor(
                    reference.element,
                    reference.rangeInElement,
                    "reference:${reference.javaClass.name}",
                )
            }
            for (item in ordered) {
                cancellationCheck()
                val reference = item.value
                when (beginProviderItem(item.descriptor)) {
                    ProviderItemDisposition.READY -> Unit
                    ProviderItemDisposition.SKIPPED -> continue
                    ProviderItemDisposition.HALTED -> return termination(ProviderTermination.HALTED)
                }
                when (packageDisposition(reference.element)) {
                    ProviderItemDisposition.READY -> Unit
                    ProviderItemDisposition.SKIPPED -> continue
                    ProviderItemDisposition.HALTED -> return termination(ProviderTermination.HALTED)
                }
                val kotlinReference = reference as? KtReference
                if (kotlinReference == null) {
                    if (reference.element.containingFile !is PsiJavaFile) {
                        observation.terminated(IntellijReadTermination.NON_KOTLIN_REFERENCE)
                        if (!incompleteItem(RelationLimitation.UNSUPPORTED_ITEM))
                            return termination(ProviderTermination.HALTED)
                        continue
                    }
                    if (!plan.admitsJava(reference)) {
                        if (!collector.dismissProviderItem()) return termination(ProviderTermination.HALTED)
                        continue
                    }
                    when (projection.confirmJavaTarget(reference, request.subject)) {
                        IntellijK2TargetConfirmation.EXACT_SUBJECT -> {
                            val related = reference.element.nearestSupportedDeclaration(projection)
                            val continued =
                                when (related) {
                                    is SupportedContainingDeclaration.Found ->
                                        emit(related.projection, reference.element, reference.rangeInElement)
                                    SupportedContainingDeclaration.Unsupported ->
                                        incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                                }
                            if (!continued) return termination(ProviderTermination.HALTED)
                        }
                        IntellijK2TargetConfirmation.DIFFERENT_SYMBOL ->
                            if (!collector.dismissProviderItem()) return termination(ProviderTermination.HALTED)
                        IntellijK2TargetConfirmation.UNRESOLVED ->
                            if (!incompleteItem(RelationLimitation.UNRESOLVED_TARGET))
                                return termination(ProviderTermination.HALTED)
                    }
                    continue
                }
                val admitted =
                    when (val admission = plan.admit(kotlinReference)) {
                        IntellijRelationReferenceAdmission.Skipped -> {
                            if (!collector.dismissProviderItem()) {
                                return termination(ProviderTermination.HALTED)
                            }
                            continue
                        }
                        is IntellijRelationReferenceAdmission.Admitted -> admission
                    }
                when (projection.confirmTarget(admitted)) {
                    IntellijK2TargetConfirmation.DIFFERENT_SYMBOL -> {
                        val continued =
                            when (admitted) {
                                is IntellijRelationReferenceAdmission.Admitted.ClassConstruction ->
                                    collector.dismissProviderItem()
                                is IntellijRelationReferenceAdmission.Admitted.ExactSymbol ->
                                    incompleteItem(RelationLimitation.UNRESOLVED_TARGET)
                            }
                        if (!continued) return termination(ProviderTermination.HALTED)
                        continue
                    }
                    IntellijK2TargetConfirmation.UNRESOLVED -> {
                        if (!incompleteItem(RelationLimitation.UNRESOLVED_TARGET)) {
                            return termination(ProviderTermination.HALTED)
                        }
                        continue
                    }
                    IntellijK2TargetConfirmation.EXACT_SUBJECT -> Unit
                }
                val related =
                    when (val containing = reference.element.nearestSupportedDeclaration(projection)) {
                        is SupportedContainingDeclaration.Found -> containing.projection
                        SupportedContainingDeclaration.Unsupported -> {
                            if (!incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)) {
                                return termination(ProviderTermination.HALTED)
                            }
                            continue
                        }
                    }
                if (!emit(related, reference.element, reference.rangeInElement)) {
                    return termination(ProviderTermination.HALTED)
                }
            }
            return termination(ProviderTermination.TERMINAL)
        }

        fun definitions(relation: IntellijDefinitionRelation): IntellijRelationTermination {
            val definitions = mutableListOf<PsiElement>()
            val providerExhausted =
                DefinitionsScopedSearch.search(subject, scope.nativeScope, false)
                    .forEach(
                        Processor { definition ->
                            cancellationCheck()
                            if (!providerCandidateReady()) return@Processor false
                            definitions += definition
                            true
                        }
                    )
            if (!providerExhausted || !providerEnumerationReady()) {
                return termination(ProviderTermination.HALTED)
            }
            val ordered = definitions.canonicalRelationProviderOrder { definition ->
                providerItemDescriptor(
                    definition,
                    definition.textRange.shiftLeft(definition.textRange.startOffset),
                    "definition:${definition.javaClass.name}",
                )
            }
            for (item in ordered) {
                cancellationCheck()
                val definition = item.value
                when (beginProviderItem(item.descriptor)) {
                    ProviderItemDisposition.READY -> Unit
                    ProviderItemDisposition.SKIPPED -> continue
                    ProviderItemDisposition.HALTED -> return termination(ProviderTermination.HALTED)
                }
                when (packageDisposition(definition)) {
                    ProviderItemDisposition.READY -> Unit
                    ProviderItemDisposition.SKIPPED -> continue
                    ProviderItemDisposition.HALTED -> return termination(ProviderTermination.HALTED)
                }
                val candidate = definition as? PsiNamedElement
                if (candidate == null) {
                    if (!incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)) {
                        return termination(ProviderTermination.HALTED)
                    }
                    continue
                }
                val continued =
                    when (projection.confirmDefinition(subject, candidate, relation)) {
                        IntellijK2DefinitionConfirmation.DIFFERENT_RELATION -> collector.dismissProviderItem()
                        IntellijK2DefinitionConfirmation.UNSUPPORTED ->
                            incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                        IntellijK2DefinitionConfirmation.CONFIRMED ->
                            emit(
                                candidate,
                                candidate,
                                candidate.textRange.shiftLeft(candidate.textRange.startOffset),
                            )
                    }
                if (!continued) return termination(ProviderTermination.HALTED)
            }
            return termination(ProviderTermination.TERMINAL)
        }

        fun callees(): IntellijRelationTermination {
            if (subject !is KtNamedDeclaration)
                return IntellijRelationTermination.TerminalIncomplete(setOf(RelationLimitation.UNSUPPORTED_ITEM))
            val calls = mutableListOf<KtCallElement>()
            val callsExhausted =
                PsiTreeUtil.processElements(
                    subject,
                    PsiElementProcessor<PsiElement> { element ->
                        cancellationCheck()
                        if (!providerEnumerationReady()) return@PsiElementProcessor false
                        val call = element as? KtCallElement ?: return@PsiElementProcessor true
                        val belongsToSubject =
                            when (val containing = call.nearestDeclaration()) {
                                is ContainingDeclaration.Found -> containing.declaration === subject
                                ContainingDeclaration.Unsupported -> false
                            }
                        if (belongsToSubject) {
                            if (!providerCandidateReady()) return@PsiElementProcessor false
                            calls += call
                        }
                        true
                    },
                )
            if (!callsExhausted) return termination(ProviderTermination.HALTED)
            val candidates = mutableListOf<CalleeProviderItem>()
            for (call in calls) {
                cancellationCheck()
                if (!providerEnumerationReady()) {
                    return termination(ProviderTermination.HALTED)
                }
                when (val resolved = call.calleeReferences()) {
                    is KotlinCallReferences.Found ->
                        resolved.references.forEach { reference ->
                            if (!providerCandidateReady()) return termination(ProviderTermination.HALTED)
                            candidates += CalleeProviderItem.Reference(reference)
                        }
                    KotlinCallReferences.Unresolved -> {
                        if (!providerCandidateReady()) return termination(ProviderTermination.HALTED)
                        candidates += CalleeProviderItem.Unresolved(call)
                    }
                }
            }
            if (!providerEnumerationReady()) {
                return termination(ProviderTermination.HALTED)
            }
            val ordered = candidates.canonicalRelationProviderOrder { candidate ->
                candidate.descriptor()
            }
            for (item in ordered) {
                cancellationCheck()
                when (beginProviderItem(item.descriptor)) {
                    ProviderItemDisposition.READY -> Unit
                    ProviderItemDisposition.SKIPPED -> continue
                    ProviderItemDisposition.HALTED -> return termination(ProviderTermination.HALTED)
                }
                when (val candidate = item.value) {
                    is CalleeProviderItem.Unresolved ->
                        if (!incompleteItem(RelationLimitation.UNRESOLVED_TARGET)) {
                            return termination(ProviderTermination.HALTED)
                        }
                    is CalleeProviderItem.Reference ->
                        when (val resolved = projection.resolve(candidate.reference)) {
                            IntellijK2ResolvedDeclaration.Unresolved ->
                                if (!incompleteItem(RelationLimitation.UNRESOLVED_TARGET)) {
                                    return termination(ProviderTermination.HALTED)
                                }
                            is IntellijK2ResolvedDeclaration.Found -> {
                                val file = resolved.declaration.containingFile?.virtualFile
                                if (
                                    file != null &&
                                        request.searchScope is
                                            io.github.amichne.kast.symbol.contract.SymbolSearchScope.Workspace &&
                                        (request.searchScope
                                                as io.github.amichne.kast.symbol.contract.SymbolSearchScope.Workspace)
                                            .libraries ==
                                            io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.EXCLUDE &&
                                        IntellijProjectFileIndexClassifier.classify(project, file, limits) is
                                            IntellijProjectFileClassification.Library
                                ) {
                                    observation.terminated(IntellijReadTermination.LIBRARY_POLICY_EXCLUSION)
                                    if (!collector.dismissProviderItem()) return termination(ProviderTermination.HALTED)
                                } else if (file == null || !scope.nativeScope.contains(file)) {
                                    observation.terminated(IntellijReadTermination.CALLEE_OUTSIDE_NATIVE_SCOPE)
                                    if (!incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)) {
                                        return termination(ProviderTermination.HALTED)
                                    }
                                } else {
                                    when (packageDisposition(resolved.declaration)) {
                                        ProviderItemDisposition.READY -> Unit
                                        ProviderItemDisposition.SKIPPED -> continue
                                        ProviderItemDisposition.HALTED -> return termination(ProviderTermination.HALTED)
                                    }
                                    if (
                                        !emit(
                                            resolved.declaration,
                                            candidate.reference.element,
                                            candidate.reference.rangeInElement,
                                        )
                                    ) {
                                        return termination(ProviderTermination.HALTED)
                                    }
                                }
                            }
                        }
                }
            }
            return termination(ProviderTermination.TERMINAL)
        }

        private fun packageDisposition(element: PsiElement): ProviderItemDisposition =
            when (request.searchConstraints.packageName.admitPackage { element.relationPackageEvidence() }) {
                IntellijRelationPackageAdmission.ADMITTED -> ProviderItemDisposition.READY
                IntellijRelationPackageAdmission.OUTSIDE_SCOPE -> {
                    observation.terminated(IntellijReadTermination.TARGET_OUTSIDE_PACKAGE)
                    if (collector.dismissProviderItem()) ProviderItemDisposition.SKIPPED
                    else ProviderItemDisposition.HALTED
                }
                IntellijRelationPackageAdmission.UNSUPPORTED ->
                    if (incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)) {
                        ProviderItemDisposition.SKIPPED
                    } else {
                        ProviderItemDisposition.HALTED
                    }
            }

        private fun providerEnumerationReady(): Boolean =
            when (collector.admitProviderEnumeration()) {
                IntellijRelationProviderEnumerationAdmission.READY -> true
                IntellijRelationProviderEnumerationAdmission.HALTED -> false
            }

        private fun providerCandidateReady(): Boolean =
            when (collector.admitProviderCandidate()) {
                IntellijRelationProviderEnumerationAdmission.READY -> true
                IntellijRelationProviderEnumerationAdmission.HALTED -> false
            }

        private fun beginProviderItem(descriptor: RelationProviderItemDescriptor): ProviderItemDisposition =
            when (collector.beginProviderItem(descriptor)) {
                IntellijRelationProviderItemAdmission.READY -> ProviderItemDisposition.READY
                IntellijRelationProviderItemAdmission.SKIPPED_VERIFIED_PREFIX -> ProviderItemDisposition.SKIPPED
                IntellijRelationProviderItemAdmission.HALTED,
                IntellijRelationProviderItemAdmission.CURSOR_MOVED -> ProviderItemDisposition.HALTED
            }

        private fun emit(
            related: PsiNamedElement,
            occurrenceElement: PsiElement,
            relativeRange: com.intellij.openapi.util.TextRange,
        ): Boolean =
            when (val result = projection.project(related)) {
                is IntellijRelationDeclarationProjection.Projected -> emit(result, occurrenceElement, relativeRange)
                IntellijRelationDeclarationProjection.Unsupported -> incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            }

        private fun emit(
            related: IntellijRelationDeclarationProjection.Projected,
            occurrenceElement: PsiElement,
            relativeRange: com.intellij.openapi.util.TextRange,
        ): Boolean {
            val endpoint =
                when (
                    val resolved =
                        RelationEndpoint.resolve(
                            request.subject.lease,
                            request.searchScope,
                            related.evidence,
                            request.searchConstraints,
                        )
                ) {
                    is Refinement.Refined -> resolved.value
                    is Refinement.Rejected -> return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                }
            val occurrenceFile =
                PsiUtilCore.getVirtualFile(occurrenceElement)
                    ?: return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            val detachedFile =
                when (val result = projection.detach(occurrenceFile)) {
                    is IntellijDetachedRelationFile.Found -> result.identity
                    IntellijDetachedRelationFile.Unsupported ->
                        return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                }
            val start = occurrenceElement.textRange.startOffset + relativeRange.startOffset
            val occurrence =
                when (
                    val result =
                        RelationOccurrence.fromBoundary(
                            detachedFile,
                            start,
                            occurrenceElement.textRange.startOffset + relativeRange.endOffset,
                        )
                ) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                }
            val provenance =
                when (val result = occurrenceFile.provenance()) {
                    is OccurrenceProvenance.Found -> result.provenance
                    OccurrenceProvenance.Unsupported -> return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                }
            val subjectEndpoint = request.subject
            val (source, target) =
                if (request.meaning == RelationMeaning.Callees) {
                    subjectEndpoint to endpoint
                } else {
                    endpoint to subjectEndpoint
                }
            val fact =
                when (
                    val result =
                        RelationFact.create(
                            request,
                            source,
                            target,
                            occurrence,
                            provenance,
                        )
                ) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                }
            return collector.accept(fact)
        }

        private fun incompleteItem(limitation: RelationLimitation): Boolean {
            limitations += limitation
            return collector.examineIncomplete(limitation)
        }

        private fun termination(provider: ProviderTermination): IntellijRelationTermination =
            when {
                provider == ProviderTermination.HALTED ->
                    IntellijRelationTermination.Resumable(
                        limitations.ifEmpty { setOf(RelationLimitation.PROVIDER_INCOMPLETE) }
                    )
                limitations.isNotEmpty() -> IntellijRelationTermination.TerminalIncomplete(limitations)
                else -> IntellijRelationTermination.Terminal
            }
    }

    private fun com.intellij.openapi.vfs.VirtualFile.provenance(): OccurrenceProvenance =
        when (val classification = IntellijProjectFileIndexClassifier.classify(project, this, limits)) {
            is IntellijProjectFileClassification.Source ->
                when (classification.generated) {
                    IntellijGeneratedSourceState.AUTHORED ->
                        OccurrenceProvenance.Found(RelationProvenance.K2_AUTHORED_SOURCE)
                    IntellijGeneratedSourceState.GENERATED ->
                        OccurrenceProvenance.Found(RelationProvenance.K2_GENERATED_SOURCE)
                }
            is IntellijProjectFileClassification.Library ->
                OccurrenceProvenance.Found(RelationProvenance.K2_PROJECT_LIBRARY)
            is IntellijProjectFileClassification.NotSource,
            is IntellijProjectFileClassification.Rejected -> OccurrenceProvenance.Unsupported
        }
}

private sealed interface ContainingDeclaration {
    data class Found(val declaration: PsiNamedElement) : ContainingDeclaration

    data object Unsupported : ContainingDeclaration
}

private sealed interface SupportedContainingDeclaration {
    data class Found(val projection: IntellijRelationDeclarationProjection.Projected) : SupportedContainingDeclaration

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

private sealed interface CalleeProviderItem {
    data class Unresolved(val call: KtCallElement) : CalleeProviderItem

    data class Reference(val reference: KtReference) : CalleeProviderItem
}

private fun CalleeProviderItem.descriptor(): RelationProviderItemDescriptor =
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

private enum class ProviderTermination {
    TERMINAL,
    HALTED,
}

private enum class ProviderItemDisposition {
    READY,
    SKIPPED,
    HALTED,
}

private fun providerItemDescriptor(
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

private fun PsiElement.nearestDeclaration(): ContainingDeclaration =
    generateSequence(this as PsiElement?) { it.parent }
        .filterIsInstance<PsiNamedElement>()
        .filter { it is KtNamedDeclaration || it is com.intellij.psi.PsiMember }
        .firstOrNull()
        ?.let(ContainingDeclaration::Found) ?: ContainingDeclaration.Unsupported

/**
 * Proof transition: `(PsiElement, IntellijK2RelationProjection) -> SupportedContainingDeclaration`.
 *
 * A found result carries the nearest containing named declaration that has already proved it can become a detached
 * compiler-grounded relation endpoint. Unsupported local declarations are refined past instead of obscuring a supported
 * enclosing caller. Live PSI remains request-local.
 */
private fun PsiElement.nearestSupportedDeclaration(
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

private fun KtCallElement.calleeReferences(): KotlinCallReferences {
    val references = calleeExpression?.references?.filterIsInstance<KtReference>().orEmpty()
    return if (references.isEmpty()) {
        KotlinCallReferences.Unresolved
    } else {
        KotlinCallReferences.Found(references)
    }
}

private fun resumable(limitation: RelationLimitation) = IntellijRelationTermination.Resumable(setOf(limitation))
