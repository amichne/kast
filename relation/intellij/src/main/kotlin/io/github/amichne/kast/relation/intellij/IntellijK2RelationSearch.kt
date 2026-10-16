package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOmissionSample
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.idea.references.KtReference

/** Request-local K2-confirmed implementation of all seven closed one-hop relation meanings. */
internal class IntellijK2RelationSearch(
    private val project: Project,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val subjectSource: IntellijRelationSubjectLookup.Found,
    private val callbackEvidence: CallbackEvidenceAccess,
    private val cancellationCheck: () -> Unit = ProgressManager::checkCanceled,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val limits: ReadLimits = ReadLimits.Default,
    private val summaries: io.github.amichne.kast.relation.contract.CallbackSummaryCachePort =
        io.github.amichne.kast.relation.contract.CallbackSummaryCachePort.Disabled,
    private val inventories: IntellijReferenceInventoryReuse = IntellijReferenceInventoryReuse.Disabled,
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
        private val resultProjection = IntellijCallbackResultProjection(project, scope, collector, observation)

        private val referenceEmitter =
            IntellijReferenceOccurrenceEmitter(
                request = request,
                projection = projection,
                collector = collector,
                provenance = { it.relationOccurrenceProvenance(project, limits) },
                omit = { limitation, element, range -> incompleteItem(limitation, element, range) },
            )

        private val factEmitter =
            IntellijRelationFactEmitter(
                request,
                projection,
                collector,
                provenance = { it.relationOccurrenceProvenance(project, limits) },
                omit = { limitation, element, range -> incompleteItem(limitation, element, range) },
            )

        private val callbackEmitter =
            IntellijCallbackObservationEmitter(
                request,
                projection,
                callbackEvidence.sources,
                collector,
                observation,
                summaries,
                resultProjection,
            ) { limitation, element, range ->
                incompleteItem(limitation, element, range)
            }

        fun references(plan: IntellijRelationPlan.References): IntellijRelationTermination =
            IntellijRetainedRelationRead(
                    project = project,
                    scope = if (request.meaning == RelationMeaning.Callers) callbackEvidence.sources else scope,
                    projection = projection,
                    limits = limits,
                    observation = observation,
                    cancellationCheck = cancellationCheck,
                    inventories =
                        if (request.meaning == RelationMeaning.Callers)
                            inventories.callbackEvidence(callbackEvidence.sources.fingerprint)
                        else inventories,
                    admitsReference = { reference ->
                        request.meaning != RelationMeaning.Callers ||
                            reference.element.containingFile?.virtualFile?.let(scope.nativeScope::contains) == true ||
                            IntellijCallbackCallerProjection.isCandidate(reference)
                    },
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
            if (request.meaning == RelationMeaning.Callers) {
                val file = reference.element.containingFile?.virtualFile
                if (file == null || !scope.nativeScope.contains(file)) return auxiliaryCaller(plan, reference)
                when (
                    request.searchConstraints.packageName.admitPackage { reference.element.relationPackageEvidence() }
                ) {
                    IntellijRelationPackageAdmission.ADMITTED -> Unit
                    IntellijRelationPackageAdmission.OUTSIDE_SCOPE -> return auxiliaryCaller(plan, reference)
                    IntellijRelationPackageAdmission.UNSUPPORTED ->
                        return incompleteItem(RelationLimitation.UNSUPPORTED_ITEM)
                }
            }
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

        private fun auxiliaryCaller(plan: IntellijRelationPlan.References, reference: PsiReference): Boolean =
            if (IntellijCallbackCallerProjection.isCandidate(reference))
                IntellijCallbackCallerProjection(projection, callbackEmitter, callableEmitter, collector, observation)
                    .read(plan, reference)
            else collector.dismissProviderItem()

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
            if (
                request.meaning == RelationMeaning.Callers &&
                    plan.confirmation is IntellijReferenceConfirmationPlan.ExactSymbol &&
                    (reference.element.parent as? org.jetbrains.kotlin.psi.KtCallableReferenceExpression)
                        ?.callableReference === reference.element
            )
                return callableEmitter.retainNamedReference(
                    CalleeProviderItem.Reference(reference, reference.element.nearestDeclaration())
                )
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
                is SupportedContainingDeclaration.Found -> emitNamedCall(reference, subject, related.projection)
                SupportedContainingDeclaration.Unresolved ->
                    callbackEmitter.retain(
                        reference,
                        subject,
                        NativeCallbackPolicy.Unavailable(CallOwnershipFailure.UnresolvedArgumentMapping),
                    )
                is SupportedContainingDeclaration.Excluded ->
                    callbackEmitter.retain(reference, subject, NativeCallbackPolicy.Excluded(related.evidence))
                SupportedContainingDeclaration.Unsupported ->
                    callbackEmitter.retain(
                        reference,
                        subject,
                        NativeCallbackPolicy.Unavailable(CallOwnershipFailure.UnsupportedBoundary),
                    )
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
                            factEmitter.emit(
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
            retainedReader().callees(request, subjectSource, collector, ::processCallee, ::termination)

        private fun processCallee(candidate: CalleeProviderItem): Boolean =
            when (candidate) {
                is CalleeProviderItem.CallbackSupplies -> callableEmitter.retainCallbackSupplies(candidate)
                is CalleeProviderItem.Unresolved -> incompleteCallee(candidate, RelationLimitation.UNRESOLVED_TARGET)
                is CalleeProviderItem.Reference ->
                    if (candidate.reference.element.parent is org.jetbrains.kotlin.psi.KtCallableReferenceExpression)
                        processNamedReference(candidate)
                    else processResolvedCallee(candidate)
            }

        private fun processResolvedCallee(candidate: CalleeProviderItem.Reference): Boolean =
            when (val resolved = projection.resolve(candidate.reference)) {
                IntellijK2ResolvedDeclaration.InvokeReceiver -> collector.dismissProviderItem()
                IntellijK2ResolvedDeclaration.Unresolved -> {
                    observation.count(IntellijReadCounter.RELATION_K2_UNAVAILABLE_TARGETS)
                    incompleteCallee(candidate, RelationLimitation.UNRESOLVED_TARGET)
                }
                is IntellijK2ResolvedDeclaration.Unsupported ->
                    incompleteCallee(candidate, RelationLimitation.UNSUPPORTED_ITEM)
                is IntellijK2ResolvedDeclaration.ParameterInvocation ->
                    callableEmitter.retainParameterInvocation(candidate, resolved)
                is IntellijK2ResolvedDeclaration.FunctionInvocation ->
                    callableEmitter.retainFunctionInvocation(candidate, resolved)
                is IntellijK2ResolvedDeclaration.SourceLess ->
                    callableEmitter.retainSourceLessCallable(candidate, resolved.callable)
                is IntellijK2ResolvedDeclaration.Found -> {
                    observation.count(IntellijReadCounter.RELATION_K2_CONFIRMED_TARGETS)
                    when (val owner = projection.callOwner(candidate.owner)) {
                        is Refinement.Refined ->
                            processCalleeTarget(owner.value, resolved.declaration, candidate.reference)
                        is Refinement.Rejected -> retainUnownedCallee(candidate, owner.failure)
                    }
                }
            }

        private fun processNamedReference(candidate: CalleeProviderItem.Reference): Boolean =
            when (val resolved = projection.resolve(candidate.reference)) {
                is IntellijK2ResolvedDeclaration.Found ->
                    admitCalleeTarget(resolved.declaration, candidate.reference) {
                        callableEmitter.retainNamedReference(candidate)
                    }
                is IntellijK2ResolvedDeclaration.FunctionInvocation ->
                    callableEmitter.retainFunctionInvocation(candidate, resolved)
                is IntellijK2ResolvedDeclaration.SourceLess ->
                    callableEmitter.retainSourceLessCallable(candidate, resolved.callable)
                IntellijK2ResolvedDeclaration.Unresolved,
                IntellijK2ResolvedDeclaration.InvokeReceiver,
                is IntellijK2ResolvedDeclaration.ParameterInvocation,
                is IntellijK2ResolvedDeclaration.Unsupported -> callableEmitter.retainNamedReference(candidate)
            }

        private val callableEmitter =
            IntellijCallableObservationEmitter(
                request,
                subject,
                projection,
                callbackEvidence.sources,
                collector,
                ::incompleteCallee,
                observation,
                summaries,
                resultProjection,
            )

        private fun retainUnownedCallee(candidate: CalleeProviderItem, failure: CallOwnershipFailure): Boolean {
            val policy =
                when (failure) {
                    is CallOwnershipFailure.ExcludedCallback -> NativeCallbackPolicy.Excluded(failure)
                    is CallOwnershipFailure.Incomplete -> NativeCallbackPolicy.Unavailable(failure)
                }
            val limitation =
                when (failure) {
                    is CallOwnershipFailure.ExcludedCallback -> RelationLimitation.UNRESOLVED_TARGET
                    is CallOwnershipFailure.Incomplete -> failure.limitation
                }
            return when (candidate) {
                is CalleeProviderItem.CallbackSupplies -> incompleteCallee(candidate, limitation)
                is CalleeProviderItem.Unresolved -> incompleteCallee(candidate, limitation)
                is CalleeProviderItem.Reference ->
                    when (val target = projection.resolve(candidate.reference)) {
                        IntellijK2ResolvedDeclaration.InvokeReceiver -> collector.dismissProviderItem()
                        is IntellijK2ResolvedDeclaration.Found -> {
                            observation.count(IntellijReadCounter.RELATION_K2_CONFIRMED_TARGETS)
                            admitCalleeTarget(target.declaration, candidate.reference) {
                                callbackEmitter.retain(candidate.reference, target.declaration, policy)
                            }
                        }
                        is IntellijK2ResolvedDeclaration.ParameterInvocation ->
                            callableEmitter.retainParameterInvocation(candidate, target)
                        is IntellijK2ResolvedDeclaration.FunctionInvocation ->
                            callableEmitter.retainFunctionInvocation(candidate, target)
                        is IntellijK2ResolvedDeclaration.SourceLess ->
                            callableEmitter.retainSourceLessCallable(candidate, target.callable)
                        is IntellijK2ResolvedDeclaration.Unsupported ->
                            incompleteCallee(candidate, RelationLimitation.UNSUPPORTED_ITEM)
                        IntellijK2ResolvedDeclaration.Unresolved -> {
                            observation.count(IntellijReadCounter.RELATION_K2_UNAVAILABLE_TARGETS)
                            incompleteCallee(candidate, RelationLimitation.UNRESOLVED_TARGET)
                        }
                    }
            }
        }

        private fun processCalleeTarget(
            owner: ContainingDeclaration.Found,
            target: PsiNamedElement,
            reference: KtReference,
        ): Boolean = admitCalleeTarget(target, reference) { emitCallee(owner, target, reference) }

        private fun admitCalleeTarget(target: PsiNamedElement, reference: KtReference, emit: () -> Boolean): Boolean {
            val file = target.containingFile?.virtualFile
            when (scope.admitProviderSite(file)) {
                RelationProviderScopeAdmission.ADMITTED -> Unit
                RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED -> {
                    observation.terminated(IntellijReadTermination.LIBRARY_POLICY_EXCLUSION)
                    return excludeCalleeTarget(
                        target,
                        reference,
                        io.github.amichne.kast.relation.contract.RelationScopeExclusionReason.LIBRARY_POLICY,
                    )
                }
                RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED -> {
                    observation.terminated(IntellijReadTermination.CALLEE_OUTSIDE_NATIVE_SCOPE)
                    return excludeCalleeTarget(
                        target,
                        reference,
                        io.github.amichne.kast.relation.contract.RelationScopeExclusionReason.SOURCE_DOMAIN,
                    )
                }
                RelationProviderScopeAdmission.UNAVAILABLE ->
                    return incompleteItem(
                        RelationLimitation.UNSUPPORTED_ITEM,
                        reference.element,
                        reference.rangeInElement,
                    )
            }
            return when (packageDisposition(target)) {
                ProviderItemDisposition.READY -> emit()
                ProviderItemDisposition.SKIPPED -> true
                ProviderItemDisposition.HALTED -> false
            }
        }

        private fun excludeCalleeTarget(
            target: PsiNamedElement,
            reference: KtReference,
            reason: io.github.amichne.kast.relation.contract.RelationScopeExclusionReason,
        ): Boolean {
            val evidence =
                when (val projected = projection.project(target)) {
                    is IntellijRelationDeclarationProjection.Projected -> projected.evidence
                    IntellijRelationDeclarationProjection.Unsupported ->
                        return incompleteItem(
                            RelationLimitation.UNSUPPORTED_ITEM,
                            reference.element,
                            reference.rangeInElement,
                        )
                }
            val occurrence =
                when (val sample = projection.omissionSample(reference.element, reference.rangeInElement)) {
                    is RelationOmissionSample.Located -> sample.occurrence
                    RelationOmissionSample.Unavailable ->
                        return incompleteItem(
                            RelationLimitation.UNSUPPORTED_ITEM,
                            reference.element,
                            reference.rangeInElement,
                        )
                }
            return when (
                val excluded =
                    io.github.amichne.kast.relation.contract.RelationScopeExclusion.fromNativeBoundary(
                        request,
                        occurrence,
                        evidence,
                        reason,
                    )
            ) {
                is Refinement.Refined -> collector.acceptScopeExclusion(excluded.value)
                is Refinement.Rejected -> collector.blockPartition(RelationLimitation.PROVIDER_INCOMPLETE)
            }
        }

        private fun emitNamedCall(
            reference: PsiReference,
            target: PsiNamedElement,
            related: IntellijRelationDeclarationProjection.Projected,
        ): Boolean {
            if (request.meaning != RelationMeaning.Callers && request.meaning != RelationMeaning.Callees)
                return factEmitter.emit(related, reference.element, reference.rangeInElement)
            val callback =
                when (val result = callbackEmitter.observe(reference, target, NativeCallbackPolicy.Inline)) {
                    is Refinement.Refined ->
                        when (val observed = result.value) {
                            NativeCallbackObservation.None -> null
                            NativeCallbackObservation.Excluded -> return collector.dismissProviderItem()
                            is NativeCallbackObservation.Observed -> observed.value
                        }
                    is Refinement.Rejected ->
                        return incompleteItem(result.failure, reference.element, reference.rangeInElement)
                }
            return factEmitter.emit(related, reference.element, reference.rangeInElement, callback)
        }

        private fun incompleteCallee(candidate: CalleeProviderItem, limitation: RelationLimitation): Boolean =
            when (candidate) {
                is CalleeProviderItem.Reference ->
                    incompleteItem(limitation, candidate.reference.element, candidate.reference.rangeInElement)
                is CalleeProviderItem.CallbackSupplies ->
                    incompleteItem(
                        limitation,
                        candidate.call,
                        candidate.call.textRange.shiftLeft(candidate.call.textRange.startOffset),
                    )
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
            if (owner.declaration === subject)
                when (val projected = projection.project(target)) {
                    is IntellijRelationDeclarationProjection.Projected -> emitNamedCall(reference, target, projected)
                    IntellijRelationDeclarationProjection.Unsupported ->
                        incompleteItem(RelationLimitation.UNSUPPORTED_ITEM, reference.element, reference.rangeInElement)
                }
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
