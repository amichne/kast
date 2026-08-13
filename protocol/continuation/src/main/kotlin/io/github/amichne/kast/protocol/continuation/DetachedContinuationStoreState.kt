package io.github.amichne.kast.protocol.continuation

internal data class OwnedContinuationState(
    val binding: ContinuationBinding,
    val records: List<DetachedContinuationRecord>,
    val encodedBytes: ContinuationByteCount,
    val issuedAtNanos: ContinuationIssuedAtNanos,
)

internal data class ContinuationEntry(
    val state: OwnedContinuationState,
    val position: ContinuationResumePosition,
)

internal enum class ContinuationStoreLifecycle {
    OPEN,
    CLOSED,
}

internal enum class ContinuationExpiryState {
    LIVE,
    EXPIRED,
}

internal sealed interface ContinuationStatePreparation {
    data class Prepared(
        val records: List<DetachedContinuationRecord>,
        val encodedBytes: ContinuationByteCount,
    ) : ContinuationStatePreparation

    data class Rejected(val failure: ContinuationIssueFailure) : ContinuationStatePreparation
}

internal sealed interface ContinuationBindingAdmission {
    data object Admitted : ContinuationBindingAdmission

    data class Rejected(val failure: ContinuationAccessFailure) : ContinuationBindingAdmission
}

internal sealed interface ContinuationTokenPublication {
    data class Published(val token: ContinuationToken) : ContinuationTokenPublication

    data class Rejected(
        val failure: ContinuationTokenPublicationFailure,
    ) : ContinuationTokenPublication
}

internal enum class ContinuationTokenPublicationFailure {
    COLLISION,
    ISSUER_FAILURE,
}

internal sealed interface ContinuationPageSelection {
    data class Selected(
        val records: List<DetachedContinuationRecord>,
        val encodedBytes: ContinuationByteCount,
        val nextPosition: ContinuationResumePosition,
    ) : ContinuationPageSelection

    data class Rejected(val failure: ContinuationAccessFailure) : ContinuationPageSelection
}

internal fun issueRejected(
    failure: ContinuationIssueFailure,
): ContinuationIssueResult.Rejected = ContinuationIssueResult.Rejected(failure)

internal fun resumeRejected(
    failure: ContinuationAccessFailure,
): ContinuationResumeResult.Rejected = ContinuationResumeResult.Rejected(failure)

internal fun bindingRejected(
    failure: ContinuationAccessFailure,
): ContinuationBindingAdmission.Rejected = ContinuationBindingAdmission.Rejected(failure)

/** Preserves token-publication failure identity at the issue boundary. */
internal fun ContinuationTokenPublicationFailure.issueFailure(): ContinuationIssueFailure =
    when (this) {
        ContinuationTokenPublicationFailure.COLLISION -> ContinuationIssueFailure.TOKEN_COLLISION
        ContinuationTokenPublicationFailure.ISSUER_FAILURE ->
            ContinuationIssueFailure.TOKEN_ISSUER_FAILURE
    }

/** Preserves token-publication failure identity at the resume boundary. */
internal fun ContinuationTokenPublicationFailure.accessFailure(): ContinuationAccessFailure =
    when (this) {
        ContinuationTokenPublicationFailure.COLLISION -> ContinuationAccessFailure.TOKEN_COLLISION
        ContinuationTokenPublicationFailure.ISSUER_FAILURE ->
            ContinuationAccessFailure.TOKEN_ISSUER_FAILURE
    }
