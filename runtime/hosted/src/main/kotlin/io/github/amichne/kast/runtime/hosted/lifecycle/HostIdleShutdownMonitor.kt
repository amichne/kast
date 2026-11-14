package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.distribution.contract.HostExitReason
import io.github.amichne.kast.distribution.contract.configuration.HostIdleShutdown
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class HostIdleOutcome {
    /** Settled and stop acknowledged; the normal, vetoable application exit was requested. */
    EXIT_REQUESTED,
    /** Native work started between the idle decision and settlement; admission reopened. */
    BUSY,
    /** Durable mutation evidence is unresolved; the Host keeps running. */
    MUTATION_RECOVERY_REQUIRED,
    /** Settlement or stop refused for another finite reason; admission reopened. */
    REJECTED,
    /** The requested exit did not happen; admission reopened. */
    VETOED,
}

@Serializable
internal data class HostIdleObservation(
    val outcome: HostIdleOutcome,
    val failure: IdeLifecycleFailure? = null,
    val limitMinutes: Int,
)

/**
 * Stops an idle managed Host through the same settlement fence and stop acknowledgment that explicit `kast stop` uses.
 * Each [tick] is one bounded decision; the caller owns scheduling.
 */
internal class HostIdleShutdownMonitor(
    private val policy: HostIdleShutdown.After,
    private val admission: HostedApplicationAdmission,
    private val settlement: HostedApplicationSettlement,
    private val host: String,
    private val nativeWorkObserved: () -> Boolean,
    private val evidence: HostExitEvidence,
    private val requestExit: () -> Unit,
    private val observe: (HostIdleObservation) -> Unit,
) {
    private val transaction = "idle-v1:$host"
    private val limitNanos = policy.millis * NANOS_PER_MILLI
    private var exitRequested = false

    fun tick() {
        if (exitRequested) {
            // A previous tick requested exit and this process is still running, so IntelliJ vetoed or ignored it.
            val resumed = settlement.resume(IdeLifecycleCommand.ResumeHost(host, transaction))
            if (resumed is IdeLifecycleResult.HostResumed) {
                exitRequested = false
                evidence.retract()
                report(HostIdleOutcome.VETOED)
            }
            return
        }
        if (nativeWorkObserved()) {
            admission.observeNativeWork()
            return
        }
        when (admission.fenceIfIdle(transaction, limitNanos)) {
            HostedApplicationAdmission.IdleFence.NotDue,
            HostedApplicationAdmission.IdleFence.Active,
            HostedApplicationAdmission.IdleFence.AlreadyFenced -> return
            HostedApplicationAdmission.IdleFence.Fenced -> settleAndStop()
        }
    }

    private fun settleAndStop() {
        when (val settled = settlement.settle(IdeLifecycleCommand.SettleHost(host, transaction))) {
            is IdeLifecycleResult.HostSettled -> stop()
            is IdeLifecycleResult.HostSettling -> reopen(HostIdleOutcome.BUSY)
            is IdeLifecycleResult.Blocked ->
                if (settled.reason == IdeLifecycleFailure.MUTATION_RECOVERY_REQUIRED)
                    reopen(HostIdleOutcome.MUTATION_RECOVERY_REQUIRED, settled.reason)
                else reopen(HostIdleOutcome.REJECTED, settled.reason)
            else -> reopen(HostIdleOutcome.REJECTED)
        }
    }

    private fun stop() {
        when (val stopped = settlement.stop(IdeLifecycleCommand.StopHost(host, transaction))) {
            is IdeLifecycleResult.HostStopRequested -> {
                exitRequested = true
                report(HostIdleOutcome.EXIT_REQUESTED)
                evidence.record(HostExitReason.IDLE)
                requestExit()
            }
            else -> {
                settlement.resume(IdeLifecycleCommand.ResumeHost(host, transaction))
                report(HostIdleOutcome.REJECTED, (stopped as? IdeLifecycleResult.Blocked)?.reason)
            }
        }
    }

    private fun reopen(outcome: HostIdleOutcome, failure: IdeLifecycleFailure? = null) {
        admission.resume(transaction)
        report(outcome, failure)
    }

    private fun report(outcome: HostIdleOutcome, failure: IdeLifecycleFailure? = null) =
        observe(HostIdleObservation(outcome, failure, policy.minutes))

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Starts the idle monitor only for an admitted finite policy; a disabled or desktop Host launches nothing. */
internal fun launchHostIdleShutdown(
    scope: CoroutineScope,
    policy: HostIdleShutdown?,
    admission: HostedApplicationAdmission,
    settlement: HostedApplicationSettlement,
    identity: IdeHostRuntimeIdentity,
    evidence: HostExitEvidence,
    hasActivePreparation: () -> Boolean,
): Job? {
    if (policy !is HostIdleShutdown.After) return null
    val logger = Logger.getInstance(HostIdleShutdownMonitor::class.java)
    val monitor =
        HostIdleShutdownMonitor(
            policy,
            admission,
            settlement,
            identity.hostLifetime,
            { observeHostNativeWork(hasActivePreparation) },
            evidence,
            ::requestSettledHostExit,
        ) { observation ->
            logger.info("kast_host_idle " + Json.encodeToString(HostIdleObservation.serializer(), observation))
        }
    return scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(IDLE_POLL_MILLIS)
            monitor.tick()
        }
    }
}

private const val IDLE_POLL_MILLIS = 30_000L
