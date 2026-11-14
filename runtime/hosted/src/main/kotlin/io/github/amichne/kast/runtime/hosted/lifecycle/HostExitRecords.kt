package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.distribution.contract.HostExitIndexState
import io.github.amichne.kast.distribution.contract.HostExitReason
import io.github.amichne.kast.distribution.contract.HostExitRecord
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Records that an acknowledged stop requested exit, and retracts that record when admission resumes. */
internal sealed interface HostExitEvidence {
    fun record(reason: HostExitReason)

    fun retract()

    /** Desktop Hosts have no private state; nothing records their exits. */
    data object Unrecorded : HostExitEvidence {
        override fun record(reason: HostExitReason) = Unit

        override fun retract() = Unit
    }
}

@Serializable
internal enum class HostExitRecordAction {
    RECORD,
    RETRACT,
}

@Serializable
internal enum class HostExitRecordOutcome {
    WRITTEN,
    REMOVED,
    ABSENT,
    FAILED,
}

@Serializable
internal data class HostExitRecordObservation(
    val action: HostExitRecordAction,
    val outcome: HostExitRecordOutcome,
    val reason: HostExitReason? = null,
)

/**
 * The only Host writer of `host-exit.json`. A failed write leaves no record, so the exit proceeds and the private state
 * is simply never offered as evidence of a clean stop.
 */
internal class HostExitRecords(
    private val state: Path,
    private val hostLifetime: String,
    private val indexing: () -> Boolean,
    private val observe: (HostExitRecordObservation) -> Unit,
) : HostExitEvidence {
    private val target = HostExitRecord.path(state)

    override fun record(reason: HostExitReason) {
        val index = if (indexing()) HostExitIndexState.DUMB else HostExitIndexState.SMART
        val encoded = JSON.encodeToString(HostExitRecord.serializer(), HostExitRecord(reason, index, hostLifetime))
        var temporary: Path? = null
        val outcome =
            try {
                temporary = Files.createTempFile(state, ".host-exit-", ".tmp")
                Files.writeString(temporary, encoded)
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                HostExitRecordOutcome.WRITTEN
            } catch (_: java.io.IOException) {
                temporary?.let(::discard)
                HostExitRecordOutcome.FAILED
            }
        observe(HostExitRecordObservation(HostExitRecordAction.RECORD, outcome, reason))
    }

    override fun retract() {
        val outcome =
            try {
                if (Files.deleteIfExists(target)) HostExitRecordOutcome.REMOVED else HostExitRecordOutcome.ABSENT
            } catch (_: java.io.IOException) {
                HostExitRecordOutcome.FAILED
            }
        observe(HostExitRecordObservation(HostExitRecordAction.RETRACT, outcome))
    }

    private fun discard(temporary: Path) {
        try {
            Files.deleteIfExists(temporary)
        } catch (_: java.io.IOException) {
            // The record outcome is already FAILED; a leftover temporary file is never read as a record.
        }
    }

    companion object {
        private val JSON = Json { encodeDefaults = true }

        fun forManagedHost(host: ManagedHeadlessHost?, hostLifetime: String): HostExitEvidence =
            if (host == null) HostExitEvidence.Unrecorded
            else {
                val logger = Logger.getInstance(HostExitRecords::class.java)
                HostExitRecords(host.environment.state, hostLifetime, ::observeHostIndexing) { observation ->
                    logger.info(
                        "kast_host_exit_record " +
                            JSON.encodeToString(HostExitRecordObservation.serializer(), observation)
                    )
                }
            }
    }
}

/** Joins both stop paths to the managed Host's exit evidence and, for a finite policy, starts the idle monitor. */
internal class HostExitControl private constructor(private val evidence: HostExitEvidence, val idle: Job?) {
    /** An explicit stop records its exit only after the acknowledgment reaches Control. */
    fun stopReply(result: IdeLifecycleResult.HostStopRequested): LifecycleReply =
        LifecycleReply.afterAcknowledgment(result) {
            evidence.record(HostExitReason.SETTLED)
            requestSettledHostExit()
        }

    /** Resumed admission means the Host will keep running, so an earlier exit record no longer holds. */
    fun resumed(result: IdeLifecycleResult): IdeLifecycleResult = result.also {
        if (it is IdeLifecycleResult.HostResumed) evidence.retract()
    }

    companion object {
        fun start(
            scope: CoroutineScope,
            admission: HostedApplicationAdmission,
            settlement: HostedApplicationSettlement,
            identity: IdeHostRuntimeIdentity,
            hasActivePreparation: () -> Boolean,
        ): HostExitControl {
            val host = ManagedHeadlessHost.current()
            val evidence = HostExitRecords.forManagedHost(host, identity.hostLifetime)
            val idle =
                launchHostIdleShutdown(
                    scope,
                    host?.idleShutdown,
                    admission,
                    settlement,
                    identity,
                    evidence,
                    hasActivePreparation,
                )
            return HostExitControl(evidence, idle)
        }
    }
}
