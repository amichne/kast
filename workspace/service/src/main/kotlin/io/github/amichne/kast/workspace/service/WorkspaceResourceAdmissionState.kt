package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceExpensiveWork
import io.github.amichne.kast.workspace.contract.WorkspaceResourceAdmissionAction
import io.github.amichne.kast.workspace.contract.WorkspaceResourceBlocker
import io.github.amichne.kast.workspace.contract.WorkspaceResourceDurationNanos
import io.github.amichne.kast.workspace.contract.WorkspaceResourceInitiationResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class WorkspaceInitiationKey(
    val root: CanonicalWorkspaceRoot,
    val kind: WorkspaceExpensiveWork,
)

internal sealed interface WorkspaceExactInitiation {
    data object Absent : WorkspaceExactInitiation

    data class Present(val entry: ActiveWorkspaceInitiation) : WorkspaceExactInitiation
}

internal class ActiveWorkspaceInitiation(
    val key: WorkspaceInitiationKey,
) : WorkspaceAdmissionWaitSignal {
    private val finished = CountDownLatch(1)
    private val state = AtomicReference(WorkspaceInitiationCompletion.Running)

    fun complete(completion: WorkspaceInitiationCompletion) {
        if (state.compareAndSet(WorkspaceInitiationCompletion.Running, completion)) {
            finished.countDown()
        }
    }

    @Throws(InterruptedException::class)
    override fun await(timeoutNanos: Long): Boolean =
        finished.await(timeoutNanos, TimeUnit.NANOSECONDS)

    fun completion(): WorkspaceInitiationCompletion = state.get()
}

internal fun interface WorkspaceAdmissionWaitSignal {
    @Throws(InterruptedException::class)
    fun await(timeoutNanos: Long): Boolean
}

internal class WorkspaceKindCapacityRelease : WorkspaceAdmissionWaitSignal {
    private val released = CountDownLatch(1)

    fun complete(): Unit = released.countDown()

    @Throws(InterruptedException::class)
    override fun await(timeoutNanos: Long): Boolean =
        released.await(timeoutNanos, TimeUnit.NANOSECONDS)
}

internal enum class WorkspaceInitiationCompletion {
    Running,
    Succeeded,
    Failed,
}

internal sealed interface WorkspaceInitiationClaim {
    data class Start(val entry: ActiveWorkspaceInitiation) : WorkspaceInitiationClaim

    data class Reuse(val entry: ActiveWorkspaceInitiation) : WorkspaceInitiationClaim

    data class Queue(val release: WorkspaceKindCapacityRelease) : WorkspaceInitiationClaim

    data class Rejected(
        val blocker: WorkspaceResourceBlocker,
        val action: WorkspaceResourceAdmissionAction,
    ) : WorkspaceInitiationClaim
}

internal sealed interface WorkspaceEntryWait {
    data class Completed(
        val queueDuration: WorkspaceResourceDurationNanos,
    ) : WorkspaceEntryWait

    data class Rejected(
        val result: WorkspaceResourceInitiationResult.Rejected,
    ) : WorkspaceEntryWait
}

internal sealed interface WorkspaceCapacityWait {
    data class Retry(
        val queueDuration: WorkspaceResourceDurationNanos,
    ) : WorkspaceCapacityWait

    data class Complete(
        val result: WorkspaceResourceInitiationResult.Rejected,
    ) : WorkspaceCapacityWait
}
