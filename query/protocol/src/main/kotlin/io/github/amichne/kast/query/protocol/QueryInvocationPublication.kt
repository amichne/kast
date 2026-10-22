package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation

/** Identity capability for checkpoints allocated by one unpublished automatic invocation. */
internal class QueryInvocationPublication : QueryExecutionPublication {
    override fun prepare(store: QueryStateStore, claim: QueryExecutionClaim, page: QueryPublishedPage) =
        when (val committed = store.commitInvocationPublication(this, claim, page)) {
            QueryPublicationCommit.Committed -> QueryExecutionPublicationResult.COMMITTED
            is QueryPublicationCommit.Rejected -> QueryExecutionPublicationResult.Rejected(committed.failure)
        }
}

internal val QueryStateEntry.invocationOwner: QueryInvocationPublication?
    get() =
        when (this) {
            is QueryStateEntry.Checkpoint -> privateInvocation
            is QueryStateEntry.Published -> privateInvocation
            is QueryStateEntry.Output,
            is QueryStateEntry.Result -> null
        }

internal val QueryStateEntry.privateRunnerOwner: QueryInvocationPublication?
    get() = if (this is QueryStateEntry.Producer && request.runnerControlled()) invocationOwner else null

internal fun QueryStateEntry.ownedByRunner(invocation: QueryInvocationPublication?): Boolean =
    invocation != null && privateRunnerOwner === invocation

internal fun QueryStateEntry.publishInvocation(): QueryStateEntry =
    when (this) {
        is QueryStateEntry.Checkpoint -> copy(privateInvocation = null)
        is QueryStateEntry.Published -> copy(privateInvocation = null)
        is QueryStateEntry.Output,
        is QueryStateEntry.Result -> this
    }

/** The store holds its monitor across allocation proof, normal publication and private lifetime release. */
internal fun MutableMap<QueryStateKey, QueryStateEntry>.commitInvocation(
    invocation: QueryInvocationPublication,
    claim: QueryExecutionClaim,
    retention: QueryStateRetention,
    commit: () -> QueryPublicationCommit,
): QueryPublicationCommit {
    val parent = (claim.origin as? QueryExecutionOrigin.Producer)?.token?.key()
    val privateAttempt = claim.origin is QueryExecutionOrigin.Initial || this[parent]?.invocationOwner === invocation
    val allocated =
        if (privateAttempt) filterValues { it is QueryStateEntry.Checkpoint && it.owner === claim }.keys.toList()
        else emptyList()
    val committed = commit()
    if (committed == QueryPublicationCommit.Committed) {
        for (key in allocated) {
            val entry = this[key] as? QueryStateEntry.Checkpoint ?: continue
            this[key] = entry.copy(privateInvocation = invocation)
        }
        if (parent != null && this[parent] is QueryStateEntry.Published) retention.discardInvocation(parent, invocation)
    }
    return committed
}

/** The store has already proven the final publication claim, and holds its monitor until cleanup ends. */
internal fun MutableMap<QueryStateKey, QueryStateEntry>.finishInvocation(
    invocation: QueryInvocationPublication,
    retained: QueryExecutionContinuation.Pipeline?,
    owner: QueryExecutionClaim?,
    retention: QueryStateRetention,
) {
    val keys = filterValues { it.invocationOwner === invocation }.keys.toList()
    val retainedKey = retained?.key()
    if (retainedKey != null && owner != null) {
        val checkpoint = this[retainedKey] as? QueryStateEntry.Checkpoint
        if (
            checkpoint?.execution == QueryCheckpointExecution.Ready &&
                retention.invocationUnpinned(retainedKey, invocation)
        ) {
            this[retainedKey] = checkpoint.copy(owner = owner, privateInvocation = null)
        }
    }
    for (key in keys) retention.discardInvocation(key, invocation)
    // Any externally pinned child remains charged and public; cleanup cannot invalidate another owner.
    for (key in keys) this[key]?.let { this[key] = it.publishInvocation() }
}
