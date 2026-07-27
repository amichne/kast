package io.github.amichne.kast.api.continuation

internal data class ContinuationEntry<Query, State>(
    val query: Query,
    val state: State,
    val createdAtNanos: Long,
)

internal data class RegisteredContinuationDisposal<out State>(
    val states: List<State>,
)

internal sealed interface ContinuationIssuePreparation<out Token, out Query, out State> {
    data class Prepared<Token, Query, State>(
        val token: Token,
        val entry: ContinuationEntry<Query, State>,
        val disposal: RegisteredContinuationDisposal<State>?,
    ) : ContinuationIssuePreparation<Token, Query, State>

    data class Rejected<State>(
        val disposal: RegisteredContinuationDisposal<State>,
        val failure: ContinuationAccessFailure,
    ) : ContinuationIssuePreparation<Nothing, Nothing, State>

    data class IssuerFailed<State>(
        val disposal: RegisteredContinuationDisposal<State>,
        val failure: Throwable,
    ) : ContinuationIssuePreparation<Nothing, Nothing, State>
}

internal sealed interface ContinuationClaimDecision<out Token, out Query, out State> {
    data class Claimed<Token, Query, State>(
        val token: Token,
        val entry: ContinuationEntry<Query, State>,
    ) : ContinuationClaimDecision<Token, Query, State>

    data class Discarded<State>(
        val disposal: RegisteredContinuationDisposal<State>,
        val failure: ContinuationAccessFailure,
    ) : ContinuationClaimDecision<Nothing, Nothing, State>

    data class Rejected(
        val failure: ContinuationAccessFailure,
    ) : ContinuationClaimDecision<Nothing, Nothing, Nothing>
}

internal sealed interface ContinuationLeaseRetention<out State> {
    data object Terminal : ContinuationLeaseRetention<Nothing>

    data class Retained<State>(
        val disposal: RegisteredContinuationDisposal<State>?,
    ) : ContinuationLeaseRetention<State>
}

internal sealed interface ContinuationReissuePreparation<out Token, out Query, out State> {
    data object Terminal : ContinuationReissuePreparation<Nothing, Nothing, Nothing>

    data class Prepared<Token, Query, State>(
        val token: Token,
        val entry: ContinuationEntry<Query, State>,
        val disposal: RegisteredContinuationDisposal<State>?,
    ) : ContinuationReissuePreparation<Token, Query, State>

    data class Rejected(
        val failure: ContinuationAccessFailure,
    ) : ContinuationReissuePreparation<Nothing, Nothing, Nothing>

    data class IssuerFailed(
        val failure: Throwable,
    ) : ContinuationReissuePreparation<Nothing, Nothing, Nothing>
}

internal sealed interface ContinuationInvalidationDecision<out State> {
    data class Discarded<State>(
        val disposal: RegisteredContinuationDisposal<State>,
        val failure: ContinuationAccessFailure?,
    ) : ContinuationInvalidationDecision<State>

    data class Rejected(
        val failure: ContinuationAccessFailure,
    ) : ContinuationInvalidationDecision<Nothing>
}
