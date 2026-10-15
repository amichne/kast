package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object NativeResetProcessRetirement {
    private const val MAXIMUM_PROCESSES = 16384L

    fun captureObserved(
        process: ResetScopedProcess,
        observe: (ResetProcessObservation) -> Unit,
    ): ResetSharedSocketCapture {
        if (revalidate(process.snapshot, observe) != NativeResetAdmission.Admitted)
            return ResetSharedSocketCapture.Rejected
        val captured = ResetOwnedSharedSocket.capture(process, observe = observe)
        if (revalidate(process.snapshot, observe) != NativeResetAdmission.Admitted)
            return ResetSharedSocketCapture.Rejected
        return captured
    }

    fun retireObserved(
        process: ResetScopedProcess,
        allowance: Duration,
        observe: (ResetProcessObservation) -> Unit,
    ): LifecycleProcessObservation {
        val captured =
            when (val result = captureObserved(process, observe)) {
                is ResetSharedSocketCapture.Captured -> result
                ResetSharedSocketCapture.Rejected -> return LifecycleProcessObservation.Unavailable
            }
        return retireCapturedObserved(captured, allowance, observe)
    }

    fun retireCapturedObserved(
        captured: ResetSharedSocketCapture.Captured,
        allowance: Duration,
        observe: (ResetProcessObservation) -> Unit,
    ): LifecycleProcessObservation {
        val process = captured.process
        val prior = process.snapshot
        when (val admission = revalidate(prior, observe)) {
            NativeResetAdmission.Admitted -> Unit
            is NativeResetAdmission.Finished -> return captured.retireAfterExit(admission.observation, observe)
        }
        val handle =
            ProcessHandle.of(prior.pid).orElse(null)
                ?: return captured.retireAfterExit(LifecycleProcessObservation.Absent, observe)
        return retireCurrent(captured, handle, allowance, observe)
    }

    private fun retireCurrent(
        sockets: ResetSharedSocketCapture.Captured,
        handle: ProcessHandle,
        allowance: Duration,
        observe: (ResetProcessObservation) -> Unit,
    ): LifecycleProcessObservation =
        try {
            val process = sockets.process
            val dedicated =
                when (val children = dedicatedChildren(handle, process.root)) {
                    is ResetProcessTable.Observed -> children.processes
                    ResetProcessTable.Unavailable -> {
                        observe(
                            ResetProcessObservation(
                                process.snapshot.pid,
                                ResetProcessStage.CHILDREN,
                                ResetProcessResult.OBSERVATION_REJECTED,
                            )
                        )
                        return LifecycleProcessObservation.Unavailable
                    }
                }
            val deadline = System.nanoTime() + allowance.toNanos()
            for (snapshot in dedicated + process.snapshot) {
                if (terminateSnapshot(snapshot, deadline, observe) == NativeTermination.REJECTED)
                    return LifecycleProcessObservation.Unavailable
            }
            val retired = verifyRetirement(process.snapshot.pid, observe)
            sockets.retireAfterExit(retired, observe)
        } catch (_: TimeoutException) {
            waitRejected(sockets.process.snapshot.pid, ResetProcessResult.DEADLINE_EXCEEDED, observe)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            waitRejected(sockets.process.snapshot.pid, ResetProcessResult.INTERRUPTED, observe)
        } catch (_: Exception) {
            waitRejected(sockets.process.snapshot.pid, ResetProcessResult.WAIT_REJECTED, observe)
        }

    private fun waitRejected(
        pid: Long,
        outcome: ResetProcessResult,
        observe: (ResetProcessObservation) -> Unit,
    ): LifecycleProcessObservation {
        observe(ResetProcessObservation(pid, ResetProcessStage.WAIT, outcome))
        return LifecycleProcessObservation.Unavailable
    }

    private fun verifyRetirement(pid: Long, observe: (ResetProcessObservation) -> Unit): LifecycleProcessObservation {
        val result = NativeLifecycleProcesses.observe(pid)
        observe(
            ResetProcessObservation(
                pid,
                ResetProcessStage.VERIFY,
                if (result == LifecycleProcessObservation.Absent) ResetProcessResult.RETIRED
                else ResetProcessResult.OBSERVATION_REJECTED,
            )
        )
        return result
    }

    private fun revalidate(
        prior: LifecycleProcessSnapshot,
        observe: (ResetProcessObservation) -> Unit,
    ): NativeResetAdmission {
        val current = NativeLifecycleProcesses.observe(prior.pid)
        val admission = NativeResetAdmission.admit(prior, current)
        val outcome =
            when (admission) {
                NativeResetAdmission.Admitted -> ResetProcessResult.IDENTITY_VERIFIED
                is NativeResetAdmission.Finished -> admission.outcome
            }
        observe(ResetProcessObservation(prior.pid, ResetProcessStage.REVALIDATE, outcome))
        return admission
    }

    private fun dedicatedChildren(handle: ProcessHandle, root: Path): ResetProcessTable {
        val children = handle.descendants().use { stream -> stream.limit(MAXIMUM_PROCESSES + 1).toList() }
        if (children.size > MAXIMUM_PROCESSES) return ResetProcessTable.Unavailable
        val snapshots = mutableListOf<LifecycleProcessSnapshot>()
        for (child in children) {
            when (val observed = NativeLifecycleProcesses.observe(child.pid())) {
                LifecycleProcessObservation.Absent -> Unit
                LifecycleProcessObservation.Unavailable -> return ResetProcessTable.Unavailable
                is LifecycleProcessObservation.Present -> {
                    when (val selection = ResetScopedProcess.select(observed.snapshot, root)) {
                        ResetProcessSelection.Unrelated -> Unit
                        ResetProcessSelection.Rejected -> return ResetProcessTable.Unavailable
                        is ResetProcessSelection.Selected -> snapshots += selection.process.snapshot
                    }
                }
            }
        }
        return ResetProcessTable.Observed(snapshots)
    }

    private fun terminateSnapshot(
        snapshot: LifecycleProcessSnapshot,
        deadline: Long,
        observe: (ResetProcessObservation) -> Unit,
    ): NativeTermination {
        val handle = ProcessHandle.of(snapshot.pid).orElse(null) ?: return NativeTermination.RETIRED
        val admission = NativeResetAdmission.admit(snapshot, NativeLifecycleProcesses.observe(handle))
        when (admission) {
            NativeResetAdmission.Admitted -> Unit
            is NativeResetAdmission.Finished -> {
                observe(ResetProcessObservation(snapshot.pid, ResetProcessStage.SIGNAL, admission.outcome))
                return when (admission.observation) {
                    LifecycleProcessObservation.Absent,
                    is LifecycleProcessObservation.Present -> NativeTermination.RETIRED
                    LifecycleProcessObservation.Unavailable -> NativeTermination.REJECTED
                }
            }
        }
        if (!handle.destroyForcibly()) {
            observe(ResetProcessObservation(snapshot.pid, ResetProcessStage.SIGNAL, ResetProcessResult.SIGNAL_REJECTED))
            return NativeTermination.REJECTED
        }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) {
            observe(ResetProcessObservation(snapshot.pid, ResetProcessStage.WAIT, ResetProcessResult.DEADLINE_EXCEEDED))
            return NativeTermination.REJECTED
        }
        handle.onExit().get(remaining, TimeUnit.NANOSECONDS)
        return if (!handle.isAlive) NativeTermination.RETIRED
        else {
            observe(ResetProcessObservation(snapshot.pid, ResetProcessStage.WAIT, ResetProcessResult.WAIT_REJECTED))
            NativeTermination.REJECTED
        }
    }
}

private sealed interface NativeResetAdmission {
    data object Admitted : NativeResetAdmission

    data class Finished(val observation: LifecycleProcessObservation, val outcome: ResetProcessResult) :
        NativeResetAdmission

    companion object {
        fun admit(prior: LifecycleProcessSnapshot, current: LifecycleProcessObservation): NativeResetAdmission =
            when (current) {
                LifecycleProcessObservation.Absent -> Finished(current, ResetProcessResult.RETIRED)
                LifecycleProcessObservation.Unavailable -> Finished(current, ResetProcessResult.OBSERVATION_REJECTED)
                is LifecycleProcessObservation.Present ->
                    when {
                        current.snapshot.pid != prior.pid ->
                            Finished(LifecycleProcessObservation.Unavailable, ResetProcessResult.IDENTITY_REJECTED)
                        current.snapshot.startEpochMillis != prior.startEpochMillis ->
                            Finished(current, ResetProcessResult.INCARNATION_CHANGED)
                        current.snapshot != prior ->
                            Finished(LifecycleProcessObservation.Unavailable, ResetProcessResult.IDENTITY_REJECTED)
                        else -> Admitted
                    }
            }
    }
}

private enum class NativeTermination {
    RETIRED,
    REJECTED,
}
