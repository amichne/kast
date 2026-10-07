package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOmissionSample
import io.github.amichne.kast.relation.contract.RelationRequest

/** All live ownership/target evidence detaches before the provider item is consumed. */
internal fun detachCallbackObservation(
    request: RelationRequest,
    reference: PsiReference,
    immediate: org.jetbrains.kotlin.psi.KtFunction,
    policy: NativeCallbackPolicy,
    target: PsiNamedElement,
    projection: IntellijK2RelationProjection,
    scope: CompiledRelationScope,
    admitWork: () -> CallbackWorkAdmission,
    summaries: CallbackParameterSummaries,
): Refinement<RelationCallbackObservation, RelationLimitation> {
    val lexical =
        (ContainingDeclaration.Deferred(immediate).enclosingDeclaration() as? ContainingDeclaration.Found)
            ?: return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
    val ownerEvidence =
        when (val owner = projection.project(lexical.declaration)) {
            is IntellijRelationDeclarationProjection.Projected -> owner.evidence
            IntellijRelationDeclarationProjection.Unsupported ->
                return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        }
    val targetEvidence =
        when (val projected = projection.project(target)) {
            is IntellijRelationDeclarationProjection.Projected -> projected.evidence
            IntellijRelationDeclarationProjection.Unsupported ->
                return Refinement.Rejected(RelationLimitation.UNRESOLVED_TARGET)
        }
    val occurrence =
        when (val located = projection.omissionSample(reference.element, reference.rangeInElement)) {
            is RelationOmissionSample.Located -> located.occurrence
            RelationOmissionSample.Unavailable -> return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        }
    val body = immediate
    val callback =
        when (val located = projection.omissionSample(body, body.textRange.shiftLeft(body.textRange.startOffset))) {
            is RelationOmissionSample.Located -> located.occurrence
            RelationOmissionSample.Unavailable -> return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        }
    val namedPolicy =
        when (val admitted = detachNamedCallbackPolicy(policy, projection)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val flow = readCallbackInvocationFlow(immediate, ownerEvidence, scope, projection, admitWork, summaries)
    return when (
        val result =
            RelationCallbackObservation.fromNativeBoundary(
                request,
                occurrence,
                targetEvidence,
                ownerEvidence,
                callback,
                namedPolicy,
                flow,
            )
    ) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> Refinement.Rejected(RelationLimitation.PROVIDER_INCOMPLETE)
    }
}

private fun detachNamedCallbackPolicy(
    policy: NativeCallbackPolicy,
    projection: IntellijK2RelationProjection,
): Refinement<io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy, RelationLimitation> {
    return Refinement.Refined(
        when (policy) {
            NativeCallbackPolicy.Direct ->
                io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy.AdmittedDirect
            NativeCallbackPolicy.Inline ->
                io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy.AdmittedInline
            is NativeCallbackPolicy.Unavailable ->
                io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy.Unavailable(
                    when (policy.evidence) {
                        CallOwnershipFailure.UnsupportedBoundary ->
                            io.github.amichne.kast.relation.contract.CallbackNamedCallUnavailableCause
                                .UNSUPPORTED_BOUNDARY
                        CallOwnershipFailure.UnresolvedArgumentMapping ->
                            io.github.amichne.kast.relation.contract.CallbackNamedCallUnavailableCause
                                .UNRESOLVED_ARGUMENT_MAPPING
                    }
                )
            is NativeCallbackPolicy.Excluded -> {
                val boundary = policy.evidence.boundary
                val excludedOccurrence =
                    when (
                        val located =
                            projection.omissionSample(
                                boundary,
                                boundary.textRange.shiftLeft(boundary.textRange.startOffset),
                            )
                    ) {
                        is RelationOmissionSample.Located -> located.occurrence
                        RelationOmissionSample.Unavailable ->
                            return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
                    }
                io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy.Excluded(
                    policy.evidence.reason,
                    excludedOccurrence,
                )
            }
        }
    )
}

internal sealed interface NativeCallbackPolicy {
    data object Direct : NativeCallbackPolicy

    data object Inline : NativeCallbackPolicy

    data class Unavailable(val evidence: CallOwnershipFailure.Incomplete) : NativeCallbackPolicy

    data class Excluded(val evidence: CallOwnershipFailure.ExcludedCallback) : NativeCallbackPolicy
}

internal sealed interface NativeCallbackObservation {
    data object None : NativeCallbackObservation

    data class Observed(val value: io.github.amichne.kast.relation.contract.RelationCallbackObservation) :
        NativeCallbackObservation
}

internal class IntellijCallbackObservationEmitter(
    private val request: RelationRequest,
    private val projection: IntellijK2RelationProjection,
    private val scope: CompiledRelationScope,
    private val collector: IntellijRelationCollector,
    private val omit: (RelationLimitation, com.intellij.psi.PsiElement, com.intellij.openapi.util.TextRange) -> Boolean,
) {
    private val summaries = CallbackParameterSummaries(request.budget)

    fun observe(
        reference: PsiReference,
        target: PsiNamedElement,
        policy: NativeCallbackPolicy,
    ): Refinement<NativeCallbackObservation, RelationLimitation> {
        val immediate = reference.element.nearestDeclaration()
        if (immediate !is ContainingDeclaration.Deferred) return Refinement.Refined(NativeCallbackObservation.None)
        val literal =
            immediate.boundary as? org.jetbrains.kotlin.psi.KtFunction
                ?: return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        return when (
            val detached =
                detachCallbackObservation(
                    request,
                    reference,
                    literal,
                    if (
                        policy == NativeCallbackPolicy.Inline &&
                            classifyCallbackFunctionSupply(literal) is IntellijCallbackLambdaSupply.Invocation
                    )
                        NativeCallbackPolicy.Direct
                    else policy,
                    target,
                    projection,
                    scope,
                    collector::admitCallbackWork,
                    summaries,
                )
        ) {
            is Refinement.Refined -> Refinement.Refined(NativeCallbackObservation.Observed(detached.value))
            is Refinement.Rejected -> detached
        }
    }

    fun retain(reference: PsiReference, target: PsiNamedElement, policy: NativeCallbackPolicy): Boolean {
        val unavailable = (policy as? NativeCallbackPolicy.Unavailable)?.evidence?.limitation
        return when (val observed = observe(reference, target, policy)) {
            is Refinement.Refined ->
                when (val evidence = observed.value) {
                    NativeCallbackObservation.None ->
                        omit(
                            unavailable ?: RelationLimitation.PROVIDER_INCOMPLETE,
                            reference.element,
                            reference.rangeInElement,
                        )
                    is NativeCallbackObservation.Observed ->
                        collector.acceptCallbackObservation(evidence.value, unavailable)
                }
            is Refinement.Rejected -> omit(observed.failure, reference.element, reference.rangeInElement)
        }
    }
}
