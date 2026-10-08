package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryCallbackExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedUnavailableCauseDocument
import io.github.amichne.kast.relation.contract.CallbackExclusionReason
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackNamedCallUnavailableCause

internal fun CallbackNamedCallUnavailableCause.protocolCallbackDocument(): QueryCallbackNamedUnavailableCauseDocument =
    when (this) {
        CallbackNamedCallUnavailableCause.UNRESOLVED_ARGUMENT_MAPPING ->
            QueryCallbackNamedUnavailableCauseDocument.UNRESOLVED_ARGUMENT_MAPPING
        CallbackNamedCallUnavailableCause.UNSUPPORTED_BOUNDARY ->
            QueryCallbackNamedUnavailableCauseDocument.UNSUPPORTED_BOUNDARY
    }

internal fun CallbackExclusionReason.protocolCallbackDocument(): QueryCallbackExclusionReasonDocument =
    when (this) {
        CallbackExclusionReason.NON_INLINE_ARGUMENT -> QueryCallbackExclusionReasonDocument.NON_INLINE_ARGUMENT
        CallbackExclusionReason.NOINLINE_ARGUMENT -> QueryCallbackExclusionReasonDocument.NOINLINE_ARGUMENT
        CallbackExclusionReason.CROSSINLINE_ARGUMENT -> QueryCallbackExclusionReasonDocument.CROSSINLINE_ARGUMENT
        CallbackExclusionReason.STORED_CALLBACK -> QueryCallbackExclusionReasonDocument.STORED_CALLBACK
        CallbackExclusionReason.RETURNED_CALLBACK -> QueryCallbackExclusionReasonDocument.RETURNED_CALLBACK
        CallbackExclusionReason.DEFAULT_PARAMETER -> QueryCallbackExclusionReasonDocument.DEFAULT_PARAMETER
    }

internal fun CallbackInvocationFlowCause.protocolCallbackDocument(): QueryCallbackFlowCauseDocument =
    when (this) {
        CallbackInvocationFlowCause.FINALLY_UNSUPPORTED -> QueryCallbackFlowCauseDocument.FINALLY_UNSUPPORTED
        CallbackInvocationFlowCause.ABRUPT_COMPLETION -> QueryCallbackFlowCauseDocument.ABRUPT_COMPLETION
        CallbackInvocationFlowCause.STORED_CALLBACK -> QueryCallbackFlowCauseDocument.STORED_CALLBACK
        CallbackInvocationFlowCause.RETURNED_CALLBACK -> QueryCallbackFlowCauseDocument.RETURNED_CALLBACK
        CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY ->
            QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY
        CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE ->
            QueryCallbackFlowCauseDocument.ANONYMOUS_IDENTITY_UNAVAILABLE
        CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING ->
            QueryCallbackFlowCauseDocument.UNRESOLVED_ARGUMENT_MAPPING
        CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE ->
            QueryCallbackFlowCauseDocument.UNRESOLVED_PARAMETER_REFERENCE
        CallbackInvocationFlowCause.EXTERNAL_CALLABLE -> QueryCallbackFlowCauseDocument.EXTERNAL_CALLABLE
        CallbackInvocationFlowCause.OUTSIDE_DOMAIN -> QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN
        CallbackInvocationFlowCause.PARAMETER_ESCAPES -> QueryCallbackFlowCauseDocument.PARAMETER_ESCAPES
        CallbackInvocationFlowCause.CALLBACK_CYCLE -> QueryCallbackFlowCauseDocument.CALLBACK_CYCLE
        CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION ->
            QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION
        CallbackInvocationFlowCause.NO_INVOCATION_PROVEN -> QueryCallbackFlowCauseDocument.NO_INVOCATION_PROVEN
        CallbackInvocationFlowCause.WORK_LIMIT_REACHED -> QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED
        CallbackInvocationFlowCause.TIME_LIMIT_REACHED -> QueryCallbackFlowCauseDocument.TIME_LIMIT_REACHED
        CallbackInvocationFlowCause.RESULT_LIMIT_REACHED -> QueryCallbackFlowCauseDocument.RESULT_LIMIT_REACHED
        CallbackInvocationFlowCause.BYTE_LIMIT_REACHED -> QueryCallbackFlowCauseDocument.BYTE_LIMIT_REACHED
    }

internal fun CallbackInvocationFlowFailure.protocolCallbackDocument(): QueryCallbackFlowFailureDocument =
    when (this) {
        CallbackInvocationFlowFailure.INVALID_SCAN_PROOF -> QueryCallbackFlowFailureDocument.INVALID_SCAN_PROOF
        CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH ->
            QueryCallbackFlowFailureDocument.INVALID_FORWARDING_PATH
        CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH -> QueryCallbackFlowFailureDocument.OWNER_BINDING_MISMATCH
        CallbackInvocationFlowFailure.DUPLICATE_OWNER_BINDING ->
            QueryCallbackFlowFailureDocument.DUPLICATE_OWNER_BINDING
        CallbackInvocationFlowFailure.BASIS_MISMATCH -> QueryCallbackFlowFailureDocument.BASIS_MISMATCH
        CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT -> QueryCallbackFlowFailureDocument.BODY_OUTSIDE_ARGUMENT
        CallbackInvocationFlowFailure.PARAMETER_OUTSIDE_CALLABLE ->
            QueryCallbackFlowFailureDocument.PARAMETER_OUTSIDE_CALLABLE
        CallbackInvocationFlowFailure.INVALID_PARAMETER_POSITION ->
            QueryCallbackFlowFailureDocument.INVALID_PARAMETER_POSITION
        CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_CALLABLE ->
            QueryCallbackFlowFailureDocument.INVOCATION_OUTSIDE_CALLABLE
        CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_OWNER ->
            QueryCallbackFlowFailureDocument.INVOCATION_OUTSIDE_OWNER
        CallbackInvocationFlowFailure.UNBOUND_INVOCATION -> QueryCallbackFlowFailureDocument.UNBOUND_INVOCATION
        CallbackInvocationFlowFailure.DUPLICATE_INVOCATION -> QueryCallbackFlowFailureDocument.DUPLICATE_INVOCATION
        CallbackInvocationFlowFailure.MISSING_OBLIGATION -> QueryCallbackFlowFailureDocument.MISSING_OBLIGATION
        CallbackInvocationFlowFailure.UNSUPPORTED_CALLABLE_TRANSFER ->
            QueryCallbackFlowFailureDocument.UNSUPPORTED_CALLABLE_TRANSFER
        CallbackInvocationFlowFailure.INVALID_CALLABLE_TRANSFER_PATH ->
            QueryCallbackFlowFailureDocument.INVALID_CALLABLE_TRANSFER_PATH
        CallbackInvocationFlowFailure.CALLABLE_TRANSFER_BINDING_MISMATCH ->
            QueryCallbackFlowFailureDocument.CALLABLE_TRANSFER_BINDING_MISMATCH
        CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_SUPPLYING_OWNER ->
            QueryCallbackFlowFailureDocument.INVOCATION_OUTSIDE_SUPPLYING_OWNER
    }

internal fun CallbackInvocationScan.protocolCallbackDocument(): QueryCallbackInvocationScanDocument =
    when (this) {
        CallbackInvocationScan.EXHAUSTIVE -> QueryCallbackInvocationScanDocument.EXHAUSTIVE
        CallbackInvocationScan.INCOMPLETE -> QueryCallbackInvocationScanDocument.INCOMPLETE
        CallbackInvocationScan.NOT_APPLICABLE -> QueryCallbackInvocationScanDocument.NOT_APPLICABLE
    }
