package io.github.amichne.kast.protocol.continuation

/**
 * Bounded host-neutral owner of immutable detached continuation records. Every operation is
 * serialized so token consumption, reissue, invalidation, expiry, and resource release are atomic.
 */
class DetachedContinuationStore(
    private val limits: ContinuationStoreLimits,
    private val tokenIssuer: ContinuationTokenIssuer = ContinuationTokenIssuer.Random,
    private val clock: ContinuationClock = ContinuationClock.System,
) : AutoCloseable {
    private val lock = Any()
    private val entries = linkedMapOf<ContinuationToken, ContinuationEntry>()
    private var lifecycle = ContinuationStoreLifecycle.OPEN

    /**
     * Proof transition:
     * `ContinuationBinding + List<DetachedContinuationRecord> + cancellation -> ContinuationIssueResult`.
     *
     * Establishes immutable server ownership under one absolute TTL after total token, record, byte,
     * cancellation, and collision admission. [ContinuationIssueFailure] is the closed expected
     * failure. Raw record lists and the token issuer may be used only inside this store boundary.
     */
    fun issue(
        binding: ContinuationBinding,
        records: List<DetachedContinuationRecord>,
        cancellation: ContinuationCancellationProbe = ContinuationCancellationProbe.Never,
    ): ContinuationIssueResult = synchronized(lock) {
        if (lifecycle == ContinuationStoreLifecycle.CLOSED) {
            return@synchronized issueRejected(ContinuationIssueFailure.STORE_CLOSED)
        }
        val now = clock.nowNanos()
        purgeExpired(now)
        if (cancellation.status() == ContinuationCancellationStatus.CANCELLED) {
            return@synchronized issueRejected(ContinuationIssueFailure.CANCELLED)
        }
        val prepared = when (val preparation = prepareState(records)) {
            is ContinuationStatePreparation.Prepared -> preparation
            is ContinuationStatePreparation.Rejected ->
                return@synchronized issueRejected(preparation.failure)
        }
        if (entries.size >= limits.tokens.value) {
            return@synchronized issueRejected(ContinuationIssueFailure.TOKEN_LIMIT_REACHED)
        }
        if (prepared.records.size > limits.cachedRecords.value - cachedRecordCount()) {
            return@synchronized issueRejected(ContinuationIssueFailure.RECORD_LIMIT_REACHED)
        }
        if (prepared.encodedBytes.value > limits.cachedBytes.value - cachedByteCount()) {
            return@synchronized issueRejected(ContinuationIssueFailure.BYTE_LIMIT_REACHED)
        }
        val token = when (val publication = publishToken()) {
            is ContinuationTokenPublication.Published -> publication.token
            is ContinuationTokenPublication.Rejected ->
                return@synchronized issueRejected(publication.failure.issueFailure())
        }
        if (cancellation.status() == ContinuationCancellationStatus.CANCELLED) {
            return@synchronized issueRejected(ContinuationIssueFailure.CANCELLED)
        }
        entries[token] = ContinuationEntry(
            state = OwnedContinuationState(
                binding,
                prepared.records,
                prepared.encodedBytes,
                ContinuationIssuedAtNanos(now),
            ),
            position = ContinuationResumePosition(0L),
        )
        ContinuationIssueResult.Issued(token)
    }

    /**
     * Proof transition:
     * `ContinuationToken + ContinuationBinding + ContinuationPageBudget + cancellation -> ContinuationResumeResult`.
     *
     * Establishes exact binding/TTL admission and one deterministic bounded page. The input token
     * is single-use; completion releases state and a nonterminal page atomically rebinds the same
     * immutable state to a fresh token and exact next position. Every expected failure is closed by
     * [ContinuationAccessFailure], and every mismatch/cancellation/no-progress failure releases the
     * claimed state.
     */
    fun resume(
        token: ContinuationToken,
        binding: ContinuationBinding,
        budget: ContinuationPageBudget,
        cancellation: ContinuationCancellationProbe = ContinuationCancellationProbe.Never,
    ): ContinuationResumeResult = synchronized(lock) {
        if (lifecycle == ContinuationStoreLifecycle.CLOSED) {
            return@synchronized resumeRejected(ContinuationAccessFailure.STORE_CLOSED)
        }
        val entry = entries[token]
                    ?: return@synchronized resumeRejected(ContinuationAccessFailure.UNKNOWN_TOKEN)
        when (expiryState(entry, clock.nowNanos())) {
            ContinuationExpiryState.LIVE -> Unit
            ContinuationExpiryState.EXPIRED -> {
                entries.remove(token)
                return@synchronized resumeRejected(ContinuationAccessFailure.EXPIRED)
            }
        }
        when (val admission = admitBinding(entry.state.binding, binding)) {
            ContinuationBindingAdmission.Admitted -> Unit
            is ContinuationBindingAdmission.Rejected -> {
                entries.remove(token)
                return@synchronized resumeRejected(admission.failure)
            }
        }
        if (cancellation.status() == ContinuationCancellationStatus.CANCELLED) {
            entries.remove(token)
            return@synchronized resumeRejected(ContinuationAccessFailure.CANCELLED)
        }
        val selection = when (
            val selected = selectPage(entry, budget, cancellation)
        ) {
            is ContinuationPageSelection.Selected -> selected
            is ContinuationPageSelection.Rejected -> {
                entries.remove(token)
                return@synchronized resumeRejected(selected.failure)
            }
        }
        val segment = ContinuationPageSegment(
            position = entry.position,
            records = selection.records,
            encodedBytes = selection.encodedBytes,
        )
        if (selection.nextPosition.value == entry.state.records.size.toLong()) {
            entries.remove(token)
            return@synchronized ContinuationResumeResult.Resumed(
                ContinuationPage.Complete(segment),
            )
        }
        val nextToken = when (val publication = publishToken()) {
            is ContinuationTokenPublication.Published -> publication.token
            is ContinuationTokenPublication.Rejected -> {
                entries.remove(token)
                return@synchronized resumeRejected(publication.failure.accessFailure())
            }
        }
        if (cancellation.status() == ContinuationCancellationStatus.CANCELLED) {
            entries.remove(token)
            return@synchronized resumeRejected(ContinuationAccessFailure.CANCELLED)
        }
        entries.remove(token)
        entries[nextToken] = ContinuationEntry(entry.state, selection.nextPosition)
        ContinuationResumeResult.Resumed(ContinuationPage.More(segment, nextToken))
    }

    /**
     * Proof transition: `ContinuationToken -> ContinuationInvalidationResult`.
     *
     * Establishes atomic resource release for one known unexpired token. Unknown, expired, and
     * closed-store states are distinct [ContinuationAccessFailure] values.
     */
    fun invalidate(token: ContinuationToken): ContinuationInvalidationResult = synchronized(lock) {
        if (lifecycle == ContinuationStoreLifecycle.CLOSED) {
            return@synchronized ContinuationInvalidationResult.Rejected(
                ContinuationAccessFailure.STORE_CLOSED,
            )
        }
        val entry = entries.remove(token)
                    ?: return@synchronized ContinuationInvalidationResult.Rejected(
                        ContinuationAccessFailure.UNKNOWN_TOKEN,
                    )
        when (expiryState(entry, clock.nowNanos())) {
            ContinuationExpiryState.EXPIRED ->
                ContinuationInvalidationResult.Rejected(ContinuationAccessFailure.EXPIRED)
            ContinuationExpiryState.LIVE -> ContinuationInvalidationResult.Invalidated
        }
    }

    /** Releases every detached entry and permanently closes this store. */
    override fun close() = synchronized(lock) {
        lifecycle = ContinuationStoreLifecycle.CLOSED
        entries.clear()
    }

    /**
     * Proof transition:
     * `List<DetachedContinuationRecord> -> ContinuationStatePreparation`.
     *
     * Establishes a non-empty immutable record list and exact overflow-safe UTF-8 byte total.
     */
    private fun prepareState(
        input: List<DetachedContinuationRecord>,
    ): ContinuationStatePreparation {
        if (input.isEmpty()) {
            return ContinuationStatePreparation.Rejected(ContinuationIssueFailure.EMPTY_STATE)
        }
        val records = input.toList()
        var bytes = 0L
        records.forEach { record ->
            if (record.encodedBytes.value > limits.cachedBytes.value - bytes) {
                return ContinuationStatePreparation.Rejected(
                    ContinuationIssueFailure.BYTE_LIMIT_REACHED,
                )
            }
            bytes += record.encodedBytes.value
        }
        return ContinuationStatePreparation.Prepared(records, ContinuationByteCount(bytes))
    }

    /**
     * Proof transition: `stored ContinuationBinding + presented ContinuationBinding -> admission`.
     *
     * Establishes exact field-by-field binding identity or one finite mismatch reason.
     */
    private fun admitBinding(
        stored: ContinuationBinding,
        presented: ContinuationBinding,
    ): ContinuationBindingAdmission = when {
        stored.lease.workspaceRoot != presented.lease.workspaceRoot ->
            bindingRejected(ContinuationAccessFailure.WRONG_WORKSPACE_ROOT)
        stored.lease.generation != presented.lease.generation ->
            bindingRejected(ContinuationAccessFailure.GENERATION_CHANGED)
        stored.normalizedRequest != presented.normalizedRequest ->
            bindingRejected(ContinuationAccessFailure.NORMALIZED_REQUEST_CHANGED)
        stored.scope != presented.scope -> bindingRejected(ContinuationAccessFailure.SCOPE_CHANGED)
        stored.order != presented.order -> bindingRejected(ContinuationAccessFailure.ORDER_CHANGED)
        stored.owner != presented.owner ->
            bindingRejected(ContinuationAccessFailure.RESOURCE_OWNER_CHANGED)
        else -> ContinuationBindingAdmission.Admitted
    }

    /**
     * Proof transition: bounded token issuer effect to collision-admitted publication data.
     *
     * Establishes a fresh token or a closed collision/issuer failure without publishing state.
     */
    private fun publishToken(): ContinuationTokenPublication {
        val token = try {
            tokenIssuer.issue()
        } catch (_: RuntimeException) {
            return ContinuationTokenPublication.Rejected(
                ContinuationTokenPublicationFailure.ISSUER_FAILURE,
            )
        }
        return if (token in entries) {
            ContinuationTokenPublication.Rejected(ContinuationTokenPublicationFailure.COLLISION)
        } else {
            ContinuationTokenPublication.Published(token)
        }
    }

    /**
     * Proof transition:
     * `ContinuationEntry + ContinuationPageBudget + cancellation -> ContinuationPageSelection`.
     *
     * Establishes an order-preserving non-empty page within record/byte bounds, or a closed
     * cancellation/no-progress failure. No record is retained beyond the detached state.
     */
    private fun selectPage(
        entry: ContinuationEntry,
        budget: ContinuationPageBudget,
        cancellation: ContinuationCancellationProbe,
    ): ContinuationPageSelection {
        val selected = mutableListOf<DetachedContinuationRecord>()
        var bytes = 0L
        var index = entry.position.value.toInt()
        while (index < entry.state.records.size && selected.size < budget.records.value) {
            if (cancellation.status() == ContinuationCancellationStatus.CANCELLED) {
                return ContinuationPageSelection.Rejected(ContinuationAccessFailure.CANCELLED)
            }
            val record = entry.state.records[index]
            if (record.encodedBytes.value > budget.bytes.value - bytes) {
                if (selected.isEmpty()) {
                    return ContinuationPageSelection.Rejected(
                        ContinuationAccessFailure.PAGE_BYTE_LIMIT_TOO_SMALL,
                    )
                }
                break
            }
            selected += record
            bytes += record.encodedBytes.value
            index += 1
        }
        return ContinuationPageSelection.Selected(
            records = selected.toList(),
            encodedBytes = ContinuationByteCount(bytes),
            nextPosition = ContinuationResumePosition(index.toLong()),
        )
    }

    private fun purgeExpired(nowNanos: Long) {
        entries.entries.removeIf { (_, entry) ->
            expiryState(entry, nowNanos) == ContinuationExpiryState.EXPIRED
        }
    }

    /**
     * Proof transition: `ContinuationEntry + raw clock observation -> ContinuationExpiryState`.
     *
     * Establishes live or expired state against the original non-renewing issue time and typed TTL.
     */
    private fun expiryState(
        entry: ContinuationEntry,
        nowNanos: Long,
    ): ContinuationExpiryState =
        if (
            (nowNanos - entry.state.issuedAtNanos.value).coerceAtLeast(0L) >=
            limits.ttl.nanoseconds
        ) {
            ContinuationExpiryState.EXPIRED
        } else {
            ContinuationExpiryState.LIVE
        }

    private fun cachedRecordCount(): Int = entries.values.sumOf { it.state.records.size }

    private fun cachedByteCount(): Long = entries.values.sumOf { it.state.encodedBytes.value }
}
