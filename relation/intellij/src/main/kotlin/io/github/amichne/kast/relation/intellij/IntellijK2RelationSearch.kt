package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiUtilCore
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionSample
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileClassification
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileIndexClassifier
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.idea.references.KtReference

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

        private val referenceEmitter =
            IntellijReferenceOccurrenceEmitter(
                request = request,
                projection = projection,
                collector = collector,
                provenance = { it.relationOccurrenceProvenance(project, limits) },
                omit = { limitation, element, range -> incompleteItem(limitation, element, range) },
            )

        fun references(plan: IntellijRelationPlan.References): IntellijRelationTermination =
            IntellijRetainedRelationRead(
                    project = project,
                    scope = scope,
                    projection = projection,
                    limits = limits,
                    observation = observation,
                    cancellationCheck = cancellationCheck,
                )
                .references(
                    request = request,
                    subject = subject,
                    collector = collector,
                    process = { reference -> processReference(plan, reference) },
                    termination = ::termination,
                )

        private fun processReference(plan: IntellijRelationPlan.References, reference: PsiReference): Boolean {
            observation.phase(io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase.REFERENCE_CONFIRMATION)
            when (packageDisposition(reference.element)) {
                ProviderItemDisposition.READY -> Unit
                ProviderItemDisposition.SKIPPED -> return true
                ProviderItemDisposition.HALTED -> return false
            }
            return when (val kotlin = reference as? KtReference) {
                null -> processJavaReference(plan, reference)
                else -> processKotlinReference(plan, kotlin)
            }
        }

        private fun processJavaReference(plan: IntellijRelationPlan.References, reference: PsiReference): Boolean {
            if (reference.element.containingFile !is PsiJavaFile) {
                observation.terminated(IntellijReadTermination.NON_KOTLIN_REFERENCE)
                return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            }
            if (!plan.admitsJava(reference)) return collector.dismissProviderItem()
            if (request.meaning == RelationMeaning.References || request.meaning == RelationMeaning.TypeUses) {
                return referenceEmitter.confirm(
                    reference,
                    projection.confirmJavaReferenceTarget(reference, request.subject).observedBy(observation),
                )
            }
            return when (projection.confirmJavaTarget(reference, request.subject).observedBy(observation)) {
                IntellijK2TargetConfirmation.EXACT_SUBJECT -> emitRelatedReference(reference)
                IntellijK2TargetConfirmation.DIFFERENT_SYMBOL -> collector.dismissProviderItem()
                IntellijK2TargetConfirmation.UNRESOLVED -> incompleteItem(RelationLimitation.UNRESOLVED_TARGET)
            }
        }

        private fun processKotlinReference(plan: IntellijRelationPlan.References, reference: KtReference): Boolean {
            val admitted =
                when (val admission = plan.admit(reference, observation)) {
                    IntellijRelationReferenceAdmission.Skipped -> return collector.dismissProviderItem()
                    is IntellijRelationReferenceAdmission.Admitted -> admission
                }
            if (request.meaning == RelationMeaning.References || request.meaning == RelationMeaning.TypeUses) {
                return referenceEmitter.confirm(
                    reference,
                    projection.confirmReferenceTarget(reference, request.subject).observedBy(observation),
                )
            }
            return when (projection.confirmTarget(admitted).observedBy(observation)) {
                IntellijK2TargetConfirmation.DIFFERENT_SYMBOL -> collector.dismissProviderItem()
                IntellijK2TargetConfirmation.UNRESOLVED -> incompleteItem(RelationLimitation.UNRESOLVED_TARGET)
                IntellijK2TargetConfirmation.EXACT_SUBJECT -> emitRelatedReference(reference)
            }
        }

        private fun emitRelatedReference(reference: PsiReference): Boolean =
            when (val related = reference.element.relatedOwner()) {
                is SupportedContainingDeclaration.Found ->
                    emit(related.projection, reference.element, reference.rangeInElement)
                SupportedContainingDeclaration.Unresolved ->
                    incompleteItem(RelationLimitation.UNRESOLVED_TARGET, reference.element, reference.rangeInElement)
                SupportedContainingDeclaration.Excluded -> collector.dismissProviderItem()
                SupportedContainingDeclaration.Unsupported ->
                    incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, reference.element, reference.rangeInElement)
            }

        private fun retainedReader() =
            IntellijRetainedRelationRead(
                project,
                scope,
                projection,
                limits,
                observation,
                cancellationCheck,
            )

        fun definitions(relation: IntellijDefinitionRelation): IntellijRelationTermination =
            retainedReader()
                .definitions(
                    request,
                    subject,
                    collector,
                    { definition ->
                        processDefinition(definition, relation)
                    },
                    ::termination,
                )

        private fun processDefinition(
            definition: IntellijRestoredDefinition,
            relation: IntellijDefinitionRelation,
        ): Boolean {
            when (packageDisposition(definition.element)) {
                ProviderItemDisposition.READY -> Unit
                ProviderItemDisposition.SKIPPED -> return true
                ProviderItemDisposition.HALTED -> return false
            }
            return when (definition) {
                is IntellijRestoredDefinition.Unsupported ->
                    incompleteItem(
                        RelationLimitation.UNSUPPORTED_ITEM,
                        definition.element,
                        definition.element.textRange.shiftLeft(definition.element.textRange.startOffset),
                    )
                is IntellijRestoredDefinition.Normalized ->
                    when (projection.confirmDefinition(subject, definition.declaration, relation)) {
                        IntellijK2DefinitionConfirmation.DIFFERENT_RELATION -> collector.dismissProviderItem()
                        IntellijK2DefinitionConfirmation.UNSUPPORTED ->
                            incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                        IntellijK2DefinitionConfirmation.CONFIRMED ->
                            emit(
                                definition.declaration,
                                definition.declaration,
                                definition.declaration.textRange.shiftLeft(
                                    definition.declaration.textRange.startOffset
                                ),
                            )
                    }
            }
        }

        fun callees(): IntellijRelationTermination =
            retainedReader().callees(request, subject, collector, ::processCallee, ::termination)

        private fun processCallee(candidate: CalleeProviderItem): Boolean {
            val owner =
                when (val admitted = projection.callOwner(candidate.owner)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return when (val failure = admitted.failure) {
                            CallOwnershipFailure.ExcludedCallback -> collector.dismissProviderItem()
                            is CallOwnershipFailure.Incomplete -> incompleteCallee(candidate, failure.limitation)
                        }
                }
            return when (candidate) {
                is CalleeProviderItem.Unresolved -> incompleteCallee(candidate, RelationLimitation.UNRESOLVED_TARGET)
                is CalleeProviderItem.Reference ->
                    when (val resolved = projection.resolve(candidate.reference)) {
                        IntellijK2ResolvedDeclaration.Unresolved -> {
                            observation.count(IntellijReadCounter.RELATION_K2_UNAVAILABLE_TARGETS)
                            incompleteCallee(candidate, RelationLimitation.UNRESOLVED_TARGET)
                        }
                        is IntellijK2ResolvedDeclaration.Found -> {
                            observation.count(IntellijReadCounter.RELATION_K2_CONFIRMED_TARGETS)
                            processCalleeTarget(owner, resolved.declaration, candidate.reference)
                        }
                    }
            }
        }

        private fun processCalleeTarget(
            owner: ContainingDeclaration.Found,
            target: PsiNamedElement,
            reference: KtReference,
        ): Boolean {
            val file = target.containingFile?.virtualFile
            val workspace = request.searchScope as? io.github.amichne.kast.symbol.contract.SymbolSearchScope.Workspace
            if (
                file != null &&
                    workspace?.libraries == io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.EXCLUDE &&
                    IntellijProjectFileIndexClassifier.classify(project, file, limits) is
                        IntellijProjectFileClassification.Library
            ) {
                observation.terminated(IntellijReadTermination.LIBRARY_POLICY_EXCLUSION)
                return collector.dismissProviderItem()
            }
            if (file == null || !scope.nativeScope.contains(file)) {
                observation.terminated(IntellijReadTermination.CALLEE_OUTSIDE_NATIVE_SCOPE)
                return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
            }
            return when (packageDisposition(target)) {
                ProviderItemDisposition.READY -> emitCallee(owner, target, reference)
                ProviderItemDisposition.SKIPPED -> true
                ProviderItemDisposition.HALTED -> false
            }
        }

        private fun incompleteCallee(candidate: CalleeProviderItem, limitation: RelationLimitation): Boolean =
            when (candidate) {
                is CalleeProviderItem.Reference ->
                    incompleteItem(limitation, candidate.reference.element, candidate.reference.rangeInElement)
                is CalleeProviderItem.Unresolved ->
                    incompleteItem(
                        limitation,
                        candidate.call,
                        candidate.call.textRange.shiftLeft(candidate.call.textRange.startOffset),
                    )
            }

        private fun emitCallee(
            owner: ContainingDeclaration.Found,
            target: PsiNamedElement,
            reference: KtReference,
        ): Boolean =
            if (owner.declaration === subject) emit(target, reference.element, reference.rangeInElement)
            else incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, reference.element, reference.rangeInElement)

        private fun PsiElement.relatedOwner(): SupportedContainingDeclaration =
            if (request.meaning == RelationMeaning.Callers) nearestSupportedCallable(projection)
            else nearestSupportedDeclaration(projection)

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

        private fun emit(
            related: PsiNamedElement,
            occurrenceElement: PsiElement,
            relativeRange: com.intellij.openapi.util.TextRange,
        ): Boolean =
            when (val result = projection.project(related)) {
                is IntellijRelationDeclarationProjection.Projected -> emit(result, occurrenceElement, relativeRange)
                IntellijRelationDeclarationProjection.Unsupported ->
                    incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
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
                    is Refinement.Rejected ->
                        return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
                }
            val occurrenceFile =
                PsiUtilCore.getVirtualFile(occurrenceElement)
                    ?: return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
            val detachedFile =
                when (val result = projection.detach(occurrenceFile)) {
                    is IntellijDetachedRelationFile.Found -> result.identity
                    IntellijDetachedRelationFile.Unsupported ->
                        return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
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
                    is Refinement.Rejected ->
                        return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
                }
            val provenance =
                when (val result = occurrenceFile.relationOccurrenceProvenance(project, limits)) {
                    is OccurrenceProvenance.Found -> result.provenance
                    OccurrenceProvenance.Unsupported ->
                        return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
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
                    is Refinement.Rejected ->
                        return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
                }
            return collector.accept(fact)
        }

        private fun incompleteItem(
            limitation: RelationLimitation,
            sample: RelationOmissionSample = RelationOmissionSample.Unavailable,
        ): Boolean {
            limitations += limitation
            return collector.examineIncomplete(limitation, sample)
        }

        private fun incompleteItem(
            limitation: RelationLimitation,
            element: PsiElement,
            range: com.intellij.openapi.util.TextRange,
        ): Boolean = incompleteItem(limitation, projection.omissionSample(element, range))

        private fun termination(provider: ProviderTermination): IntellijRelationTermination =
            when {
                provider == ProviderTermination.HALTED -> IntellijRelationTermination.Resumable(limitations)
                limitations.isNotEmpty() -> IntellijRelationTermination.TerminalIncomplete(limitations)
                else -> IntellijRelationTermination.Terminal
            }
    }
}
