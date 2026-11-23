package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.appserver.provider.BrokerExecutable
import io.github.amichne.kast.appserver.runtime.*
import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOwner
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select

internal object InstalledCoordinatorConfiguration {
    fun admit(
        kast: Path,
        user: Path,
        environment: Map<String, String>,
        activitySink: BrokerStartupActivitySink = JsonLineBrokerStartupActivitySink(System.err),
    ): Refinement<InstalledCoordinatorOptions, InstalledBrokerServerConfigurationFailure> {
        fun reject(failure: InstalledBrokerServerConfigurationFailure) = Refinement.Rejected(failure)
        val executable =
            when (val admission = BrokerExecutable.admit(kast)) {
                is Refinement.Refined -> admission.value.path
                is Refinement.Rejected ->
                    return reject(InstalledBrokerServerConfigurationFailure.KAST_EXECUTABLE_REJECTED)
            }
        val installation = InstallationLayout.ofKastExecutable(executable)
        if (
            InstallationLifecycleFence.observe(installation.version.path) !=
                InstallationLifecycleStartAdmission.AVAILABLE
        )
            return reject(InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED)
        val admittedConfiguration =
            when (val admission = InstalledBrokerConfigurationIngress.admit(environment)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected ->
                    return reject(InstalledBrokerServerConfigurationFailure.PROVIDER_CONFIGURATION_REJECTED)
            }
        val configuration = admittedConfiguration.configuration
        return try {
            if (user.toRealPath() != user || !Files.isDirectory(user, LinkOption.NOFOLLOW_LINKS))
                return reject(InstalledBrokerServerConfigurationFailure.USER_HOME_REJECTED)
            val rawCodexHome = configuration.ownerInputs(ConfigurationOwner.APP_SERVER)["CODEX_HOME"]
            val codexHome = rawCodexHome?.let { Path.of(it) } ?: user.resolve(".codex")
            if (!codexHome.isAbsolute || codexHome.normalize() != codexHome)
                return reject(InstalledBrokerServerConfigurationFailure.CODEX_HOME_REJECTED)
            val layout = BrokerInstallationLayout.from(executable, codexHome)
            for (directory in listOf(layout.broker, layout.run)) {
                Files.createDirectories(directory)
                if (directory.toRealPath() != directory || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                    return reject(InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED)
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            }
            val readiness =
                when (val admission = BrokerServiceReadiness.admit(layout.readiness, environment)) {
                    is BrokerServiceReadinessAdmission.Admitted -> admission.readiness
                    BrokerServiceReadinessAdmission.Rejected ->
                        return reject(InstalledBrokerServerConfigurationFailure.READINESS_REJECTED)
                }
            val publicEndpoint =
                BrokerPublicEndpoint.select(layout, codexHome, admittedConfiguration.publicEndpointMode)
            val socket =
                when (val admission = publicEndpoint.prepare()) {
                    is Validation.Validated -> admission.value
                    is Validation.Rejected ->
                        return reject(InstalledBrokerServerConfigurationFailure.SOCKET_PATH_REJECTED)
                }
            Refinement.Refined(
                InstalledCoordinatorOptions(
                    executable,
                    user,
                    environment.toMap(),
                    installation,
                    socket,
                    publicEndpoint,
                    layout.broker,
                    readiness,
                    configuration,
                    activitySink,
                )
            )
        } catch (_: Exception) {
            reject(InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED)
        }
    }
}

internal sealed interface InstalledCoordinatorStart {
    data class Started(val coordinator: InstalledCoordinator) : InstalledCoordinatorStart

    data class Rejected(val failure: BrokerServerFailure) : InstalledCoordinatorStart
}

internal class InstalledCoordinator
private constructor(
    private val server: KtorBrokerServer,
    private val readiness: OwnedBrokerServiceReadiness?,
    private val retirement: CompletableDeferred<Unit>,
) {
    private val closed = AtomicBoolean(false)
    private val stopped = CompletableDeferred<Unit>()

    /** Completes after close, or once an accepted lifecycle retirement asks the owner to close. */
    suspend fun awaitTermination() = select {
        stopped.onAwait {}
        retirement.onAwait {}
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) {
            stopped.await()
            return
        }
        try {
            server.close()
        } finally {
            readiness?.retire()
            stopped.complete(Unit)
        }
    }

    companion object {
        suspend fun start(
            options: InstalledCoordinatorOptions,
            desktopDiscovery: DesktopDaemonDiscovery = DesktopDaemonDiscovery(),
            semantic: CoordinatorOperationExecutor? = null,
        ): InstalledCoordinatorStart =
            start(options, desktopDiscovery, semantic?.let { CoordinatorSemantic.LaunchBound(it) })

        suspend fun start(
            options: InstalledCoordinatorOptions,
            requests: CoordinatorRequestExecutor,
            desktopDiscovery: DesktopDaemonDiscovery = DesktopDaemonDiscovery(),
            invocations: PublicInvocations? = null,
        ): InstalledCoordinatorStart =
            start(options, desktopDiscovery, CoordinatorSemantic.RequestBound(requests, invocations))

        @Suppress("LongMethod")
        internal suspend fun start(
            options: InstalledCoordinatorOptions,
            desktopDiscovery: DesktopDaemonDiscovery,
            selected: CoordinatorSemantic?,
        ): InstalledCoordinatorStart {
            if (selected != null && options.publicEndpoint !is BrokerPublicEndpoint.Private)
                return InstalledCoordinatorStart.Rejected(BrokerServerFailure.PROTOCOL_CONFIGURATION_REJECTED)
            // The launch-bound executor resolves only this environment, so its stated tier is the conflict authority.
            val semantic =
                when (val execution = selected?.execution(options.environment)) {
                    null -> null
                    is Refinement.Refined -> execution.value
                    is Refinement.Rejected ->
                        return InstalledCoordinatorStart.Rejected(BrokerServerFailure.PROTOCOL_CONFIGURATION_REJECTED)
                }
            val activity = BrokerStartupActivityPublisher(options.activitySink)
            var stage = BrokerStartupStage.READINESS_ACQUISITION
            activity.started(stage)
            val readiness =
                when (val admission = options.readiness.begin()) {
                    BrokerReadinessBeginning.NotManaged -> null
                    is BrokerReadinessBeginning.Begun -> admission.owned
                    BrokerReadinessBeginning.Rejected -> {
                        activity.rejected(
                            stage,
                            BrokerStartupRejection.Coordinator(BrokerServerFailure.READINESS_REJECTED),
                        )
                        return InstalledCoordinatorStart.Rejected(BrokerServerFailure.READINESS_REJECTED)
                    }
                }
            activity.completed(stage)
            fun reject(failure: BrokerServerFailure): InstalledCoordinatorStart.Rejected {
                activity.rejected(stage, BrokerStartupRejection.Coordinator(failure))
                readiness?.reject(failure)
                return InstalledCoordinatorStart.Rejected(failure)
            }
            stage = BrokerStartupStage.INSTALLATION_OWNERSHIP
            activity.started(stage)
            val generation =
                when (val management = options.readiness) {
                    is BrokerServiceReadiness.Managed -> management.instanceId
                    BrokerServiceReadiness.Standalone -> BrokerServiceGeneration.fresh()
                }
            val installation = options.installation.version.path
            val ownership =
                if (semantic == null) BrokerInstallationState.admit(installation)
                else BrokerInstallationState.admitSemantic(installation, generation)
            val owner =
                when (ownership) {
                    is Refinement.Refined -> ownership.value
                    is Refinement.Rejected -> return reject(ownership.failure.serverFailure())
                }
            activity.completed(stage)
            val preparation =
                InstalledWorkspacePreparation(options.userHome, options.configuration, owner.installationId.value)
            val registryPath = options.installation.configuration.workspaceRegistry
            val frontend = deferredFrontend(options, preparation, activity)
            val retirement = CompletableDeferred<Unit>()
            stage = BrokerStartupStage.WORKER_CONTROL
            activity.started(stage)
            val control =
                when (
                    val admission =
                        CoordinatorControl.create(
                            options.installation,
                            owner,
                            generation,
                            options.configuration,
                            stoppedMarker = options.serviceDirectory.resolve("stopped"),
                            hostObservation = frontend::observe,
                            sessions = frontend,
                            preparations = preparation.operations,
                            hostedServices = { preparation.hostedServices(registryPath) },
                            privateEndpoint = options.publicEndpoint is BrokerPublicEndpoint.Private,
                            semantic = semantic,
                            requestRetirement = { retirement.complete(Unit) },
                        )
                ) {
                    is Refinement.Refined -> admission.value
                    is Refinement.Rejected -> {
                        frontend.close()
                        preparation.operations.close()
                        return reject(BrokerServerFailure.STATE_DIRECTORY_REJECTED)
                    }
                }
            activity.completed(stage)
            stage = BrokerStartupStage.PUBLIC_SERVER
            activity.started(stage)
            val server =
                when (val started = KtorBrokerServer.startCoordinator(options.socket, control, frontend)) {
                    is KtorBrokerServerStart.Started -> started.server
                    is KtorBrokerServerStart.Rejected -> {
                        frontend.close()
                        control.drain()
                        return reject(BrokerServerFailure.SERVER_REJECTED)
                    }
                }
            activity.completed(stage)
            val semanticOwner = if (semantic == null) null else owner
            return completeStartup(options, server, readiness, activity, desktopDiscovery, semanticOwner, retirement)
        }

        private fun deferredFrontend(
            options: InstalledCoordinatorOptions,
            preparation: InstalledWorkspacePreparation,
            activity: BrokerStartupActivityPublisher,
        ): DeferredBrokerFrontend = DeferredBrokerFrontend { upgrades ->
            activity.started(BrokerStartupStage.HOST_ADMISSION)
            when (val configuration = options.admitHost(preparation.demand)) {
                is InstalledBrokerServerConfiguration.Rejected -> {
                    activity.rejected(
                        BrokerStartupStage.HOST_ADMISSION,
                        BrokerStartupRejection.HostAdmission(configuration.failure),
                    )
                    BrokerFrontendAdmission.Rejected
                }
                is InstalledBrokerServerConfiguration.Configured -> {
                    activity.completed(BrokerStartupStage.HOST_ADMISSION)
                    when (
                        val prepared =
                            InstalledBrokerHost.prepare(
                                configuration.options.copy(startupActivitySink = options.activitySink)
                            )
                    ) {
                        is InstalledBrokerHostStart.Rejected -> BrokerFrontendAdmission.Rejected
                        is InstalledBrokerHostStart.Prepared ->
                            when (
                                val admission =
                                    KtorBrokerServer.frontend(prepared.options, upgrades) {
                                        prepared.upstream.close()
                                    }
                            ) {
                                is BrokerFrontendAdmission.Prepared -> admission
                                BrokerFrontendAdmission.Rejected -> {
                                    prepared.upstream.close()
                                    admission
                                }
                            }
                    }
                }
            }
        }

        private suspend fun completeStartup(
            options: InstalledCoordinatorOptions,
            server: KtorBrokerServer,
            readiness: OwnedBrokerServiceReadiness?,
            activity: BrokerStartupActivityPublisher,
            desktopDiscovery: DesktopDaemonDiscovery,
            semanticOwner: ThreadBindingOwner.Installation?,
            retirement: CompletableDeferred<Unit>,
        ): InstalledCoordinatorStart {
            val peer =
                if (semanticOwner != null && readiness != null) {
                    server.semanticPeer(semanticOwner.installationId.value)
                        ?: run {
                            server.close()
                            readiness.reject(BrokerServerFailure.READINESS_REJECTED)
                            return InstalledCoordinatorStart.Rejected(BrokerServerFailure.READINESS_REJECTED)
                        }
                } else null
            if (provePublicReadiness(options, activity) == PublicReadinessGate.REJECTED) {
                server.close()
                activity.rejected(
                    BrokerStartupStage.NATIVE_PROTOCOL,
                    BrokerStartupRejection.Coordinator(BrokerServerFailure.NATIVE_PROTOCOL_REJECTED),
                )
                readiness?.reject(BrokerServerFailure.NATIVE_PROTOCOL_REJECTED)
                return InstalledCoordinatorStart.Rejected(BrokerServerFailure.NATIVE_PROTOCOL_REJECTED)
            }
            when (publishDesktopDiscovery(DesktopDiscoveryTarget.from(options), activity, desktopDiscovery)) {
                DesktopDiscoveryOutcome.Ready -> Unit
                is DesktopDiscoveryOutcome.Rejected -> {
                    server.close()
                    readiness?.reject(BrokerServerFailure.DESKTOP_DISCOVERY_REJECTED)
                    return InstalledCoordinatorStart.Rejected(BrokerServerFailure.DESKTOP_DISCOVERY_REJECTED)
                }
            }
            return publishReadiness(
                server,
                readiness,
                activity,
                DesktopDiscoveryTarget.from(options),
                desktopDiscovery,
                peer,
                retirement,
            )
        }

        internal fun publishDesktopDiscovery(
            target: DesktopDiscoveryTarget,
            activity: BrokerStartupActivityPublisher,
            discovery: DesktopDaemonDiscovery,
        ): DesktopDiscoveryOutcome {
            if (target == DesktopDiscoveryTarget.NotRequired) return DesktopDiscoveryOutcome.Ready
            activity.started(BrokerStartupStage.DESKTOP_DISCOVERY)
            return when (val outcome = discovery.enable(target)) {
                DesktopDiscoveryOutcome.Ready -> {
                    activity.completed(BrokerStartupStage.DESKTOP_DISCOVERY)
                    outcome
                }
                is DesktopDiscoveryOutcome.Rejected -> {
                    activity.rejected(
                        BrokerStartupStage.DESKTOP_DISCOVERY,
                        BrokerStartupRejection.DesktopDiscovery(outcome.failure),
                    )
                    when (outcome.rollback) {
                        DesktopDiscoveryRollback.NOT_REQUIRED -> outcome
                        DesktopDiscoveryRollback.REQUIRED ->
                            when (val cleanup = CoordinatorReadinessPublisher.rollback(target, discovery, activity)) {
                                DesktopDiscoveryOutcome.Ready -> outcome
                                is DesktopDiscoveryOutcome.Rejected -> cleanup
                            }
                    }
                }
            }
        }

        private suspend fun provePublicReadiness(
            options: InstalledCoordinatorOptions,
            activity: BrokerStartupActivityPublisher,
        ): PublicReadinessGate {
            if (options.publicEndpoint is BrokerPublicEndpoint.Private) return PublicReadinessGate.NOT_REQUIRED
            activity.started(BrokerStartupStage.NATIVE_PROTOCOL)
            val observed =
                NativeCodexReadiness.observe(options.socket.path, BrokerOperationalLimits.serviceChildPhases.value)
            if (observed == NativeCodexReadiness.READY) activity.completed(BrokerStartupStage.NATIVE_PROTOCOL)
            return when (observed) {
                NativeCodexReadiness.READY -> PublicReadinessGate.PROVEN
                NativeCodexReadiness.REJECTED -> PublicReadinessGate.REJECTED
            }
        }

        private suspend fun publishReadiness(
            server: KtorBrokerServer,
            readiness: OwnedBrokerServiceReadiness?,
            activity: BrokerStartupActivityPublisher,
            target: DesktopDiscoveryTarget,
            discovery: DesktopDaemonDiscovery,
            peer: SemanticCoordinatorPeer?,
            retirement: CompletableDeferred<Unit>,
        ): InstalledCoordinatorStart =
            when (val outcome = CoordinatorReadinessPublisher.publish(readiness, target, discovery, activity, peer)) {
                CoordinatorReadinessPublication.Ready ->
                    InstalledCoordinatorStart.Started(InstalledCoordinator(server, readiness, retirement))
                is CoordinatorReadinessPublication.Rejected -> {
                    server.close()
                    InstalledCoordinatorStart.Rejected(outcome.failure)
                }
            }
    }
}

private enum class PublicReadinessGate {
    NOT_REQUIRED,
    PROVEN,
    REJECTED,
}
