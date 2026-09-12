package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.BrokerServiceGeneration
import io.github.amichne.kast.appserver.CoordinatorControlAction
import io.github.amichne.kast.appserver.CoordinatorControlRequest
import io.github.amichne.kast.appserver.CoordinatorHostAttachment
import io.github.amichne.kast.appserver.CoordinatorStatusProtocol
import io.github.amichne.kast.appserver.InstallationLifecycleFence
import io.github.amichne.kast.appserver.InstallationLifecycleStartAdmission
import io.github.amichne.kast.appserver.WorkerControlFailure
import io.github.amichne.kast.appserver.coordinatorConfigurationIdentity
import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.appserver.rejectedCoordinatorControl
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** Coordinator status only. The existing IDE exclusively owns semantic execution and project lifetime. */
internal class CoordinatorControl
private constructor(
    private val installationRoot: Path,
    private val owner: ThreadBindingOwner.Installation,
    private val generation: BrokerServiceGeneration,
    private val configuration: ResolvedKastConfiguration,
    private val stoppedMarker: Path,
    private val hostObservation: () -> BrokerFrontendObservation,
) {
    private val closed = AtomicBoolean(false)

    suspend fun drain() {
        closed.set(true)
    }

    suspend fun handle(session: DefaultWebSocketServerSession) {
        val frame =
            withTimeoutOrNull(BrokerOperationalLimits.workerControlHandshake.value) {
                session.incoming.receiveCatching().getOrNull()
            }
        val request =
            try {
                val text = (frame as? Frame.Text)?.readText() ?: return
                if (text.toByteArray().size > CoordinatorStatusProtocol.maximumCommandBytes) return
                Json.decodeFromString<CoordinatorControlRequest>(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                session.send(rejectedCoordinatorControl(WorkerControlFailure.INVALID_REQUEST))
                return
            }
        val response =
            when (request.action) {
                CoordinatorControlAction.STATUS ->
                    if (available())
                        CoordinatorStatusProtocol.encode(
                            owner.installationId.value,
                            owner.stateEpoch.value.toString(),
                            generation.value.toString(),
                            coordinatorConfigurationIdentity(configuration),
                            CoordinatorHostAttachment.valueOf(hostObservation().name),
                        )
                    else rejectedCoordinatorControl(WorkerControlFailure.LIFECYCLE_TRANSITION)
                CoordinatorControlAction.DEMAND,
                CoordinatorControlAction.RETIRED ->
                    rejectedCoordinatorControl(WorkerControlFailure.ISOLATED_RUNTIME_RETIRED)
            }
        session.send(response)
    }

    private fun available(): Boolean =
        !closed.get() &&
            InstallationLifecycleFence.observe(installationRoot) == InstallationLifecycleStartAdmission.AVAILABLE &&
            try {
                Files.readAttributes(
                    stoppedMarker,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                false
            } catch (_: NoSuchFileException) {
                true
            } catch (_: Exception) {
                false
            }

    companion object {
        fun create(
            installationRoot: Path,
            owner: ThreadBindingOwner.Installation,
            generation: BrokerServiceGeneration,
            configuration: ResolvedKastConfiguration,
            stoppedMarker: Path,
            hostObservation: () -> BrokerFrontendObservation = { BrokerFrontendObservation.PENDING },
        ): Refinement<CoordinatorControl, WorkerControlFailure> =
            try {
                val legacy = installationRoot.resolve("state/workers")
                when {
                    installationRoot.toRealPath() != installationRoot ||
                        InstallationLifecycleFence.observe(installationRoot) !=
                            InstallationLifecycleStartAdmission.AVAILABLE ->
                        Refinement.Rejected(WorkerControlFailure.LIFECYCLE_TRANSITION)
                    Files.exists(legacy, LinkOption.NOFOLLOW_LINKS) &&
                        (!Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS) ||
                            Files.list(legacy).use { it.findAny().isPresent }) ->
                        Refinement.Rejected(WorkerControlFailure.RECOVERY_REQUIRED)
                    else ->
                        Refinement.Refined(
                            CoordinatorControl(
                                installationRoot,
                                owner,
                                generation,
                                configuration,
                                stoppedMarker,
                                hostObservation,
                            )
                        )
                }
            } catch (_: Exception) {
                Refinement.Rejected(WorkerControlFailure.RECEIPT_REJECTED)
            }
    }
}
