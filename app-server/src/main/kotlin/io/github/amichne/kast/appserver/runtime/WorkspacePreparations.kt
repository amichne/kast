package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One service-owned lifecycle sequence per canonical root; observing never repeats an effect. */
internal class WorkspacePreparations(
    scope: CoroutineScope,
    private val exchange: suspend (WorkspaceLifecycleRequest) -> IdeLifecycleResult,
    private val observer: WorkspacePreparationObserver = WorkspacePreparationObserver.Disabled,
    private val capacity: Int = 256,
    private val budget: Duration = 5.minutes,
    private val interval: Duration = 100.milliseconds,
    private val newId: () -> WorkspacePreparationId = WorkspacePreparationId::fresh,
) {
    private val worker = Job(scope.coroutineContext[Job])
    private val work = CoroutineScope(scope.coroutineContext + worker)
    private val entries = linkedMapOf<CanonicalRoot, WorkspacePreparation>()
    private val records = linkedMapOf<WorkspacePreparationId, WorkspacePreparation>()
    private var closed = false

    init {
        require(capacity > 0 && budget.isPositive() && interval.isPositive())
    }

    @Synchronized
    fun upgradeBlockers(): Set<UpgradeBlocker> =
        if (records.values.any { it.state.value is WorkspacePreparationOutcome.Pending })
            setOf(UpgradeBlocker.PREPARATION_ACTIVE)
        else emptySet()

    @Synchronized
    fun prepare(root: CanonicalRoot): Refinement<WorkspacePreparation, WorkspacePreparationFailure> {
        if (closed || !worker.isActive) return Refinement.Rejected(WorkspacePreparationFailure.CLOSED)
        entries[root]?.let {
            return Refinement.Refined(it)
        }
        if (records.size >= capacity) return Refinement.Rejected(WorkspacePreparationFailure.CAPACITY_EXCEEDED)
        val id = newId()
        if (id in records) return Refinement.Rejected(WorkspacePreparationFailure.IDENTITY_REJECTED)
        val entry = WorkspacePreparation(id, root)
        val job = work.launch(start = CoroutineStart.LAZY) { run(entry) }
        entries[root] = entry
        records[id] = entry
        observer.observe(entry.activity())
        job.start()
        return Refinement.Refined(entry)
    }

    @Synchronized
    fun observe(id: WorkspacePreparationId): Refinement<WorkspacePreparation, WorkspacePreparationFailure> =
        records[id]?.let { Refinement.Refined(it) }
            ?: Refinement.Rejected(WorkspacePreparationFailure.UNKNOWN_OPERATION)

    /** Retire only this ready incarnation; preserve its historical operation record. */
    @Synchronized
    fun invalidate(id: WorkspacePreparationId, workspace: PreparedWorkspace) {
        val entry = records[id] ?: return
        val outcome = entry.state.value
        if (outcome is WorkspacePreparationOutcome.Complete && outcome.workspace === workspace)
            entries.remove(workspace.root, entry)
    }

    suspend fun close() {
        synchronized(this) { closed = true }
        worker.cancelAndJoin()
        synchronized(this) {
            entries.values
                .filter { it.state.value is WorkspacePreparationOutcome.Pending }
                .forEach {
                    publish(it, WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.CLOSED))
                }
        }
    }

    private fun publish(entry: WorkspacePreparation, outcome: WorkspacePreparationOutcome) {
        if (entry.mutable.value == outcome) return
        entry.mutable.value = outcome
        observer.observe(entry.activity())
    }

    private suspend fun run(entry: WorkspacePreparation) {
        try {
            val result =
                withTimeoutOrNull(budget) { prepareNative(entry) }
                    ?: WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED)
            publish(entry, result)
        } finally {
            if (entry.mutable.value is WorkspacePreparationOutcome.Pending) {
                val reason =
                    synchronized(this) {
                        if (closed) WorkspacePreparationFailure.CLOSED else WorkspacePreparationFailure.INTERRUPTED
                    }
                publish(entry, WorkspacePreparationOutcome.Rejected(reason))
            }
        }
    }

    private suspend fun prepareNative(entry: WorkspacePreparation): WorkspacePreparationOutcome {
        val requestId = entry.id.value.toString()
        var response = exchange(WorkspaceLifecycleRequest.Open(entry.root.path.toString(), requestId))
        var progress: NativePreparationProgress? = null
        while (response is IdeLifecycleResult.Pending) {
            progress =
                when (val admitted = admitPreparationProgress(response, entry.id, progress)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return WorkspacePreparationOutcome.Rejected(admitted.failure)
                }
            publish(entry, WorkspacePreparationOutcome.Pending(progress.stage))
            delay(interval)
            response = exchange(WorkspaceLifecycleRequest.Status(progress.host.toString(), requestId))
        }
        return completion(entry, response, progress?.host)
    }

    private fun completion(
        entry: WorkspacePreparation,
        response: IdeLifecycleResult,
        host: UUID?,
    ): WorkspacePreparationOutcome {
        return when (response) {
            is IdeLifecycleResult.Opened ->
                when (val admitted = PreparedWorkspace.admit(entry.root, response.target)) {
                    is Refinement.Rejected -> WorkspacePreparationOutcome.Rejected(admitted.failure)
                    is Refinement.Refined ->
                        if (host == null || host == admitted.value.host)
                            WorkspacePreparationOutcome.Complete(admitted.value)
                        else WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED)
                }
            is IdeLifecycleResult.Blocked -> WorkspacePreparationOutcome.Blocked(response.reason)
            else -> WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED)
        }
    }
}
