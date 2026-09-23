package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.DaemonUpgradeDocument
import io.github.amichne.kast.appserver.runtime.UpgradeBlocker
import io.github.amichne.kast.appserver.runtime.UpgradeCandidate
import io.github.amichne.kast.kernel.NonEmptyFailures
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

sealed interface InstalledUpgradeRejection {
    data object CandidateRejected : InstalledUpgradeRejection

    data class Command(val failure: PersistentBrokerServiceFailure) : InstalledUpgradeRejection

    data object ServiceMarkersRejected : InstalledUpgradeRejection

    data object RetainedServiceEvidence : InstalledUpgradeRejection

    data class Lifecycle(val failure: InstalledUpgradeLifecycleFailure) : InstalledUpgradeRejection

    data class Daemon(val reason: DaemonManagementRejection) : InstalledUpgradeRejection

    data object PreviousUpdateCancelled : InstalledUpgradeRejection
}

enum class InstalledUpgradeLifecycleFailure {
    REJECTED,
    INTERRUPTED,
    TIMED_OUT,
}

sealed interface InstalledUpgradePreparation {
    data object NoDaemon : InstalledUpgradePreparation

    data class Pending(val blockers: NonEmptyFailures<UpgradeBlocker>) : InstalledUpgradePreparation

    data class Sealed(val permit: InstalledUpgradePermit) : InstalledUpgradePreparation

    data class Committed(val permit: InstalledCommittedUpgradePermit) : InstalledUpgradePreparation

    data class Rejected(val reason: InstalledUpgradeRejection) : InstalledUpgradePreparation
}

sealed interface InstalledUpgradeSettlement {
    data object Completed : InstalledUpgradeSettlement

    data class Rejected(val reason: DaemonManagementRejection) : InstalledUpgradeSettlement
}

/** The exact daemon target and request remain bound until commit or cancellation. */
class InstalledUpgradePermit
internal constructor(
    private val client: InstalledDaemonManagementClient,
    private val command: BrokerServiceLaunchCommand,
    private val target: DaemonManagementTarget,
    private val sealed: DaemonUpgradeDocument.Sealed,
) {
    fun commit(): InstalledUpgradeSettlement = runBlocking {
        when (val result = client.commitUpdate(command, target, sealed)) {
            is Refinement.Refined -> InstalledUpgradeSettlement.Completed
            is Refinement.Rejected -> InstalledUpgradeSettlement.Rejected(result.failure)
        }
    }

    fun cancel(): InstalledUpgradeSettlement = runBlocking {
        when (val result = client.cancelUpdate(command, target, sealed)) {
            is Refinement.Refined -> InstalledUpgradeSettlement.Completed
            is Refinement.Rejected -> InstalledUpgradeSettlement.Rejected(result.failure)
        }
    }
}

/** A prior daemon's already-committed exact request permits retirement to resume. */
class InstalledCommittedUpgradePermit
private constructor(
    private val target: DaemonManagementTarget,
    private val document: DaemonUpgradeDocument.Committed,
) {
    internal companion object {
        fun admit(
            qualified: QualifiedDaemonUpdate
        ): Refinement<InstalledCommittedUpgradePermit, DaemonManagementFailure> =
            when (val document = qualified.document) {
                is DaemonUpgradeDocument.Committed ->
                    Refinement.Refined(InstalledCommittedUpgradePermit(qualified.target, document))
                else -> Refinement.Rejected(DaemonManagementFailure.RESPONSE_REJECTED)
            }
    }
}

/** A read-only native observation precedes every update request; uncertain service state rejects. */
object InstalledDaemonUpgrade {
    fun prepare(
        kast: Path,
        userHome: Path,
        environment: Map<String, String>,
        candidate: String,
    ): InstalledUpgradePreparation {
        if (UpgradeCandidate.admit(candidate) is Refinement.Rejected)
            return InstalledUpgradePreparation.Rejected(InstalledUpgradeRejection.CandidateRejected)
        val command =
            when (
                val resolved =
                    BrokerServiceLaunchCommand.resolve(
                        kast,
                        userHome,
                        environment,
                        purpose = BrokerServicePurpose.COORDINATOR,
                    )
            ) {
                is BrokerServiceLaunchCommandResolution.Resolved -> resolved.command
                is BrokerServiceLaunchCommandResolution.Rejected ->
                    return InstalledUpgradePreparation.Rejected(InstalledUpgradeRejection.Command(resolved.failure))
            }
        val markers = observeUpgradeMarkers(command)
        if (markers == UpgradeServiceMarkers.Rejected)
            return InstalledUpgradePreparation.Rejected(InstalledUpgradeRejection.ServiceMarkersRejected)
        val lifecycle = MacOsPersistentBrokerServiceHost().observeLifecycle(command)
        when (val presence = classifyUpgradePresence(lifecycle, markers)) {
            UpgradePresence.Absent -> return InstalledUpgradePreparation.NoDaemon
            is UpgradePresence.Rejected -> return InstalledUpgradePreparation.Rejected(presence.reason)
            UpgradePresence.Active -> Unit
        }
        return prepareActive(command, candidate)
    }

    private fun prepareActive(command: BrokerServiceLaunchCommand, candidate: String): InstalledUpgradePreparation {
        val client = InstalledDaemonManagementClient(command.kast)
        return runBlocking {
            when (val result = client.prepareUpdate(command, candidate)) {
                is Refinement.Rejected ->
                    InstalledUpgradePreparation.Rejected(InstalledUpgradeRejection.Daemon(result.failure))
                is Refinement.Refined ->
                    when (val document = result.value.document) {
                        is DaemonUpgradeDocument.Pending ->
                            InstalledUpgradePreparation.Pending(
                                NonEmptyFailures.from(document.blockers.first(), document.blockers.drop(1))
                            )
                        is DaemonUpgradeDocument.Sealed ->
                            InstalledUpgradePreparation.Sealed(
                                InstalledUpgradePermit(client, command, result.value.target, document)
                            )
                        is DaemonUpgradeDocument.Committed ->
                            when (val admitted = InstalledCommittedUpgradePermit.admit(result.value)) {
                                is Refinement.Refined -> InstalledUpgradePreparation.Committed(admitted.value)
                                is Refinement.Rejected ->
                                    InstalledUpgradePreparation.Rejected(
                                        InstalledUpgradeRejection.Daemon(
                                            DaemonManagementRejection.Protocol(admitted.failure)
                                        )
                                    )
                            }
                        is DaemonUpgradeDocument.Cancelled ->
                            InstalledUpgradePreparation.Rejected(InstalledUpgradeRejection.PreviousUpdateCancelled)
                    }
            }
        }
    }
}

internal sealed interface UpgradePresence {
    data object Absent : UpgradePresence

    data object Active : UpgradePresence

    data class Rejected(val reason: InstalledUpgradeRejection) : UpgradePresence
}

internal enum class UpgradeServiceMarkers {
    Absent,
    Retained,
    Rejected,
}

internal fun classifyUpgradePresence(
    lifecycle: BrokerLifecycleObservation,
    markers: UpgradeServiceMarkers,
): UpgradePresence =
    when (lifecycle) {
        BrokerLifecycleObservation.ALIVE ->
            if (markers == UpgradeServiceMarkers.Rejected)
                UpgradePresence.Rejected(InstalledUpgradeRejection.ServiceMarkersRejected)
            else UpgradePresence.Active
        BrokerLifecycleObservation.ABSENT ->
            when (markers) {
                UpgradeServiceMarkers.Absent -> UpgradePresence.Absent
                UpgradeServiceMarkers.Retained ->
                    UpgradePresence.Rejected(InstalledUpgradeRejection.RetainedServiceEvidence)
                UpgradeServiceMarkers.Rejected ->
                    UpgradePresence.Rejected(InstalledUpgradeRejection.ServiceMarkersRejected)
            }
        BrokerLifecycleObservation.REJECTED ->
            UpgradePresence.Rejected(InstalledUpgradeRejection.Lifecycle(InstalledUpgradeLifecycleFailure.REJECTED))
        BrokerLifecycleObservation.INTERRUPTED ->
            UpgradePresence.Rejected(InstalledUpgradeRejection.Lifecycle(InstalledUpgradeLifecycleFailure.INTERRUPTED))
        BrokerLifecycleObservation.TIMED_OUT ->
            UpgradePresence.Rejected(InstalledUpgradeRejection.Lifecycle(InstalledUpgradeLifecycleFailure.TIMED_OUT))
    }

private fun observeUpgradeMarkers(command: BrokerServiceLaunchCommand): UpgradeServiceMarkers =
    try {
        val agent = command.userHome.resolve("Library/LaunchAgents/${command.serviceLabel.value}.login.plist")
        val markers =
            listOf(
                command.stateDirectory.resolve("service.plist"),
                command.readinessFile,
                command.publicSocket,
                agent,
            )
        var retained = false
        for (path in markers) {
            when (observeUpgradeMarker(path)) {
                UpgradeServiceMarkers.Absent -> Unit
                UpgradeServiceMarkers.Retained -> retained = true
                UpgradeServiceMarkers.Rejected -> return UpgradeServiceMarkers.Rejected
            }
        }
        if (retained) UpgradeServiceMarkers.Retained else UpgradeServiceMarkers.Absent
    } catch (_: SecurityException) {
        UpgradeServiceMarkers.Rejected
    }

internal fun observeUpgradeMarker(path: Path): UpgradeServiceMarkers =
    when {
        Files.isSymbolicLink(path) -> UpgradeServiceMarkers.Rejected
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> UpgradeServiceMarkers.Retained
        Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> UpgradeServiceMarkers.Absent
        else -> UpgradeServiceMarkers.Rejected
    }
