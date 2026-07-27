package io.github.amichne.kast.api.continuation

class ServerHeldContinuationStore<
    Token : Any,
    Query : Any,
    State : ContinuationOwnedState,
    Projection : ContinuationProjection,
>(
    capacity: ContinuationCapacity,
    timeToLive: ContinuationTtl,
    tokenIssuer: ContinuationTokenIssuer<Token>,
    stateDisposer: ContinuationStateDisposer<State>,
    clock: ContinuationClock = ContinuationClock.System,
) : AutoCloseable {
    private val ownership = ContinuationStoreOwnership<Token, Query, State, Projection>(
        capacity = capacity,
        timeToLive = timeToLive,
        tokenIssuer = tokenIssuer,
        stateDisposer = stateDisposer,
        clock = clock,
    )

    fun issue(query: Query, state: State): ContinuationIssueResult<Token> =
        when (val preparation = ownership.prepareIssue(query, state)) {
            is ContinuationIssuePreparation.Prepared -> completeIssuePublication(preparation)
            is ContinuationIssuePreparation.Rejected -> {
                ownership.disposeRegistered(preparation.disposal)
                ContinuationIssueResult.Rejected(preparation.failure)
            }
            is ContinuationIssuePreparation.IssuerFailed -> {
                val disposeFailure =
                    ownership.disposeRegisteredCapturingFailure(preparation.disposal)
                disposeFailure?.let(preparation.failure::addSuppressed)
                throw preparation.failure
            }
        }

    private fun completeIssuePublication(
        preparation: ContinuationIssuePreparation.Prepared<Token, Query, State>,
    ): ContinuationIssueResult<Token> {
        val publicationFailure =
            ownership.disposeRegisteredCapturingFailure(preparation.disposal)
        if (publicationFailure != null) {
            rollbackIssuePublication(preparation.entry.state, publicationFailure)
        }

        val terminalDisposal = ownership.completeIssuePublication(preparation)
        if (terminalDisposal != null) {
            ownership.disposeRegistered(terminalDisposal)
            return ContinuationIssueResult.Rejected(ContinuationAccessFailure.StoreClosed)
        }
        return ContinuationIssueResult.Issued(preparation.token)
    }

    private fun rollbackIssuePublication(state: State, publicationFailure: Throwable): Nothing {
        val rollbackDisposal = ownership.rollbackIssuePublication(state)
        ownership.disposeRegisteredCapturingFailure(rollbackDisposal)
            ?.let(publicationFailure::addSuppressed)
        throw publicationFailure
    }

    fun lease(
        token: Token,
        query: Query,
        projection: ContinuationStateProjection<State, Projection>,
    ): ContinuationLeaseResult<Projection> = when (val claim = ownership.claim(token, query)) {
        is ContinuationClaimDecision.Rejected -> ContinuationLeaseResult.Rejected(claim.failure)
        is ContinuationClaimDecision.Discarded -> {
            ownership.disposeRegistered(claim.disposal)
            ContinuationLeaseResult.Rejected(claim.failure)
        }
        is ContinuationClaimDecision.Claimed -> leaseClaim(claim.token, claim.entry, projection)
    }

    fun consume(
        token: Token,
        query: Query,
        transition: ContinuationStateTransition<State, Projection, Query>,
    ): ContinuationConsumeResult<Token, Projection> =
        when (val claim = ownership.claim(token, query)) {
            is ContinuationClaimDecision.Rejected ->
                ContinuationConsumeResult.Rejected(claim.failure)
            is ContinuationClaimDecision.Discarded -> {
                ownership.disposeRegistered(claim.disposal)
                ContinuationConsumeResult.Rejected(claim.failure)
            }
            is ContinuationClaimDecision.Claimed ->
                consumeClaim(claim.token, claim.entry.state, transition)
        }

    fun invalidate(token: Token): ContinuationInvalidationResult =
        when (val decision = ownership.invalidate(token)) {
            is ContinuationInvalidationDecision.Rejected ->
                ContinuationInvalidationResult.Rejected(decision.failure)
            is ContinuationInvalidationDecision.Discarded -> {
                ownership.disposeRegistered(decision.disposal)
                decision.failure?.let(ContinuationInvalidationResult::Rejected)
                    ?: ContinuationInvalidationResult.Invalidated
            }
        }

    override fun close() {
        ownership.close()
    }

    private fun leaseClaim(
        token: Token,
        entry: ContinuationEntry<Query, State>,
        projection: ContinuationStateProjection<State, Projection>,
    ): ContinuationLeaseResult<Projection> {
        val output = try {
            projection.project(entry.state)
        } catch (failure: Throwable) {
            val disposeFailure = ownership.disposeCapturingFailure(entry.state)
            ownership.finishCallback(token, disposeFailure)
            disposeFailure?.let(failure::addSuppressed)
            throw failure
        }

        val retainDecision = ownership.retainLease(token, entry)
        val disposeFailure = when (retainDecision) {
            ContinuationLeaseRetention.Terminal ->
                ownership.disposeCapturingFailure(entry.state)
            is ContinuationLeaseRetention.Retained ->
                ownership.disposeRegisteredCapturingFailure(retainDecision.disposal)
        }
        if (retainDecision is ContinuationLeaseRetention.Terminal) {
            ownership.finishCallback(token, disposeFailure)
        }
        disposeFailure?.let { throw it }
        return ContinuationLeaseResult.Granted(output)
    }

    private fun consumeClaim(
        token: Token,
        state: State,
        transition: ContinuationStateTransition<State, Projection, Query>,
    ): ContinuationConsumeResult<Token, Projection> {
        val result = try {
            transition.transition(state)
        } catch (failure: Throwable) {
            val disposeFailure = ownership.disposeCapturingFailure(state)
            ownership.finishCallback(token, disposeFailure)
            disposeFailure?.let(failure::addSuppressed)
            throw failure
        }

        return when (result) {
            is ContinuationTransition.Complete -> {
                val disposeFailure = ownership.disposeCapturingFailure(state)
                ownership.finishCallback(token, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Completed(result.output)
            }
            is ContinuationTransition.Reissue -> reissueClaim(token, state, result)
        }
    }

    private fun reissueClaim(
        claimedToken: Token,
        state: State,
        transition: ContinuationTransition.Reissue<Projection, Query>,
    ): ContinuationConsumeResult<Token, Projection> =
        when (val preparation = ownership.prepareReissue(transition, state)) {
            is ContinuationReissuePreparation.Prepared ->
                completeReissuePublication(claimedToken, transition.output, preparation)
            ContinuationReissuePreparation.Terminal -> {
                val disposeFailure = ownership.disposeCapturingFailure(state)
                ownership.finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Rejected(ContinuationAccessFailure.StoreClosed)
            }
            is ContinuationReissuePreparation.Rejected -> {
                val disposeFailure = ownership.disposeCapturingFailure(state)
                ownership.finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let { throw it }
                ContinuationConsumeResult.Rejected(preparation.failure)
            }
            is ContinuationReissuePreparation.IssuerFailed -> {
                val disposeFailure = ownership.disposeCapturingFailure(state)
                ownership.finishCallback(claimedToken, disposeFailure)
                disposeFailure?.let(preparation.failure::addSuppressed)
                throw preparation.failure
            }
        }

    private fun completeReissuePublication(
        claimedToken: Token,
        output: Projection,
        preparation: ContinuationReissuePreparation.Prepared<Token, Query, State>,
    ): ContinuationConsumeResult<Token, Projection> {
        val publicationFailure =
            ownership.disposeRegisteredCapturingFailure(preparation.disposal)
        if (publicationFailure != null) {
            rollbackReissuePublication(claimedToken, preparation.entry.state, publicationFailure)
        }

        val terminalDisposal =
            ownership.completeReissuePublication(claimedToken, preparation)
        if (terminalDisposal != null) {
            val disposeFailure =
                ownership.disposeRegisteredCapturingFailure(terminalDisposal)
            ownership.finishCallback(claimedToken, disposeFailure)
            disposeFailure?.let { throw it }
            return ContinuationConsumeResult.Rejected(ContinuationAccessFailure.StoreClosed)
        }
        return ContinuationConsumeResult.Reissued(output, preparation.token)
    }

    private fun rollbackReissuePublication(
        claimedToken: Token,
        state: State,
        publicationFailure: Throwable,
    ): Nothing {
        val rollbackDisposal = ownership.rollbackReissuePublication(state)
        val rollbackFailure =
            ownership.disposeRegisteredCapturingFailure(rollbackDisposal)
        ownership.finishCallback(claimedToken, rollbackFailure)
        rollbackFailure?.let(publicationFailure::addSuppressed)
        throw publicationFailure
    }
}
