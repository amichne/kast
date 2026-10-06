package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Proven named-call ownership policy; it says nothing about whether the callback executes. */
enum class CallbackExclusionReason {
    NON_INLINE_ARGUMENT,
    NOINLINE_ARGUMENT,
    CROSSINLINE_ARGUMENT,
    STORED_CALLBACK,
    RETURNED_CALLBACK,
    DEFAULT_PARAMETER,
}

enum class RelationCallbackObservationFailure {
    MEANING_MISMATCH,
    SUBJECT_MISMATCH,
    OCCURRENCE_OUTSIDE_CALLBACK,
    CALLBACK_OUTSIDE_LEXICAL_OWNER,
    FLOW_IDENTITY_MISMATCH,
}

enum class CallbackNamedCallUnavailableCause {
    UNRESOLVED_ARGUMENT_MAPPING,
    UNSUPPORTED_BOUNDARY,
}

sealed interface CallbackNamedCallPolicy {
    data object AdmittedDirect : CallbackNamedCallPolicy

    data object AdmittedInline : CallbackNamedCallPolicy

    data class Unavailable(val cause: CallbackNamedCallUnavailableCause) : CallbackNamedCallPolicy

    data class Excluded(val reason: CallbackExclusionReason, val boundary: RelationOccurrence) : CallbackNamedCallPolicy
}

/** Exact callback invocation, lexical containment, named-call policy and independently proved flow. */
@ConsistentCopyVisibility
data class RelationCallbackObservation
private constructor(
    val subject: RelationEndpointFingerprint,
    val basis: SemanticReadAuthority,
    val requestedDomain: RelationSearchBoundary,
    val effectiveDomain: RelationScopeFingerprint,
    val meaning: RelationMeaning,
    val occurrence: RelationOccurrence,
    val target: CompilerGroundedSymbolEvidence,
    val lexicalOwner: CompilerGroundedSymbolEvidence,
    val callbackBody: RelationOccurrence,
    val policy: CallbackNamedCallPolicy,
    val flow: CallbackInvocationFlowRead,
) : Comparable<RelationCallbackObservation> {
    val retainedBytes: Long
        get() =
            4096L +
                canonicalProjection().length * 4L +
                when (val observed = flow) {
                    is CallbackInvocationFlowRead.Observed -> observed.flow.retainedBytes
                    is CallbackInvocationFlowRead.Unavailable,
                    is CallbackInvocationFlowRead.ContractRejected -> 256L
                }

    fun belongsTo(request: RelationRequest): Boolean =
        subject == request.subject.fingerprint &&
            basis == request.subject.lease &&
            requestedDomain == request.boundary &&
            effectiveDomain == request.scopeFingerprint &&
            meaning == request.meaning

    fun supportsNamedFact(fact: RelationFact): Boolean =
        (policy == CallbackNamedCallPolicy.AdmittedInline || policy == CallbackNamedCallPolicy.AdmittedDirect) &&
            fact.subject.fingerprint == subject &&
            fact.authority == basis.identity &&
            fact.meaning == meaning &&
            fact.occurrence == occurrence &&
            fact.source.matches(lexicalOwner) &&
            fact.target.matches(target)

    override fun compareTo(other: RelationCallbackObservation): Int =
        canonicalProjection().compareTo(other.canonicalProjection())

    fun canonicalProjection(): String =
        listOf(
                subject.value,
                effectiveDomain.value,
                meaning.toString(),
                occurrence.file.stableValue,
                occurrence.range.toString(),
                target.file.stableValue,
                target.range.toString(),
                target.compilerIdentity.value,
                lexicalOwner.file.stableValue,
                lexicalOwner.range.toString(),
                lexicalOwner.compilerIdentity.value,
                callbackBody.range.toString(),
                policy.canonicalProjection(),
                flow.canonicalProjection(),
            )
            .joinToString("\u0000")

    companion object {
        /** Native boundary must first confirm target and excluded callback ownership through K2. */
        fun fromNativeBoundary(
            request: RelationRequest,
            occurrence: RelationOccurrence,
            target: CompilerGroundedSymbolEvidence,
            lexicalOwner: CompilerGroundedSymbolEvidence,
            callbackBody: RelationOccurrence,
            policy: CallbackNamedCallPolicy,
            flow: CallbackInvocationFlowRead,
        ): Refinement<RelationCallbackObservation, RelationCallbackObservationFailure> =
            when {
                request.meaning != RelationMeaning.Callers && request.meaning != RelationMeaning.Callees ->
                    Refinement.Rejected(RelationCallbackObservationFailure.MEANING_MISMATCH)
                request.meaning == RelationMeaning.Callers && !request.subject.matches(target) ||
                    request.meaning == RelationMeaning.Callees && !request.subject.matches(lexicalOwner) ->
                    Refinement.Rejected(RelationCallbackObservationFailure.SUBJECT_MISMATCH)
                occurrence.file != callbackBody.file || !callbackBody.range.containsValueRange(occurrence.range) ->
                    Refinement.Rejected(RelationCallbackObservationFailure.OCCURRENCE_OUTSIDE_CALLBACK)
                callbackBody.file != lexicalOwner.file || !lexicalOwner.range.containsValueRange(callbackBody.range) ->
                    Refinement.Rejected(RelationCallbackObservationFailure.CALLBACK_OUTSIDE_LEXICAL_OWNER)
                policy is CallbackNamedCallPolicy.Excluded &&
                    (policy.boundary.file != callbackBody.file ||
                        !policy.boundary.range.containsValueRange(callbackBody.range) ||
                        !lexicalOwner.range.containsValueRange(policy.boundary.range)) ->
                    Refinement.Rejected(RelationCallbackObservationFailure.CALLBACK_OUTSIDE_LEXICAL_OWNER)
                !flow.matchesCallback(request, callbackBody) ->
                    Refinement.Rejected(RelationCallbackObservationFailure.FLOW_IDENTITY_MISMATCH)
                else ->
                    Refinement.Refined(
                        RelationCallbackObservation(
                            request.subject.fingerprint,
                            request.subject.lease,
                            request.boundary,
                            request.scopeFingerprint,
                            request.meaning,
                            occurrence,
                            target,
                            lexicalOwner,
                            callbackBody,
                            policy,
                            flow,
                        )
                    )
            }
    }
}

private fun RelationEndpoint.matches(evidence: CompilerGroundedSymbolEvidence): Boolean =
    file == evidence.file && range == evidence.range && compilerIdentity == evidence.compilerIdentity

private fun CallbackInvocationFlowRead.matchesCallback(
    request: RelationRequest,
    callback: RelationOccurrence,
): Boolean =
    when (this) {
        is CallbackInvocationFlowRead.Observed ->
            flow.basis == request.subject.lease.identity &&
                flow.body.file == callback.file &&
                flow.body.range == callback.range
        is CallbackInvocationFlowRead.Unavailable,
        is CallbackInvocationFlowRead.ContractRejected -> true
    }

private fun CallbackNamedCallPolicy.canonicalProjection(): String =
    when (this) {
        CallbackNamedCallPolicy.AdmittedDirect -> "ADMITTED_DIRECT"
        CallbackNamedCallPolicy.AdmittedInline -> "ADMITTED_INLINE"
        is CallbackNamedCallPolicy.Excluded -> "EXCLUDED:${reason.name}:${boundary.file.stableValue}:${boundary.range}"
        is CallbackNamedCallPolicy.Unavailable -> "UNAVAILABLE:${cause.name}"
    }
