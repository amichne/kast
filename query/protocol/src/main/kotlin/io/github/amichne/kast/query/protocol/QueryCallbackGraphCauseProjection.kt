package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphBoundaryDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphObligationsDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphObligationsFailure
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphProjectionFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphUnprovenExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.relation.contract.CallbackExclusionReason
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure

internal fun StaticCallbackGraphFailure.protocolGraphCause():
    Refinement<QueryCallbackGraphCauseDocument, QueryCallbackGraphProjectionFailureDocument> =
    when (this) {
        is StaticCallbackGraphFailure.Unavailable ->
            Refinement.Refined(QueryCallbackGraphCauseDocument.Unavailable(cause.protocolCallbackDocument()))
        is StaticCallbackGraphFailure.InvalidFlow ->
            Refinement.Refined(QueryCallbackGraphCauseDocument.InvalidFlow(cause.protocolCallbackDocument()))
        is StaticCallbackGraphFailure.ImmutableUnresolved -> unresolvedGraphCause(flow.obligations, flow.scan)
        is StaticCallbackGraphFailure.Unresolved -> unresolvedGraphCause(flow.obligations, flow.scan)
        is StaticCallbackGraphFailure.UnprovenPolicy ->
            when (val admitted = policy.protocolGraphPolicy()) {
                is Refinement.Refined ->
                    Refinement.Refined(QueryCallbackGraphCauseDocument.UnprovenPolicy(admitted.value))
                is Refinement.Rejected -> admitted
            }
        StaticCallbackGraphFailure.IncompleteScan -> Refinement.Refined(QueryCallbackGraphCauseDocument.IncompleteScan)
        StaticCallbackGraphFailure.UnsupportedDefaultSupply ->
            Refinement.Refined(QueryCallbackGraphCauseDocument.UnsupportedDefaultSupply)
        StaticCallbackGraphFailure.MissingNamedOwner ->
            Refinement.Refined(QueryCallbackGraphCauseDocument.MissingNamedOwner)
        StaticCallbackGraphFailure.SupplierIdentityMismatch ->
            Refinement.Refined(QueryCallbackGraphCauseDocument.SupplierIdentityMismatch)
        StaticCallbackGraphFailure.OutsideWorkspace ->
            Refinement.Refined(QueryCallbackGraphCauseDocument.OutsideWorkspace)
        StaticCallbackGraphFailure.NonCallableTarget ->
            Refinement.Refined(QueryCallbackGraphCauseDocument.NonCallableTarget)
        StaticCallbackGraphFailure.CyclicRoute -> Refinement.Refined(QueryCallbackGraphCauseDocument.CyclicRoute)
    }

private fun CallbackNamedCallPolicy.protocolGraphPolicy():
    Refinement<QueryCallbackGraphPolicyDocument, QueryCallbackGraphProjectionFailureDocument> =
    when (this) {
        CallbackNamedCallPolicy.AdmittedDirect ->
            Refinement.Rejected(QueryCallbackGraphProjectionFailureDocument.ADMITTED_DIRECT_POLICY_REJECTED)
        CallbackNamedCallPolicy.AdmittedInline ->
            Refinement.Rejected(QueryCallbackGraphProjectionFailureDocument.ADMITTED_INLINE_POLICY_REJECTED)
        is CallbackNamedCallPolicy.Unavailable ->
            Refinement.Refined(QueryCallbackGraphPolicyDocument.Unavailable(cause.protocolCallbackDocument()))
        is CallbackNamedCallPolicy.Excluded -> protocolExcludedPolicy()
    }

private fun CallbackNamedCallPolicy.Excluded.protocolExcludedPolicy():
    Refinement<QueryCallbackGraphPolicyDocument, QueryCallbackGraphProjectionFailureDocument> {
    val reason =
        when (val exclusion = reason.protocolUnprovenExclusion()) {
            is Refinement.Refined -> exclusion.value
            is Refinement.Rejected -> return exclusion
        }
    val file =
        when (val admitted = ProtocolText.parse(boundary.file.stableValue)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Admitted graph exclusion file is nonempty")
        }
    val range =
        when (
            val admitted =
                SourceRangeDocument.create(
                    queryPosition(boundary.range.startInclusive),
                    queryPosition(boundary.range.endExclusive),
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Admitted graph exclusion range is nonempty")
        }
    return Refinement.Refined(
        QueryCallbackGraphPolicyDocument.Excluded(reason, QueryCallbackGraphBoundaryDocument.from(file, range))
    )
}

private fun CallbackExclusionReason.protocolUnprovenExclusion():
    Refinement<QueryCallbackGraphUnprovenExclusionReasonDocument, QueryCallbackGraphProjectionFailureDocument> =
    when (this) {
        CallbackExclusionReason.STORED_CALLBACK ->
            Refinement.Refined(QueryCallbackGraphUnprovenExclusionReasonDocument.STORED_CALLBACK)
        CallbackExclusionReason.RETURNED_CALLBACK ->
            Refinement.Refined(QueryCallbackGraphUnprovenExclusionReasonDocument.RETURNED_CALLBACK)
        CallbackExclusionReason.DEFAULT_PARAMETER ->
            Refinement.Refined(QueryCallbackGraphUnprovenExclusionReasonDocument.DEFAULT_PARAMETER)
        CallbackExclusionReason.NON_INLINE_ARGUMENT ->
            Refinement.Rejected(QueryCallbackGraphProjectionFailureDocument.NON_INLINE_POLICY_REJECTED)
        CallbackExclusionReason.NOINLINE_ARGUMENT ->
            Refinement.Rejected(QueryCallbackGraphProjectionFailureDocument.NOINLINE_POLICY_REJECTED)
        CallbackExclusionReason.CROSSINLINE_ARGUMENT ->
            Refinement.Rejected(QueryCallbackGraphProjectionFailureDocument.CROSSINLINE_POLICY_REJECTED)
    }

private fun unresolvedGraphCause(
    causes: Set<io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause>,
    scan: io.github.amichne.kast.relation.contract.CallbackInvocationScan,
): Refinement<QueryCallbackGraphCauseDocument, QueryCallbackGraphProjectionFailureDocument> =
    when (
        val admitted =
            QueryCallbackGraphObligationsDocument.from(
                causes.map { it.protocolCallbackDocument() }.sortedBy { it.ordinal }
            )
    ) {
        is Refinement.Refined ->
            Refinement.Refined(
                QueryCallbackGraphCauseDocument.Unresolved(admitted.value, scan.protocolCallbackDocument())
            )
        is Refinement.Rejected ->
            Refinement.Rejected(
                when (admitted.failure) {
                    QueryCallbackGraphObligationsFailure.EMPTY ->
                        QueryCallbackGraphProjectionFailureDocument.EMPTY_NATIVE_OBLIGATIONS
                    QueryCallbackGraphObligationsFailure.NON_CANONICAL ->
                        QueryCallbackGraphProjectionFailureDocument.NON_CANONICAL_NATIVE_OBLIGATIONS
                }
            )
    }
