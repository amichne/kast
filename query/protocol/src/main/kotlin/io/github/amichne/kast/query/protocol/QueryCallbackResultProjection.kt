package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackResultProjectionDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackResultSiteDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.relation.contract.CallbackResultProjection
import io.github.amichne.kast.relation.contract.CallbackResultSite

internal fun CallbackResultProjection.projectResultProjection(
    projection: CallbackProjection,
    subject: CallbackResultSubject,
): QueryCallbackResultProjectionDocument? =
    when (this) {
        is CallbackResultProjection.Unproven ->
            QueryCallbackResultProjectionDocument.Unproven(cause.protocolCallbackDocument())
        is CallbackResultProjection.Admitted -> {
            if (membership.subject != subject.native || membership.basis != projection.basis) return null
            QueryCallbackResultProjectionDocument.Admitted(
                subject.reference,
                membership.basis.identity.impactDocument().projectionValue() ?: return null,
                QueryRelationDomainFingerprint.parse(membership.domain.value).projectionValue() ?: return null,
                membership.meaning.protocolDocument(),
                membership.site.projectSite(projection) ?: return null,
            )
        }
    }

private fun CallbackResultSite.projectSite(projection: CallbackProjection): QueryCallbackResultSiteDocument? =
    when (this) {
        is CallbackResultSite.Occurrence ->
            projection.occurrence(occurrence)?.let(QueryCallbackResultSiteDocument::Occurrence)
        is CallbackResultSite.Invocation ->
            projection.invocation(invocation)?.let(QueryCallbackResultSiteDocument::Invocation)
        is CallbackResultSite.DirectInvocation ->
            projection.direct(binding)?.let(QueryCallbackResultSiteDocument::DirectInvocation)
        is CallbackResultSite.Target -> projection.callable(target)?.let(QueryCallbackResultSiteDocument::Target)
        is CallbackResultSite.AnonymousTarget ->
            (projection.body(body) as? QueryCallbackBodyDocument.Anonymous)?.let(
                QueryCallbackResultSiteDocument::AnonymousTarget
            )
    }

private fun <V, F> Refinement<V, F>.projectionValue(): V? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
