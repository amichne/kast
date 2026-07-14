package io.github.amichne.kast.api.continuation

sealed interface ContinuationIssueResult<out Token> {
    data class Issued<Token>(val token: Token) : ContinuationIssueResult<Token>

    data class Rejected(
        val failure: ContinuationAccessFailure.StoreClosed,
    ) : ContinuationIssueResult<Nothing>
}
