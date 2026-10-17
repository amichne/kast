package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

/** One admission owner binds each callable target to its request and exact source frame. */
internal object RelationCallableTargetAdmission {
    fun admit(
        target: RelationCallableTarget,
        request: RelationRequest,
        occurrence: RelationOccurrence,
        body: RelationCallableBody,
    ): Refinement<Unit, RelationCallableObservationFailure> {
        val admitted =
            when (target) {
                is RelationCallableTarget.NamedReference -> target.admitsNamedTarget(request, occurrence)
                is RelationCallableTarget.DirectInvocations ->
                    target.invocations.admitsObservation(request, occurrence, body)
                is RelationCallableTarget.CallbackSupplies ->
                    target.supplies.values.all { it.admitsObservation(request, occurrence, body) }
                is RelationCallableTarget.ParameterInvocation ->
                    return if (target.admitsParameter(request, occurrence, body)) Refinement.Refined(Unit)
                    else Refinement.Rejected(RelationCallableObservationFailure.PARAMETER_AUTHORITY_MISMATCH)
                is RelationCallableTarget.SourceLess ->
                    return if (target.validDisposition(request)) Refinement.Refined(Unit)
                    else Refinement.Rejected(RelationCallableObservationFailure.BOUNDARY_POLICY_MISMATCH)
                is RelationCallableTarget.UnavailableSupply,
                is RelationCallableTarget.UnavailableReference -> true
            }
        return if (admitted) Refinement.Refined(Unit)
        else Refinement.Rejected(RelationCallableObservationFailure.SUBJECT_MISMATCH)
    }
}

private fun RelationCallableTarget.SourceLess.validDisposition(request: RelationRequest): Boolean {
    val libraries =
        (request.searchScope as? io.github.amichne.kast.symbol.contract.SymbolSearchScope.Workspace)?.libraries
            ?: io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.EXCLUDE
    return when (disposition) {
        SourceLessCallableDisposition.BUILTIN_BOUNDARY -> callable.moduleKind == SourceLessCallableModuleKind.BUILTINS
        SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED ->
            callable.moduleKind == SourceLessCallableModuleKind.LIBRARY &&
                libraries == io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.EXCLUDE
        SourceLessCallableDisposition.LIBRARY_SOURCE_UNAVAILABLE ->
            callable.moduleKind == SourceLessCallableModuleKind.LIBRARY &&
                libraries == io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.INCLUDE
    }
}

private fun CompleteCallbackSupply.admitsObservation(
    request: RelationRequest,
    occurrence: RelationOccurrence,
    body: RelationCallableBody,
): Boolean {
    val invocation = supplier.binding.invocation
    if (invocation.basis != request.subject.lease.identity || supplier.binding.invocationOwner != body) return false
    return occurrence.file == invocation.enclosing.file && occurrence.range == invocation.range
}

private fun CompleteCallbackDirectInvocations.admitsObservation(
    request: RelationRequest,
    occurrence: RelationOccurrence,
    body: RelationCallableBody,
): Boolean =
    binding.basis == request.subject.lease.identity && binding.occurrence == occurrence && binding.owner == body

private fun RelationCallableTarget.ParameterInvocation.admitsParameter(
    request: RelationRequest,
    occurrence: RelationOccurrence,
    body: RelationCallableBody,
): Boolean {
    if (
        parameter.callable.lease != request.subject.lease ||
            parameter.callable.file != occurrence.file ||
            !parameter.callable.range.containsValueRange(body.range)
    )
        return false
    if (!suppliers.admits(parameter, CallbackEvidenceDomain.forRequest(request).fingerprint)) return false
    if (!admitsResultProjection(request)) return false
    if (invocation.occurrence != occurrence || invocation.owner != body || invocation.forwardings.isNotEmpty())
        return false
    return invocation.callableTransfers.all { transfer ->
        listOf(transfer.source, transfer.target).all { site ->
            site.basis == request.subject.lease.identity &&
                site.enclosing.valueIdentity == parameter.callable.valueIdentity
        }
    }
}

private fun RelationCallableTarget.ParameterInvocation.admitsResultProjection(request: RelationRequest): Boolean =
    when (val projection = resultProjection) {
        is CallbackResultProjection.Unproven -> true
        is CallbackResultProjection.Admitted -> {
            val proof = suppliers as? CallbackSupplierInventoryEvidence.Exhaustive
            proof != null &&
                projection.membership.belongsTo(request) &&
                projection.membership.supportsSuppliers(proof.inventory)
        }
    }

private fun RelationCallableTarget.NamedReference.admitsNamedTarget(
    request: RelationRequest,
    occurrence: RelationOccurrence,
): Boolean {
    if (reference.occurrence != occurrence || reference.target.lease != request.subject.lease) return false
    when (val projection = resultProjection) {
        is CallbackResultProjection.Unproven -> Unit
        is CallbackResultProjection.Admitted ->
            if (
                !projection.membership.belongsTo(request) ||
                    !projection.membership.supportsReference(request.meaning, reference)
            )
                return false
    }
    return request.meaning != RelationMeaning.Callers ||
        RevalidatedRelationEndpoint.validate(request.subject, reference.target.evidence) is Refinement.Refined
}
