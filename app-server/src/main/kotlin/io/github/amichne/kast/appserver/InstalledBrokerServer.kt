package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.JsonLineBrokerInvocationActivitySink
import io.github.amichne.kast.appserver.core.ProviderDefinition
import io.github.amichne.kast.appserver.host.admission.CodexAppServerArguments
import io.github.amichne.kast.appserver.host.admission.DesktopFacadeExecutables
import io.github.amichne.kast.appserver.host.admission.UpstreamCodexExecutable
import io.github.amichne.kast.appserver.protocol.FileThreadCatalogStore
import io.github.amichne.kast.appserver.protocol.FileThreadCatalogStoreOpen
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolQualification
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolQualificationOptions
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolQualifier
import io.github.amichne.kast.appserver.provider.BrokerExecutable
import io.github.amichne.kast.appserver.provider.BrokerProcessExecutor
import io.github.amichne.kast.appserver.provider.GradleProvider
import io.github.amichne.kast.appserver.provider.JdkBrokerProcessExecutor
import io.github.amichne.kast.appserver.provider.KastProviderOptions
import io.github.amichne.kast.appserver.provider.KastProviderQualification
import io.github.amichne.kast.appserver.provider.KastProviderQualifier
import io.github.amichne.kast.appserver.runtime.BrokerSocketPath
import io.github.amichne.kast.appserver.runtime.CodexAppServerProcessLauncher
import io.github.amichne.kast.appserver.runtime.KtorBrokerServer
import io.github.amichne.kast.appserver.runtime.KtorBrokerServerOptions
import io.github.amichne.kast.appserver.runtime.KtorBrokerServerStart
import io.github.amichne.kast.appserver.runtime.ManagedCodexUpstream
import io.github.amichne.kast.appserver.runtime.ManagedCodexUpstreamOptions
import io.github.amichne.kast.appserver.runtime.ManagedCodexUpstreamStart
import io.github.amichne.kast.appserver.runtime.ManagedCodexUpstreamTermination
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOwner
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class InstalledBrokerServerConfigurationFailure {
    KAST_EXECUTABLE_REJECTED,
    USER_HOME_REJECTED,
    CODEX_EXECUTABLE_REJECTED,
    CODEX_HOME_REJECTED,
    STATE_DIRECTORY_REJECTED,
    SOCKET_PATH_REJECTED,
    READINESS_REJECTED,
    PROVIDER_CONFIGURATION_REJECTED,
    PROTOCOL_CONFIGURATION_REJECTED,
    APP_SERVER_DISABLED,
}

internal enum class BrokerClientTransport {
    LEGACY_CONTROL,
    INTEGRATION_OWNED,
}

internal sealed interface InstalledBrokerServerConfiguration {
    data class Configured(val options: InstalledBrokerServerOptions) : InstalledBrokerServerConfiguration

    data class Rejected(val failure: InstalledBrokerServerConfigurationFailure) : InstalledBrokerServerConfiguration

    companion object {
        internal fun admit(
            kastExecutable: Path,
            userHome: Path,
            environment: Map<String, String>,
            processExecutor: BrokerProcessExecutor = JdkBrokerProcessExecutor,
            launcher: CodexAppServerProcessLauncher? = null,
            clientTransport: BrokerClientTransport = BrokerClientTransport.LEGACY_CONTROL,
            appServerArguments: CodexAppServerArguments = CodexAppServerArguments.sharedService(),
        ): InstalledBrokerServerConfiguration {
            val canonicalUserHome =
                canonicalDirectory(userHome)
                    ?: return rejected(InstalledBrokerServerConfigurationFailure.USER_HOME_REJECTED)
            val kast =
                when (val admission = BrokerExecutable.admit(kastExecutable)) {
                    is Refinement.Refined -> admission.value
                    is Refinement.Rejected ->
                        return rejected(InstalledBrokerServerConfigurationFailure.KAST_EXECUTABLE_REJECTED)
                }
            if (
                InstallationLifecycleFence.observe(kast.path.parent.parent) !=
                    InstallationLifecycleStartAdmission.AVAILABLE
            ) {
                return rejected(InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED)
            }
            val admittedConfiguration =
                when (val admission = InstalledBrokerConfigurationIngress.admit(environment)) {
                    is Refinement.Refined -> admission.value
                    is Refinement.Rejected ->
                        return rejected(
                            when (val failure = admission.failure) {
                                is BrokerConfigurationIngressRejection.Configuration ->
                                    when (failure.failure.key) {
                                        "CODEX_EXECUTABLE",
                                        "KAST_REAL_CODEX_EXECUTABLE" ->
                                            InstalledBrokerServerConfigurationFailure.CODEX_EXECUTABLE_REJECTED
                                        "CODEX_HOME" -> InstalledBrokerServerConfigurationFailure.CODEX_HOME_REJECTED
                                        else ->
                                            InstalledBrokerServerConfigurationFailure.PROVIDER_CONFIGURATION_REJECTED
                                    }
                                is BrokerConfigurationIngressRejection.Owner,
                                is BrokerConfigurationIngressRejection.Source ->
                                    InstalledBrokerServerConfigurationFailure.PROVIDER_CONFIGURATION_REJECTED
                            }
                        )
                }
            val configuration = admittedConfiguration.configuration
            val ownerInputs = configuration.ownerInputs(ConfigurationOwner.APP_SERVER)
            if (admittedConfiguration.toolingMode == AppServerToolingMode.DISABLED) {
                return rejected(InstalledBrokerServerConfigurationFailure.APP_SERVER_DISABLED)
            }
            val toolSelection = admittedConfiguration.toolSelection
            val codexPath =
                when {
                    ownerInputs.containsKey("KAST_REAL_CODEX_EXECUTABLE") ->
                        absoluteNormalizedPath(ownerInputs.getValue("KAST_REAL_CODEX_EXECUTABLE"))
                    ownerInputs.containsKey("CODEX_EXECUTABLE") ->
                        absoluteNormalizedPath(ownerInputs.getValue("CODEX_EXECUTABLE"))
                    else -> resolveExecutable("codex", environment["PATH"].orEmpty())
                } ?: return rejected(InstalledBrokerServerConfigurationFailure.CODEX_EXECUTABLE_REJECTED)
            val facades =
                DesktopFacadeExecutables.resolve(
                    kast.path.parent.resolve("kast-codex"),
                    environment["CODEX_CLI_PATH"],
                )
            val codex =
                when (val admission = UpstreamCodexExecutable.admit(codexPath, facades)) {
                    is Refinement.Refined -> admission.value
                    is Refinement.Rejected ->
                        return rejected(InstalledBrokerServerConfigurationFailure.CODEX_EXECUTABLE_REJECTED)
                }
            val codexHomeCandidate =
                if (ownerInputs.containsKey("CODEX_HOME")) absoluteNormalizedPath(ownerInputs.getValue("CODEX_HOME"))
                else canonicalUserHome.resolve(".codex")
            if (codexHomeCandidate == null) {
                return rejected(InstalledBrokerServerConfigurationFailure.CODEX_HOME_REJECTED)
            }
            val codexHome =
                createConfigurationDirectory(codexHomeCandidate)
                    ?: return rejected(InstalledBrokerServerConfigurationFailure.CODEX_HOME_REJECTED)
            val installation = BrokerInstallationLayout.from(kast.path, codexHome)
            val stateDirectory =
                createBrokerOwnedDirectory(installation.broker)
                    ?: return rejected(InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED)
            val protocolTemporary =
                createBrokerOwnedDirectory(stateDirectory.resolve("protocol"))
                    ?: return rejected(InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED)
            val publicParent =
                createBrokerOwnedDirectory(installation.run)
                    ?: return rejected(InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED)
            val publicSocket =
                when (val admission = BrokerSocketPath.prepareInstalled(publicParent.resolve("c.sock"))) {
                    is Validation.Validated -> admission.value
                    is Validation.Rejected ->
                        return rejected(InstalledBrokerServerConfigurationFailure.SOCKET_PATH_REJECTED)
                }
            val privateSocket =
                when (val admission = BrokerSocketPath.prepareInstalled(publicParent.resolve("u.sock"))) {
                    is Validation.Validated -> admission.value
                    is Validation.Rejected ->
                        return rejected(InstalledBrokerServerConfigurationFailure.SOCKET_PATH_REJECTED)
                }
            if (publicSocket == privateSocket) {
                return rejected(InstalledBrokerServerConfigurationFailure.SOCKET_PATH_REJECTED)
            }
            val readiness =
                when (val admitted = BrokerServiceReadiness.admit(stateDirectory, environment)) {
                    is BrokerServiceReadinessAdmission.Admitted -> admitted.readiness
                    BrokerServiceReadinessAdmission.Rejected ->
                        return rejected(InstalledBrokerServerConfigurationFailure.READINESS_REJECTED)
                }
            val kastOptions =
                when (
                    val admission =
                        KastProviderOptions.admit(
                            kast.path,
                            canonicalUserHome,
                            processExecutor,
                            toolSelection = toolSelection,
                            readLimits = configuration.readLimits,
                        )
                ) {
                    is Refinement.Refined -> admission.value
                    is Refinement.Rejected ->
                        return rejected(InstalledBrokerServerConfigurationFailure.PROVIDER_CONFIGURATION_REJECTED)
                }
            val protocolOptions =
                when (
                    val admission =
                        CodexProtocolQualificationOptions.admit(
                            codex,
                            codexHome,
                            protocolTemporary,
                            processExecutor,
                            maximumSchemaBytes = BrokerOperationalLimits.installedCodexSchemaBytes,
                            maximumSchemaFiles = BrokerOperationalLimits.maximumCodexSchemaFiles,
                            timeoutMillis = BrokerOperationalLimits.codexQualification.value,
                        )
                ) {
                    is Refinement.Refined -> admission.value
                    is Refinement.Rejected ->
                        return rejected(InstalledBrokerServerConfigurationFailure.PROTOCOL_CONFIGURATION_REJECTED)
                }
            val upstreamOptions =
                if (launcher == null) {
                    ManagedCodexUpstreamOptions(
                        codex,
                        codexHome,
                        privateSocket,
                        maximumMessageBytes = MAXIMUM_MESSAGE_BYTES,
                        startupTimeoutMillis = UPSTREAM_STARTUP_TIMEOUT_MILLIS,
                        appServerArguments = appServerArguments,
                    )
                } else {
                    ManagedCodexUpstreamOptions(
                        codex,
                        codexHome,
                        privateSocket,
                        launcher,
                        MAXIMUM_MESSAGE_BYTES,
                        UPSTREAM_STARTUP_TIMEOUT_MILLIS,
                        appServerArguments,
                    )
                }
            return Configured(
                InstalledBrokerServerOptions(
                    kastOptions = kastOptions,
                    protocolOptions = protocolOptions,
                    upstreamOptions = upstreamOptions,
                    threadStore = stateDirectory.resolve("threads.json"),
                    publicSocket = publicSocket,
                    readiness = readiness,
                    limits = BrokerLimits.defaults(),
                    maximumConnections = BrokerOperationalLimits.maximumConnections,
                    maximumMessageBytes = MAXIMUM_MESSAGE_BYTES,
                    startupActivitySink = JsonLineBrokerStartupActivitySink(System.err),
                    installationRoot = installation.root,
                    configuration = configuration,
                )
            )
        }

        private fun createConfigurationDirectory(path: Path): Path? =
            try {
                Files.createDirectories(
                    path,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                canonicalDirectory(path)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

        private fun createBrokerOwnedDirectory(path: Path): Path? =
            try {
                Files.createDirectories(
                    path,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
                canonicalDirectory(path)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

        private fun canonicalDirectory(path: Path): Path? =
            try {
                path.toRealPath().takeIf { canonical ->
                    canonical == path && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

        private fun resolveExecutable(name: String, searchPath: String): Path? {
            val directories = searchPath.split(File.pathSeparatorChar)
            if (directories.isEmpty() || directories.any(String::isBlank)) return null
            return directories
                .asSequence()
                .mapNotNull(::absoluteNormalizedPath)
                .map { directory -> directory.resolve(name) }
                .firstOrNull { candidate ->
                    BrokerExecutable.admit(candidate) is Refinement.Refined
                }
        }

        private fun absoluteNormalizedPath(raw: String): Path? =
            try {
                Path.of(raw).takeIf { path -> path.isAbsolute && path.normalize() == path }
            } catch (_: RuntimeException) {
                null
            }

        private fun rejected(failure: InstalledBrokerServerConfigurationFailure): Rejected = Rejected(failure)

        private const val MAXIMUM_MESSAGE_BYTES = BrokerOperationalLimits.maximumMessageBytes
        private val UPSTREAM_STARTUP_TIMEOUT_MILLIS: Long
            get() = BrokerOperationalLimits.upstreamStartup.value
    }
}

internal data class InstalledBrokerServerOptions(
    val kastOptions: KastProviderOptions,
    val protocolOptions: CodexProtocolQualificationOptions,
    val upstreamOptions: ManagedCodexUpstreamOptions,
    val threadStore: Path,
    val publicSocket: BrokerSocketPath,
    val readiness: BrokerServiceReadiness,
    val limits: BrokerLimits,
    val maximumConnections: Int,
    val maximumMessageBytes: Int,
    val startupActivitySink: BrokerStartupActivitySink,
    val installationRoot: Path,
    val configuration: ResolvedKastConfiguration,
)

internal enum class InstalledBrokerServerFailure {
    READINESS_REJECTED,
    KAST_QUALIFICATION_REJECTED,
    GRADLE_DEFINITION_REJECTED,
    CATALOG_REJECTED,
    CODEX_QUALIFICATION_REJECTED,
    THREAD_STORE_REJECTED,
    UPSTREAM_REJECTED,
    PUBLIC_SERVER_REJECTED,
}

internal sealed interface InstalledBrokerServerStart {
    data class Started(val server: InstalledBrokerServer) : InstalledBrokerServerStart

    data class Rejected(val failure: InstalledBrokerServerFailure) : InstalledBrokerServerStart
}

internal enum class InstalledBrokerServerTermination {
    CLOSED,
    UPSTREAM_EXITED,
}

internal class InstalledBrokerServer
private constructor(
    private val publicServer: KtorBrokerServer,
    private val upstream: ManagedCodexUpstream,
    private val readiness: OwnedBrokerServiceReadiness?,
) {
    private val closed = AtomicBoolean(false)
    private val termination = CompletableDeferred<InstalledBrokerServerTermination>()
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        monitorScope.launch {
            if (upstream.awaitTermination() == ManagedCodexUpstreamTermination.PROCESS_EXITED) {
                terminate(InstalledBrokerServerTermination.UPSTREAM_EXITED)
            }
        }
    }

    internal suspend fun awaitTermination(): InstalledBrokerServerTermination = termination.await()

    internal suspend fun close() = terminate(InstalledBrokerServerTermination.CLOSED)

    private suspend fun terminate(reason: InstalledBrokerServerTermination) {
        if (!closed.compareAndSet(false, true)) {
            termination.await()
            return
        }
        try {
            try {
                publicServer.close()
            } finally {
                try {
                    upstream.close()
                } finally {
                    readiness?.retire()
                }
            }
        } finally {
            termination.complete(reason)
            monitorScope.cancel()
        }
    }

    companion object {
        internal suspend fun start(options: InstalledBrokerServerOptions): InstalledBrokerServerStart {
            val activity = BrokerStartupActivityPublisher(options.startupActivitySink)
            var stage = BrokerStartupStage.READINESS_ACQUISITION
            activity.started(stage)
            val readiness =
                when (val beginning = options.readiness.begin()) {
                    BrokerReadinessBeginning.NotManaged -> null
                    is BrokerReadinessBeginning.Begun -> beginning.owned
                    BrokerReadinessBeginning.Rejected -> {
                        val failure = InstalledBrokerServerFailure.READINESS_REJECTED
                        activity.rejected(stage, BrokerStartupRejection.Server(failure))
                        return rejected(failure)
                    }
                }
            activity.completed(stage)
            fun rejectWithState(
                rejectedStage: BrokerStartupStage,
                failure: InstalledBrokerServerFailure,
                rejection: BrokerStartupRejection = BrokerStartupRejection.Server(failure),
            ): InstalledBrokerServerStart.Rejected {
                activity.rejected(rejectedStage, rejection)
                readiness?.reject(failure.serverFailure())
                return rejected(failure)
            }
            val host =
                when (val prepared = InstalledBrokerHost.prepare(options)) {
                    is InstalledBrokerHostStart.Prepared -> prepared
                    is InstalledBrokerHostStart.Rejected -> {
                        readiness?.reject(prepared.failure.serverFailure())
                        return rejected(prepared.failure)
                    }
                }
            val upstream = host.upstream
            stage = BrokerStartupStage.PUBLIC_SERVER
            activity.started(stage)
            val publicServer =
                when (val started = KtorBrokerServer.start(host.options)) {
                    is KtorBrokerServerStart.Started ->
                        started.server.also {
                            activity.completed(stage)
                        }
                    is KtorBrokerServerStart.Rejected -> {
                        upstream.close()
                        return rejectWithState(
                            stage,
                            InstalledBrokerServerFailure.PUBLIC_SERVER_REJECTED,
                        )
                    }
                }
            stage = BrokerStartupStage.READINESS_PUBLICATION
            activity.started(stage)
            if (readiness?.ready() == BrokerReadinessTransition.Rejected) {
                try {
                    publicServer.close()
                } finally {
                    upstream.close()
                }
                readiness.reject(BrokerServerFailure.READINESS_REJECTED)
                activity.rejected(
                    stage,
                    BrokerStartupRejection.Server(InstalledBrokerServerFailure.READINESS_REJECTED),
                )
                return rejected(InstalledBrokerServerFailure.READINESS_REJECTED)
            }
            activity.completed(stage)
            return InstalledBrokerServerStart.Started(InstalledBrokerServer(publicServer, upstream, readiness))
        }

        private fun rejected(failure: InstalledBrokerServerFailure): InstalledBrokerServerStart.Rejected =
            InstalledBrokerServerStart.Rejected(failure)
    }
}

internal sealed interface InstalledBrokerHostStart {
    data class Prepared(val options: KtorBrokerServerOptions, val upstream: ManagedCodexUpstream) :
        InstalledBrokerHostStart

    data class Rejected(val failure: InstalledBrokerServerFailure) : InstalledBrokerHostStart
}

internal object InstalledBrokerHost {
    suspend fun prepare(options: InstalledBrokerServerOptions): InstalledBrokerHostStart {
        val activity = BrokerStartupActivityPublisher(options.startupActivitySink)
        var stage = BrokerStartupStage.KAST_QUALIFICATION
        fun rejectHost(
            stage: BrokerStartupStage,
            failure: InstalledBrokerServerFailure,
            rejection: BrokerStartupRejection = BrokerStartupRejection.Server(failure),
        ): InstalledBrokerHostStart.Rejected {
            activity.rejected(stage, rejection)
            return InstalledBrokerHostStart.Rejected(failure)
        }
        stage = BrokerStartupStage.KAST_QUALIFICATION
        activity.started(stage)
        val kastQualification =
            when (val qualification = KastProviderQualifier.qualify(options.kastOptions)) {
                is KastProviderQualification.Qualified ->
                    qualification.also {
                        activity.completed(stage)
                    }
                is KastProviderQualification.Rejected ->
                    return rejectHost(
                        stage,
                        InstalledBrokerServerFailure.KAST_QUALIFICATION_REJECTED,
                    )
            }
        val kast = kastQualification.registration
        stage = BrokerStartupStage.GRADLE_DEFINITION
        activity.started(stage)
        val gradle =
            when (val definition = GradleProvider.registration()) {
                is Validation.Validated -> definition.value.also { activity.completed(stage) }
                is Validation.Rejected ->
                    return rejectHost(
                        stage,
                        InstalledBrokerServerFailure.GRADLE_DEFINITION_REJECTED,
                    )
            }
        stage = BrokerStartupStage.CATALOG
        activity.started(stage)
        val broker =
            when (
                val creation =
                    Broker.create(
                        listOf<ProviderDefinition>(gradle, kast),
                        options.limits,
                    )
            ) {
                is Validation.Validated -> creation.value.also { activity.completed(stage) }
                is Validation.Rejected ->
                    return rejectHost(
                        stage,
                        InstalledBrokerServerFailure.CATALOG_REJECTED,
                    )
            }
        stage = BrokerStartupStage.CODEX_QUALIFICATION
        activity.started(stage)
        val protocol =
            when (val qualification = CodexProtocolQualifier.qualify(options.protocolOptions)) {
                is CodexProtocolQualification.Qualified ->
                    qualification.also {
                        activity.completed(stage)
                    }
                is CodexProtocolQualification.Rejected ->
                    return rejectHost(
                        stage,
                        InstalledBrokerServerFailure.CODEX_QUALIFICATION_REJECTED,
                        BrokerStartupRejection.CodexQualification(qualification.failure),
                    )
            }
        stage = BrokerStartupStage.THREAD_STORE
        activity.started(stage)
        val store =
            when (val opened = FileThreadCatalogStore.open(options.threadStore)) {
                is FileThreadCatalogStoreOpen.Opened ->
                    opened.store.also {
                        activity.completed(stage)
                    }
                is FileThreadCatalogStoreOpen.Rejected ->
                    return rejectHost(
                        stage,
                        InstalledBrokerServerFailure.THREAD_STORE_REJECTED,
                    )
            }
        val enrollment =
            when (
                val read = WorkspaceEnrollmentStore(options.installationRoot.resolve("config/workspaces.json")).read()
            ) {
                is EnrollmentRead.Read -> read.enrollment
                is EnrollmentRead.Rejected ->
                    return rejectHost(stage, InstalledBrokerServerFailure.THREAD_STORE_REJECTED)
            }
        val owner =
            when (val admitted = BrokerInstallationState.admit(options.installationRoot)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejectHost(stage, InstalledBrokerServerFailure.THREAD_STORE_REJECTED)
            }
        stage = BrokerStartupStage.UPSTREAM
        activity.started(stage)
        val upstream =
            when (val started = ManagedCodexUpstream.start(options.upstreamOptions)) {
                is ManagedCodexUpstreamStart.Started ->
                    started.upstream.also {
                        activity.completed(stage)
                    }
                is ManagedCodexUpstreamStart.Rejected ->
                    return rejectHost(
                        stage,
                        InstalledBrokerServerFailure.UPSTREAM_REJECTED,
                        BrokerStartupRejection.Upstream(started.failure),
                    )
            }
        return InstalledBrokerHostStart.Prepared(
            KtorBrokerServerOptions(
                publicSocket = options.publicSocket,
                broker = broker,
                contracts = protocol.contracts,
                threadStore = store,
                upstream = upstream,
                maximumConnections = options.maximumConnections,
                maximumMessageBytes = options.maximumMessageBytes,
                activitySink = JsonLineBrokerInvocationActivitySink(System.err),
                sessionBootstrap = kastQualification.bootstrap,
                enrollment = enrollment,
                bindingOwner = owner,
                sessionActivitySink = io.github.amichne.kast.appserver.runtime.JsonLineSessionActivitySink(System.err),
                qualification =
                    io.github.amichne.kast.appserver.runtime.BrokerQualification(
                        options.readiness,
                        protocol,
                        broker.catalog.digest.value,
                    ),
                invocationJournal = options.threadStore.parent.resolve("invocations.json"),
            ),
            upstream,
        )
    }
}

class InstalledBrokerServerRunner(
    private val kastExecutable: Path,
    private val userHome: Path,
    private val environment: Map<String, String> = System.getenv(),
    private val workerEffects: InstalledWorkerEffects = InstalledWorkerEffects.Unavailable,
) : BrokerServerRunner {
    override fun serve(): BrokerServerRun {
        val options =
            when (val admission = InstalledCoordinatorConfiguration.admit(kastExecutable, userHome, environment)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return BrokerServerRun.Rejected(admission.failure.serverFailure())
            }
        return try {
            runBlocking {
                val running =
                    when (val started = InstalledCoordinator.start(options, workerEffects)) {
                        is InstalledCoordinatorStart.Started -> started.coordinator
                        is InstalledCoordinatorStart.Rejected ->
                            return@runBlocking BrokerServerRun.Rejected(started.failure)
                    }
                val hook = Thread({ runBlocking { running.close() } }, "kast-coordinator-shutdown")
                Runtime.getRuntime().addShutdownHook(hook)
                try {
                    running.awaitTermination()
                    BrokerServerRun.Stopped
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook)
                    } catch (_: IllegalStateException) {
                        /* Registered hook owns JVM shutdown. */
                    }
                    running.close()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            BrokerServerRun.Rejected(BrokerServerFailure.INTERRUPTED)
        }
    }
}

private fun InstalledBrokerServerConfigurationFailure.serverFailure(): BrokerServerFailure =
    when (this) {
        InstalledBrokerServerConfigurationFailure.KAST_EXECUTABLE_REJECTED ->
            BrokerServerFailure.KAST_EXECUTABLE_REJECTED
        InstalledBrokerServerConfigurationFailure.USER_HOME_REJECTED -> BrokerServerFailure.USER_HOME_REJECTED
        InstalledBrokerServerConfigurationFailure.CODEX_EXECUTABLE_REJECTED ->
            BrokerServerFailure.CODEX_EXECUTABLE_REJECTED
        InstalledBrokerServerConfigurationFailure.CODEX_HOME_REJECTED -> BrokerServerFailure.CODEX_HOME_REJECTED
        InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED ->
            BrokerServerFailure.STATE_DIRECTORY_REJECTED
        InstalledBrokerServerConfigurationFailure.SOCKET_PATH_REJECTED -> BrokerServerFailure.SOCKET_PATH_REJECTED
        InstalledBrokerServerConfigurationFailure.READINESS_REJECTED -> BrokerServerFailure.READINESS_REJECTED
        InstalledBrokerServerConfigurationFailure.PROVIDER_CONFIGURATION_REJECTED ->
            BrokerServerFailure.PROVIDER_CONFIGURATION_REJECTED
        InstalledBrokerServerConfigurationFailure.PROTOCOL_CONFIGURATION_REJECTED ->
            BrokerServerFailure.PROTOCOL_CONFIGURATION_REJECTED
        InstalledBrokerServerConfigurationFailure.APP_SERVER_DISABLED -> BrokerServerFailure.APP_SERVER_DISABLED
    }

private fun InstalledBrokerServerFailure.serverFailure(): BrokerServerFailure =
    when (this) {
        InstalledBrokerServerFailure.READINESS_REJECTED -> BrokerServerFailure.READINESS_REJECTED
        InstalledBrokerServerFailure.KAST_QUALIFICATION_REJECTED -> BrokerServerFailure.KAST_QUALIFICATION_REJECTED
        InstalledBrokerServerFailure.GRADLE_DEFINITION_REJECTED,
        InstalledBrokerServerFailure.CATALOG_REJECTED -> BrokerServerFailure.CATALOG_REJECTED
        InstalledBrokerServerFailure.CODEX_QUALIFICATION_REJECTED -> BrokerServerFailure.CODEX_QUALIFICATION_REJECTED
        InstalledBrokerServerFailure.THREAD_STORE_REJECTED -> BrokerServerFailure.THREAD_STORE_REJECTED
        InstalledBrokerServerFailure.UPSTREAM_REJECTED -> BrokerServerFailure.UPSTREAM_REJECTED
        InstalledBrokerServerFailure.PUBLIC_SERVER_REJECTED -> BrokerServerFailure.SERVER_REJECTED
    }

@JvmInline
internal value class BrokerServiceGeneration private constructor(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun fresh(): BrokerServiceGeneration = BrokerServiceGeneration(UUID.randomUUID())
    }
}

internal sealed interface BrokerServiceReadiness {
    data object Standalone : BrokerServiceReadiness

    class Managed
    private constructor(
        val identity: BrokerServiceIdentity,
        val instanceId: BrokerServiceGeneration,
        val path: Path,
    ) : BrokerServiceReadiness {
        companion object {
            internal fun admit(
                identity: String,
                path: Path,
                expected: Path,
            ): Managed? {
                val admittedIdentity = BrokerServiceIdentity.admit(identity) ?: return null
                if (path != expected || !path.isAbsolute || path.normalize() != path) return null
                return Managed(admittedIdentity, BrokerServiceGeneration.fresh(), path)
            }
        }
    }

    fun begin(): BrokerReadinessBeginning =
        when (this) {
            Standalone -> BrokerReadinessBeginning.NotManaged
            is Managed -> beginManagedReadiness(this)
        }

    companion object {
        internal fun admit(
            stateDirectory: Path,
            environment: Map<String, String>,
        ): BrokerServiceReadinessAdmission {
            val identity = environment["BROKER_SERVICE_IDENTITY"]
            val readinessPath = environment["BROKER_READINESS_FILE"]
            if (identity == null && readinessPath == null) {
                return BrokerServiceReadinessAdmission.Admitted(Standalone)
            }
            if (identity == null || readinessPath == null) return BrokerServiceReadinessAdmission.Rejected
            val path =
                try {
                    Path.of(readinessPath)
                } catch (_: RuntimeException) {
                    return BrokerServiceReadinessAdmission.Rejected
                }
            val managed =
                Managed.admit(
                    identity,
                    path,
                    stateDirectory.resolve("service-readiness.json"),
                ) ?: return BrokerServiceReadinessAdmission.Rejected
            return BrokerServiceReadinessAdmission.Admitted(managed)
        }
    }
}

internal sealed interface BrokerServiceReadinessAdmission {
    data class Admitted(val readiness: BrokerServiceReadiness) : BrokerServiceReadinessAdmission

    data object Rejected : BrokerServiceReadinessAdmission
}

internal sealed interface BrokerReadinessBeginning {
    data object NotManaged : BrokerReadinessBeginning

    data class Begun(val owned: OwnedBrokerServiceReadiness) : BrokerReadinessBeginning

    data object Rejected : BrokerReadinessBeginning
}

internal enum class BrokerReadinessTransition {
    Published,
    Rejected,
}

internal class OwnedBrokerServiceReadiness
private constructor(
    private val managed: BrokerServiceReadiness.Managed,
    private var state: BrokerServiceStateDocument,
    private var fileKey: Any,
) {
    @Synchronized
    internal fun ready(): BrokerReadinessTransition =
        transition(
            BrokerServiceStateDocument.Ready(
                BROKER_SERVICE_STATE_SCHEMA_VERSION,
                managed.identity.value,
                managed.instanceId.toString(),
                VENDORED_BROKER_VERSION,
            )
        )

    @Synchronized
    internal fun reject(failure: BrokerServerFailure): BrokerReadinessTransition =
        transition(
            BrokerServiceStateDocument.Rejected(
                BROKER_SERVICE_STATE_SCHEMA_VERSION,
                managed.identity.value,
                managed.instanceId.toString(),
                VENDORED_BROKER_VERSION,
                failure,
            )
        )

    private fun transition(next: BrokerServiceStateDocument): BrokerReadinessTransition {
        val parent = managed.path.parent ?: return BrokerReadinessTransition.Rejected
        val staging = parent.resolve(".${managed.path.fileName}.${UUID.randomUUID()}.partial")
        try {
            val original =
                Files.readAttributes(
                    managed.path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            if (
                !original.isRegularFile ||
                    original.isSymbolicLink ||
                    original.fileKey() != fileKey ||
                    readServiceState(managed.path) != state
            ) {
                return BrokerReadinessTransition.Rejected
            }
            writeServiceState(staging, next)
            val stagedKey = admittedServiceStateFileKey(staging, next) ?: return BrokerReadinessTransition.Rejected
            val current =
                Files.readAttributes(
                    managed.path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            if (!current.isRegularFile || current.isSymbolicLink || current.fileKey() != fileKey) {
                return BrokerReadinessTransition.Rejected
            }
            Files.move(
                staging,
                managed.path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            state = next
            fileKey = stagedKey
            return BrokerReadinessTransition.Published
        } catch (_: AtomicMoveNotSupportedException) {
            return BrokerReadinessTransition.Rejected
        } catch (_: SerializationException) {
            return BrokerReadinessTransition.Rejected
        } catch (_: IllegalArgumentException) {
            return BrokerReadinessTransition.Rejected
        } catch (_: IOException) {
            return BrokerReadinessTransition.Rejected
        } catch (_: SecurityException) {
            return BrokerReadinessTransition.Rejected
        } finally {
            try {
                Files.deleteIfExists(staging)
            } catch (_: IOException) {
                // A partial document is never authoritative.
            } catch (_: SecurityException) {
                // A partial document is never authoritative.
            }
        }
    }

    @Synchronized
    internal fun retire() {
        try {
            val attributes =
                Files.readAttributes(
                    managed.path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.fileKey() != fileKey) {
                return
            }
            if (readServiceState(managed.path) == state) {
                Files.delete(managed.path)
            }
        } catch (_: RuntimeException) {
            return
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }
    }

    companion object {
        internal fun committed(
            managed: BrokerServiceReadiness.Managed,
            state: BrokerServiceStateDocument,
            fileKey: Any,
        ): OwnedBrokerServiceReadiness = OwnedBrokerServiceReadiness(managed, state, fileKey)
    }
}

private fun beginManagedReadiness(managed: BrokerServiceReadiness.Managed): BrokerReadinessBeginning {
    val parent = managed.path.parent ?: return BrokerReadinessBeginning.Rejected
    val staging = parent.resolve(".${managed.path.fileName}.${managed.instanceId}.partial")
    val document =
        BrokerServiceStateDocument.Starting(
            BROKER_SERVICE_STATE_SCHEMA_VERSION,
            managed.identity.value,
            managed.instanceId.toString(),
            VENDORED_BROKER_VERSION,
        )
    try {
        Files.createDirectories(
            parent,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        if (parent.toRealPath() != parent) return BrokerReadinessBeginning.Rejected
        if (Files.exists(managed.path, LinkOption.NOFOLLOW_LINKS)) {
            return BrokerReadinessBeginning.Rejected
        }
        writeServiceState(staging, document)
        val stagedKey = admittedServiceStateFileKey(staging, document) ?: return BrokerReadinessBeginning.Rejected
        try {
            Files.move(staging, managed.path, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            return BrokerReadinessBeginning.Rejected
        }
        return BrokerReadinessBeginning.Begun(OwnedBrokerServiceReadiness.committed(managed, document, stagedKey))
    } catch (_: IOException) {
        return BrokerReadinessBeginning.Rejected
    } catch (_: SerializationException) {
        return BrokerReadinessBeginning.Rejected
    } catch (_: IllegalArgumentException) {
        return BrokerReadinessBeginning.Rejected
    } catch (_: SecurityException) {
        return BrokerReadinessBeginning.Rejected
    } finally {
        try {
            Files.deleteIfExists(staging)
        } catch (_: IOException) {
            // The publication result already fails closed; stale staging is never authoritative.
        } catch (_: SecurityException) {
            // The publication result already fails closed; stale staging is never authoritative.
        }
    }
}

@Serializable
internal sealed interface BrokerServiceStateDocument {
    val schemaVersion: Int
    val serviceIdentity: String
    val serviceInstanceId: String
    val brokerVersion: String

    @Serializable
    @SerialName("starting")
    data class Starting(
        override val schemaVersion: Int,
        override val serviceIdentity: String,
        override val serviceInstanceId: String,
        override val brokerVersion: String,
    ) : BrokerServiceStateDocument

    @Serializable
    @SerialName("ready")
    data class Ready(
        override val schemaVersion: Int,
        override val serviceIdentity: String,
        override val serviceInstanceId: String,
        override val brokerVersion: String,
    ) : BrokerServiceStateDocument

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        override val schemaVersion: Int,
        override val serviceIdentity: String,
        override val serviceInstanceId: String,
        override val brokerVersion: String,
        val failure: BrokerServerFailure,
    ) : BrokerServiceStateDocument
}

private fun writeServiceState(path: Path, document: BrokerServiceStateDocument) {
    val bytes =
        (BROKER_SERVICE_STATE_JSON.encodeToString(BrokerServiceStateDocument.serializer(), document) + "\n")
            .toByteArray(Charsets.UTF_8)
    FileChannel.open(
            path,
            setOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        .use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
}

private fun readServiceState(path: Path): BrokerServiceStateDocument =
    BROKER_SERVICE_STATE_JSON.decodeFromString(
        BrokerServiceStateDocument.serializer(),
        Files.readString(path),
    )

private fun admittedServiceStateFileKey(
    path: Path,
    expected: BrokerServiceStateDocument,
): Any? {
    val attributes =
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    if (!attributes.isRegularFile || attributes.isSymbolicLink) return null
    val key = attributes.fileKey() ?: return null
    return key.takeIf { readServiceState(path) == expected }
}

internal const val BROKER_SERVICE_STATE_SCHEMA_VERSION = 3

internal val BROKER_SERVICE_STATE_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    classDiscriminator = "state"
}
