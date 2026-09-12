package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.BrokerControlRoute
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import io.github.amichne.kast.appserver.runtime.connectCodexUnixWebSocket
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import java.nio.file.Path
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A coordinator descendant may use its already-published service, but must never lifecycle-manage it. */
internal sealed interface BrokerServiceDemandContext {
    data object External : BrokerServiceDemandContext

    data object CurrentServiceDescendant : BrokerServiceDemandContext

    data object Rejected : BrokerServiceDemandContext

    companion object {
        private val serviceRuntimeKeys =
            setOf(
                "BROKER_SERVICE_IDENTITY",
                "BROKER_READINESS_FILE",
                "KAST_OPTS",
            )

        /**
         * Service ownership proofs and the launcher's synthetic JVM option are observations, never configuration
         * inputs.
         */
        fun configurationEnvironment(environment: Map<String, String>): Map<String, String> =
            environment - serviceRuntimeKeys

        fun resolveCommand(
            kast: Path,
            userHome: Path,
            environment: Map<String, String>,
        ): BrokerServiceLaunchCommandResolution =
            BrokerServiceLaunchCommand.resolveCoordinator(
                kast,
                userHome,
                configurationEnvironment(environment),
            )

        fun observe(
            command: BrokerServiceLaunchCommand,
            environment: Map<String, String>,
        ): BrokerServiceDemandContext {
            val identity = environment["BROKER_SERVICE_IDENTITY"]
            val readiness = environment["BROKER_READINESS_FILE"]
            if (identity == null && readiness == null) return External
            return if (identity == command.identity.value && readiness == command.readinessFile.toString()) {
                CurrentServiceDescendant
            } else {
                Rejected
            }
        }
    }
}

/** Read-only client for the coordinator's own identity and host attachment. */
internal class InstalledCoordinatorClient(private val kast: Path) {
    internal suspend fun status(command: BrokerServiceLaunchCommand): CoordinatorStatusRead {
        val publication =
            when (val read = published(command)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return CoordinatorStatusRead.Rejected(read.failure)
            }
        return withTimeoutOrNull(OperationExecutionBudget.LOCAL_QUALIFICATION.value) {
            val connection =
                when (
                    val connected =
                        connectCodexUnixWebSocket(
                            command.publicSocket,
                            CoordinatorStatusProtocol.maximumMessageBytes,
                            OperationExecutionBudget.LOCAL_QUALIFICATION.value,
                            BrokerControlRoute.RUNTIME,
                        )
                ) {
                    is BrokerUpstreamConnectionAdmission.Connected -> connected.connection
                    BrokerUpstreamConnectionAdmission.Rejected ->
                        return@withTimeoutOrNull CoordinatorStatusRead.Rejected(WorkerControlFailure.UNAVAILABLE)
                }
            try {
                exchange(connection, publication)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { connection.close() }
            }
        } ?: CoordinatorStatusRead.Rejected(WorkerControlFailure.DEADLINE_EXCEEDED)
    }

    private suspend fun exchange(
        connection: io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnection,
        publication: Published,
    ): CoordinatorStatusRead {
        val request = Json.encodeToString(CoordinatorControlRequest(CoordinatorControlAction.STATUS))
        if (connection.send(request) != BrokerUpstreamSend.SENT)
            return CoordinatorStatusRead.Rejected(WorkerControlFailure.UNAVAILABLE)
        val frame = connection.receive()
        if (frame !is BrokerUpstreamFrame.Text) return CoordinatorStatusRead.Rejected(WorkerControlFailure.UNAVAILABLE)
        val snapshot =
            when (val admitted = CoordinatorStatusSnapshot.decode(frame.message)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return CoordinatorStatusRead.Rejected(admitted.failure)
            }
        return if (snapshot.belongsTo(publication.owner, publication.service)) CoordinatorStatusRead.Observed(snapshot)
        else CoordinatorStatusRead.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
    }

    private class Published(
        val owner: io.github.amichne.kast.appserver.protocol.ThreadBindingOwner.Installation,
        val service: BrokerServiceStateDocument.Ready,
    )

    private fun published(command: BrokerServiceLaunchCommand): Refinement<Published, WorkerControlFailure> {
        return try {
            val root = kast.toRealPath().parent.parent
            val owner =
                when (val observed = BrokerInstallationState.observe(root)) {
                    is Refinement.Refined -> observed.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(
                            if (observed.failure == InstallationStateFailure.EPOCH_ABSENT)
                                WorkerControlFailure.UNAVAILABLE
                            else WorkerControlFailure.IDENTITY_REJECTED
                        )
                }
            val state =
                BROKER_SERVICE_STATE_JSON.decodeFromString<BrokerServiceStateDocument>(
                    readBounded(command.readinessFile)
                )
            if (
                state !is BrokerServiceStateDocument.Ready || state.schemaVersion != BROKER_SERVICE_STATE_SCHEMA_VERSION
            )
                Refinement.Rejected(WorkerControlFailure.UNAVAILABLE)
            else Refinement.Refined(Published(owner, state))
        } catch (_: Exception) {
            Refinement.Rejected(WorkerControlFailure.UNAVAILABLE)
        }
    }

    private fun readBounded(path: Path): String {
        if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw java.io.IOException("state rejected")
        return java.nio.file.Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use {
            val bytes = it.readNBytes(BrokerOperationalLimits.maximumControlStateBytes + 1)
            if (bytes.size > BrokerOperationalLimits.maximumControlStateBytes)
                throw java.io.IOException("state rejected")
            bytes.toString(Charsets.UTF_8)
        }
    }
}
