package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackResultMembership
import io.github.amichne.kast.relation.contract.CallbackResultProjection
import io.github.amichne.kast.relation.contract.CallbackResultSite
import io.github.amichne.kast.relation.contract.RelationRequest

/** Scripted native membership for protocol tests, without an IDE or compiler effect. */
internal fun scriptedCallbackProjection(request: RelationRequest, site: CallbackResultSite): CallbackResultProjection =
    when (val admitted = CallbackResultMembership.fromNativeScope(request, site)) {
        is Refinement.Refined -> CallbackResultProjection.Admitted(admitted.value)
        is Refinement.Rejected ->
            error("The scripted selected site does not fit its declared domain: ${admitted.failure}")
    }
