package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.provider.BrokerExecutable
import io.github.amichne.kast.appserver.runtime.*
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOwner
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean

internal class InstalledCoordinatorOptions(
    val kast: Path,
    val userHome: Path,
    val environment: Map<String, String>,
    val installationRoot: Path,
    val socket: BrokerSocketPath,
    val serviceDirectory: Path,
    val readiness: BrokerServiceReadiness,
    val configuration: ResolvedKastConfiguration,
    val activitySink: BrokerStartupActivitySink,
) {
    /** Host admission is deferred; successful options must retain this process's readiness authority. */
    internal fun admitHost(): InstalledBrokerServerConfiguration = when (
        val admitted = InstalledBrokerServerConfiguration.admit(kast, userHome, environment)
    ) {
        is InstalledBrokerServerConfiguration.Rejected -> admitted
        is InstalledBrokerServerConfiguration.Configured -> InstalledBrokerServerConfiguration.Configured(
            admitted.options.copy(readiness = readiness),
        )
    }
}

internal object InstalledCoordinatorConfiguration {
    fun admit(kast: Path, user: Path, environment: Map<String, String>,
        activitySink: BrokerStartupActivitySink = JsonLineBrokerStartupActivitySink(System.err)): Refinement<InstalledCoordinatorOptions, InstalledBrokerServerConfigurationFailure> {
        fun reject(failure: InstalledBrokerServerConfigurationFailure) = Refinement.Rejected(failure)
        val executable = when (val admission = BrokerExecutable.admit(kast)) {
            is Refinement.Refined -> admission.value.path
            is Refinement.Rejected -> return reject(InstalledBrokerServerConfigurationFailure.KAST_EXECUTABLE_REJECTED)
        }
        val root = executable.parent.parent
        if (InstallationLifecycleFence.observe(root) != InstallationLifecycleStartAdmission.AVAILABLE)
            return reject(InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED)
        val configuration = when (val admission = InstalledBrokerConfigurationIngress.admit(environment)) {
            is Refinement.Refined -> admission.value.configuration
            is Refinement.Rejected -> return reject(InstalledBrokerServerConfigurationFailure.PROVIDER_CONFIGURATION_REJECTED)
        }
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
            val readiness = when (val admission = BrokerServiceReadiness.admit(layout.broker, environment)) {
                is BrokerServiceReadinessAdmission.Admitted -> admission.readiness
                BrokerServiceReadinessAdmission.Rejected -> return reject(InstalledBrokerServerConfigurationFailure.READINESS_REJECTED)
            }
            val socket = when (val admission = BrokerSocketPath.prepareInstalled(layout.run.resolve("c.sock"))) {
                is Validation.Validated -> admission.value
                is Validation.Rejected -> return reject(InstalledBrokerServerConfigurationFailure.SOCKET_PATH_REJECTED)
            }
            Refinement.Refined(InstalledCoordinatorOptions(executable, user, environment.toMap(), root, socket, layout.broker, readiness, configuration, activitySink))
        } catch (_: Exception) { reject(InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED) }
    }
}

internal sealed interface InstalledCoordinatorStart {
    data class Started(val coordinator: InstalledCoordinator) : InstalledCoordinatorStart
    data class Rejected(val failure: BrokerServerFailure) : InstalledCoordinatorStart
}

internal class InstalledCoordinator private constructor(
    private val server: KtorBrokerServer,
    private val readiness: OwnedBrokerServiceReadiness?,
) {
    private val closed = AtomicBoolean(false)
    private val stopped = CompletableDeferred<Unit>()
    suspend fun awaitTermination() = stopped.await()
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) { stopped.await(); return }
        try { server.close() } finally { readiness?.retire(); stopped.complete(Unit) }
    }

    companion object {
        suspend fun start(options: InstalledCoordinatorOptions, effects: InstalledWorkerEffects): InstalledCoordinatorStart {
            val activity = BrokerStartupActivityPublisher(options.activitySink)
            var stage = BrokerStartupStage.READINESS_ACQUISITION
            activity.started(stage)
            val readiness = when (val admission = options.readiness.begin()) {
                BrokerReadinessBeginning.NotManaged -> null
                is BrokerReadinessBeginning.Begun -> admission.owned
                BrokerReadinessBeginning.Rejected -> {
                    activity.rejected(stage, BrokerStartupRejection.Coordinator(BrokerServerFailure.READINESS_REJECTED))
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
            val owner = when (val admission = BrokerInstallationState.admit(options.installationRoot)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return reject(BrokerServerFailure.STATE_DIRECTORY_REJECTED)
            }
            activity.completed(stage)
            val generation = when (val management = options.readiness) {
                is BrokerServiceReadiness.Managed -> management.instanceId
                BrokerServiceReadiness.Standalone -> BrokerServiceGeneration.fresh()
            }
            val frontend = DeferredBrokerFrontend {
                activity.started(BrokerStartupStage.HOST_ADMISSION)
                when (val configuration = options.admitHost()) {
                    is InstalledBrokerServerConfiguration.Rejected -> {
                        activity.rejected(BrokerStartupStage.HOST_ADMISSION, BrokerStartupRejection.HostAdmission(configuration.failure))
                        BrokerFrontendAdmission.Rejected
                    }
                    is InstalledBrokerServerConfiguration.Configured -> {
                        activity.completed(BrokerStartupStage.HOST_ADMISSION)
                        when (val prepared = InstalledBrokerHost.prepare(configuration.options.copy(startupActivitySink = options.activitySink))) {
                        is InstalledBrokerHostStart.Rejected -> BrokerFrontendAdmission.Rejected
                        is InstalledBrokerHostStart.Prepared -> when (val admission = KtorBrokerServer.frontend(prepared.options) { prepared.upstream.close() }) {
                            is BrokerFrontendAdmission.Prepared -> admission
                            BrokerFrontendAdmission.Rejected -> { prepared.upstream.close(); admission }
                        }
                        }
                    }
                }
            }
            stage = BrokerStartupStage.WORKER_CONTROL
            activity.started(stage)
            val control = when (val admission = WorkspaceRuntimeControl.create(
                options.installationRoot, owner, generation, options.configuration, effects,
                lifecycle = WorkerControlLifecycle.Installed(options.serviceDirectory.resolve("stopped")),
                hostObservation = frontend::observe,
                sourceEnvironment = options.environment,
            )) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> { frontend.close(); return reject(BrokerServerFailure.STATE_DIRECTORY_REJECTED) }
            }
            activity.completed(stage)
            stage = BrokerStartupStage.PUBLIC_SERVER
            activity.started(stage)
            val server = when (val started = KtorBrokerServer.startCoordinator(options.socket, control, frontend)) {
                is KtorBrokerServerStart.Started -> started.server
                is KtorBrokerServerStart.Rejected -> { frontend.close(); control.drain(); return reject(BrokerServerFailure.SERVER_REJECTED) }
            }
            activity.completed(stage)
            stage = BrokerStartupStage.READINESS_PUBLICATION
            activity.started(stage)
            if (readiness?.ready() == BrokerReadinessTransition.Rejected) {
                server.close()
                return reject(BrokerServerFailure.READINESS_REJECTED)
            }
            activity.completed(stage)
            return InstalledCoordinatorStart.Started(InstalledCoordinator(server, readiness))
        }
    }
}
