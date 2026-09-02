package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerLimits
import io.github.amichne.kast.cli.broker.core.JsonLineBrokerInvocationActivitySink
import io.github.amichne.kast.cli.broker.core.ProviderDefinition
import io.github.amichne.kast.cli.broker.protocol.codex.CodexProtocolQualification
import io.github.amichne.kast.cli.broker.protocol.codex.CodexProtocolQualificationOptions
import io.github.amichne.kast.cli.broker.protocol.codex.CodexProtocolQualifier
import io.github.amichne.kast.cli.broker.protocol.FileThreadCatalogStore
import io.github.amichne.kast.cli.broker.protocol.FileThreadCatalogStoreOpen
import io.github.amichne.kast.cli.broker.provider.BrokerExecutable
import io.github.amichne.kast.cli.broker.provider.BrokerProcessExecutor
import io.github.amichne.kast.cli.broker.provider.GradleProvider
import io.github.amichne.kast.cli.broker.provider.JdkBrokerProcessExecutor
import io.github.amichne.kast.cli.broker.provider.KastProviderOptions
import io.github.amichne.kast.cli.broker.provider.KastProviderQualification
import io.github.amichne.kast.cli.broker.provider.KastProviderQualifier
import io.github.amichne.kast.cli.broker.runtime.BrokerSocketPath
import io.github.amichne.kast.cli.broker.runtime.CodexAppServerProcessLauncher
import io.github.amichne.kast.cli.broker.runtime.KtorBrokerServer
import io.github.amichne.kast.cli.broker.runtime.KtorBrokerServerOptions
import io.github.amichne.kast.cli.broker.runtime.KtorBrokerServerStart
import io.github.amichne.kast.cli.broker.runtime.ManagedCodexUpstream
import io.github.amichne.kast.cli.broker.runtime.ManagedCodexUpstreamOptions
import io.github.amichne.kast.cli.broker.runtime.ManagedCodexUpstreamStart
import io.github.amichne.kast.cli.broker.runtime.ManagedCodexUpstreamTermination
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
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
}

internal sealed interface InstalledBrokerServerConfiguration {
    data class Configured(val options: InstalledBrokerServerOptions) :
        InstalledBrokerServerConfiguration

    data class Rejected(val failure: InstalledBrokerServerConfigurationFailure) :
        InstalledBrokerServerConfiguration

    companion object {
        internal fun admit(
            kastExecutable: Path,
            userHome: Path,
            environment: Map<String, String>,
            processExecutor: BrokerProcessExecutor = JdkBrokerProcessExecutor,
            launcher: CodexAppServerProcessLauncher? = null,
        ): InstalledBrokerServerConfiguration {
            val canonicalUserHome = canonicalDirectory(userHome)
                ?: return rejected(InstalledBrokerServerConfigurationFailure.USER_HOME_REJECTED)
            val kast = when (val admission = BrokerExecutable.admit(kastExecutable)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return rejected(
                    InstalledBrokerServerConfigurationFailure.KAST_EXECUTABLE_REJECTED,
                )
            }
            val codexPath = when (val explicit = environment["CODEX_EXECUTABLE"]) {
                null -> resolveExecutable("codex", environment["PATH"].orEmpty())
                else -> absoluteNormalizedPath(explicit)
            } ?: return rejected(
                InstalledBrokerServerConfigurationFailure.CODEX_EXECUTABLE_REJECTED,
            )
            val codex = when (val admission = BrokerExecutable.admit(codexPath)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return rejected(
                    InstalledBrokerServerConfigurationFailure.CODEX_EXECUTABLE_REJECTED,
                )
            }
            val codexHomeCandidate = environment["CODEX_HOME"]?.let(::absoluteNormalizedPath)
                ?: canonicalUserHome.resolve(".codex")
            if (codexHomeCandidate == null) {
                return rejected(InstalledBrokerServerConfigurationFailure.CODEX_HOME_REJECTED)
            }
            val codexHome = createConfigurationDirectory(codexHomeCandidate)
                ?: return rejected(InstalledBrokerServerConfigurationFailure.CODEX_HOME_REJECTED)
            val stateDirectory = createBrokerOwnedDirectory(codexHome.resolve("broker"))
                ?: return rejected(
                    InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED,
                )
            val protocolTemporary = createBrokerOwnedDirectory(stateDirectory.resolve("protocol"))
                ?: return rejected(
                    InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED,
                )
            val publicParent = createBrokerOwnedDirectory(
                codexHome.resolve("app-server-control"),
            )
                ?: return rejected(
                    InstalledBrokerServerConfigurationFailure.STATE_DIRECTORY_REJECTED,
                )
            val publicSocket = when (
                val admission = BrokerSocketPath.admit(
                    publicParent.resolve("app-server-control.sock"),
                )
            ) {
                is Validation.Validated -> admission.value
                is Validation.Rejected -> return rejected(
                    InstalledBrokerServerConfigurationFailure.SOCKET_PATH_REJECTED,
                )
            }
            val privateSocket = when (
                val admission = BrokerSocketPath.admit(stateDirectory.resolve("upstream.sock"))
            ) {
                is Validation.Validated -> admission.value
                is Validation.Rejected -> return rejected(
                    InstalledBrokerServerConfigurationFailure.SOCKET_PATH_REJECTED,
                )
            }
            if (publicSocket == privateSocket) {
                return rejected(InstalledBrokerServerConfigurationFailure.SOCKET_PATH_REJECTED)
            }
            val readiness = when (
                val admitted = BrokerServiceReadiness.admit(stateDirectory, environment)
            ) {
                is BrokerServiceReadinessAdmission.Admitted -> admitted.readiness
                BrokerServiceReadinessAdmission.Rejected -> return rejected(
                    InstalledBrokerServerConfigurationFailure.READINESS_REJECTED,
                )
            }
            val kastOptions = when (
                val admission = KastProviderOptions.admit(
                    kast.path,
                    canonicalUserHome,
                    processExecutor,
                )
            ) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return rejected(
                    InstalledBrokerServerConfigurationFailure.PROVIDER_CONFIGURATION_REJECTED,
                )
            }
            val protocolOptions = when (
                val admission = CodexProtocolQualificationOptions.admit(
                    codex.path,
                    codexHome,
                    protocolTemporary,
                    processExecutor,
                    maximumSchemaBytes = 32 * 1_024 * 1_024,
                    maximumSchemaFiles = 2_048,
                    timeoutMillis = 30_000,
                )
            ) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return rejected(
                    InstalledBrokerServerConfigurationFailure.PROTOCOL_CONFIGURATION_REJECTED,
                )
            }
            val upstreamOptions = if (launcher == null) {
                ManagedCodexUpstreamOptions(
                    codex,
                    codexHome,
                    privateSocket,
                    maximumMessageBytes = MAXIMUM_MESSAGE_BYTES,
                    startupTimeoutMillis = UPSTREAM_STARTUP_TIMEOUT_MILLIS,
                )
            } else {
                ManagedCodexUpstreamOptions(
                    codex,
                    codexHome,
                    privateSocket,
                    launcher,
                    MAXIMUM_MESSAGE_BYTES,
                    UPSTREAM_STARTUP_TIMEOUT_MILLIS,
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
                    maximumConnections = 8,
                    maximumMessageBytes = MAXIMUM_MESSAGE_BYTES,
                ),
            )
        }

        private fun createConfigurationDirectory(path: Path): Path? = try {
            Files.createDirectories(
                path,
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------"),
                ),
            )
            canonicalDirectory(path)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        private fun createBrokerOwnedDirectory(path: Path): Path? = try {
            Files.createDirectories(
                path,
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------"),
                ),
            )
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            canonicalDirectory(path)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        private fun canonicalDirectory(path: Path): Path? = try {
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
            return directories.asSequence()
                .mapNotNull(::absoluteNormalizedPath)
                .map { directory -> directory.resolve(name) }
                .firstOrNull { candidate ->
                    BrokerExecutable.admit(candidate) is Refinement.Refined
                }
        }

        private fun absoluteNormalizedPath(raw: String): Path? = try {
            Path.of(raw).takeIf { path -> path.isAbsolute && path.normalize() == path }
        } catch (_: RuntimeException) {
            null
        }

        private fun rejected(
            failure: InstalledBrokerServerConfigurationFailure,
        ): Rejected = Rejected(failure)

        private const val MAXIMUM_MESSAGE_BYTES = 64 * 1_024 * 1_024
        private const val UPSTREAM_STARTUP_TIMEOUT_MILLIS = 10_000L
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

internal enum class InstalledBrokerServerTermination { CLOSED, UPSTREAM_EXITED }

internal class InstalledBrokerServer private constructor(
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
        if (!closed.compareAndSet(false, true)) return
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
            val readiness = when (val beginning = options.readiness.begin()) {
                BrokerReadinessBeginning.NotManaged -> null
                is BrokerReadinessBeginning.Begun -> beginning.owned
                BrokerReadinessBeginning.Rejected -> return rejected(
                    InstalledBrokerServerFailure.READINESS_REJECTED,
                )
            }
            fun rejectWithState(
                failure: InstalledBrokerServerFailure,
            ): InstalledBrokerServerStart.Rejected {
                readiness?.reject(failure.serverFailure())
                return rejected(failure)
            }
            val kast = when (val qualification = KastProviderQualifier.qualify(options.kastOptions)) {
                is KastProviderQualification.Qualified -> qualification.registration
                is KastProviderQualification.Rejected -> return rejectWithState(
                    InstalledBrokerServerFailure.KAST_QUALIFICATION_REJECTED,
                )
            }
            val gradle = when (val definition = GradleProvider.registration()) {
                is Validation.Validated -> definition.value
                is Validation.Rejected -> return rejectWithState(
                    InstalledBrokerServerFailure.GRADLE_DEFINITION_REJECTED,
                )
            }
            val broker = when (
                val creation = Broker.create(
                    listOf<ProviderDefinition>(gradle, kast),
                    options.limits,
                )
            ) {
                is Validation.Validated -> creation.value
                is Validation.Rejected -> return rejectWithState(
                    InstalledBrokerServerFailure.CATALOG_REJECTED,
                )
            }
            val protocol = when (
                val qualification = CodexProtocolQualifier.qualify(options.protocolOptions)
            ) {
                is CodexProtocolQualification.Qualified -> qualification
                is CodexProtocolQualification.Rejected -> return rejectWithState(
                    InstalledBrokerServerFailure.CODEX_QUALIFICATION_REJECTED,
                )
            }
            val store = when (val opened = FileThreadCatalogStore.open(options.threadStore)) {
                is FileThreadCatalogStoreOpen.Opened -> opened.store
                is FileThreadCatalogStoreOpen.Rejected -> return rejectWithState(
                    InstalledBrokerServerFailure.THREAD_STORE_REJECTED,
                )
            }
            val upstream = when (
                val started = ManagedCodexUpstream.start(options.upstreamOptions)
            ) {
                is ManagedCodexUpstreamStart.Started -> started.upstream
                is ManagedCodexUpstreamStart.Rejected -> return rejectWithState(
                    InstalledBrokerServerFailure.UPSTREAM_REJECTED,
                )
            }
            val publicServer = when (
                val started = KtorBrokerServer.start(
                    KtorBrokerServerOptions(
                        publicSocket = options.publicSocket,
                        broker = broker,
                        contracts = protocol.contracts,
                        threadStore = store,
                        upstream = upstream,
                        maximumConnections = options.maximumConnections,
                        maximumMessageBytes = options.maximumMessageBytes,
                        activitySink = JsonLineBrokerInvocationActivitySink(System.err),
                    ),
                )
            ) {
                is KtorBrokerServerStart.Started -> started.server
                is KtorBrokerServerStart.Rejected -> {
                    upstream.close()
                    return rejectWithState(InstalledBrokerServerFailure.PUBLIC_SERVER_REJECTED)
                }
            }
            if (readiness?.ready() == BrokerReadinessTransition.Rejected) {
                try {
                    publicServer.close()
                } finally {
                    upstream.close()
                }
                readiness.reject(BrokerServerFailure.READINESS_REJECTED)
                return rejected(InstalledBrokerServerFailure.READINESS_REJECTED)
            }
            return InstalledBrokerServerStart.Started(
                InstalledBrokerServer(publicServer, upstream, readiness),
            )
        }

        private fun rejected(
            failure: InstalledBrokerServerFailure,
        ): InstalledBrokerServerStart.Rejected = InstalledBrokerServerStart.Rejected(failure)
    }
}

internal class InstalledBrokerServerRunner(
    private val kastExecutable: Path,
    private val userHome: Path,
    private val environment: Map<String, String> = System.getenv(),
) : BrokerServerRunner {
    override fun serve(): BrokerServerRun {
        val configuration = when (
            val admitted = InstalledBrokerServerConfiguration.admit(
                kastExecutable,
                userHome,
                environment,
            )
        ) {
            is InstalledBrokerServerConfiguration.Configured -> admitted.options
            is InstalledBrokerServerConfiguration.Rejected -> return BrokerServerRun.Rejected(
                BrokerServerFailure.CONFIGURATION_REJECTED,
            )
        }
        return try {
            runBlocking<BrokerServerRun> {
                val running = when (val started = InstalledBrokerServer.start(configuration)) {
                    is InstalledBrokerServerStart.Started -> started.server
                    is InstalledBrokerServerStart.Rejected -> return@runBlocking BrokerServerRun
                        .Rejected(started.failure.serverFailure())
                }
                val hook = Thread(
                    { runBlocking { running.close() } },
                    "kast-broker-shutdown",
                )
                Runtime.getRuntime().addShutdownHook(hook)
                try {
                    when (running.awaitTermination()) {
                        InstalledBrokerServerTermination.CLOSED -> BrokerServerRun.Stopped
                        InstalledBrokerServerTermination.UPSTREAM_EXITED ->
                            BrokerServerRun.Rejected(BrokerServerFailure.UPSTREAM_REJECTED)
                    }
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook)
                    } catch (_: IllegalStateException) {
                        // The JVM has already entered shutdown; the registered hook owns closure.
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

private fun InstalledBrokerServerFailure.serverFailure(): BrokerServerFailure = when (this) {
    InstalledBrokerServerFailure.READINESS_REJECTED -> BrokerServerFailure.READINESS_REJECTED
    InstalledBrokerServerFailure.KAST_QUALIFICATION_REJECTED ->
        BrokerServerFailure.KAST_QUALIFICATION_REJECTED
    InstalledBrokerServerFailure.GRADLE_DEFINITION_REJECTED,
    InstalledBrokerServerFailure.CATALOG_REJECTED,
        -> BrokerServerFailure.CATALOG_REJECTED
    InstalledBrokerServerFailure.CODEX_QUALIFICATION_REJECTED ->
        BrokerServerFailure.CODEX_QUALIFICATION_REJECTED
    InstalledBrokerServerFailure.THREAD_STORE_REJECTED ->
        BrokerServerFailure.THREAD_STORE_REJECTED
    InstalledBrokerServerFailure.UPSTREAM_REJECTED -> BrokerServerFailure.UPSTREAM_REJECTED
    InstalledBrokerServerFailure.PUBLIC_SERVER_REJECTED -> BrokerServerFailure.SERVER_REJECTED
}

internal sealed interface BrokerServiceReadiness {
    data object Standalone : BrokerServiceReadiness

    class Managed private constructor(
        val identity: BrokerServiceIdentity,
        val instanceId: UUID,
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
                return Managed(admittedIdentity, UUID.randomUUID(), path)
            }
        }
    }

    fun begin(): BrokerReadinessBeginning = when (this) {
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
            val path = try {
                Path.of(readinessPath)
            } catch (_: RuntimeException) {
                return BrokerServiceReadinessAdmission.Rejected
            }
            val managed = Managed.admit(
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

internal enum class BrokerReadinessTransition { Published, Rejected }

internal class OwnedBrokerServiceReadiness private constructor(
    private val managed: BrokerServiceReadiness.Managed,
    private var state: BrokerServiceStateDocument,
    private var fileKey: Any,
) {
    @Synchronized
    internal fun ready(): BrokerReadinessTransition = transition(
        BrokerServiceStateDocument.Ready(
            BROKER_SERVICE_STATE_SCHEMA_VERSION,
            managed.identity.value,
            managed.instanceId.toString(),
            VENDORED_BROKER_VERSION,
        ),
    )

    @Synchronized
    internal fun reject(failure: BrokerServerFailure): BrokerReadinessTransition = transition(
        BrokerServiceStateDocument.Rejected(
            BROKER_SERVICE_STATE_SCHEMA_VERSION,
            managed.identity.value,
            managed.instanceId.toString(),
            VENDORED_BROKER_VERSION,
            failure,
        ),
    )

    private fun transition(next: BrokerServiceStateDocument): BrokerReadinessTransition {
        val parent = managed.path.parent ?: return BrokerReadinessTransition.Rejected
        val staging = parent.resolve(".${managed.path.fileName}.${UUID.randomUUID()}.partial")
        try {
            val original = Files.readAttributes(
                managed.path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (
                !original.isRegularFile || original.isSymbolicLink ||
                original.fileKey() != fileKey || readServiceState(managed.path) != state
            ) {
                return BrokerReadinessTransition.Rejected
            }
            writeServiceState(staging, next)
            val stagedKey = admittedServiceStateFileKey(staging, next)
                ?: return BrokerReadinessTransition.Rejected
            val current = Files.readAttributes(
                managed.path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (
                !current.isRegularFile || current.isSymbolicLink ||
                current.fileKey() != fileKey
            ) {
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
            val attributes = Files.readAttributes(
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

private fun beginManagedReadiness(
    managed: BrokerServiceReadiness.Managed,
): BrokerReadinessBeginning {
    val parent = managed.path.parent ?: return BrokerReadinessBeginning.Rejected
    val staging = parent.resolve(".${managed.path.fileName}.${managed.instanceId}.partial")
    val document = BrokerServiceStateDocument.Starting(
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
        val stagedKey = admittedServiceStateFileKey(staging, document)
            ?: return BrokerReadinessBeginning.Rejected
        try {
            Files.move(staging, managed.path, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            return BrokerReadinessBeginning.Rejected
        }
        return BrokerReadinessBeginning.Begun(
            OwnedBrokerServiceReadiness.committed(managed, document, stagedKey),
        )
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
    val bytes = (
        BROKER_SERVICE_STATE_JSON.encodeToString(BrokerServiceStateDocument.serializer(), document) +
            "\n"
        ).toByteArray(Charsets.UTF_8)
    FileChannel.open(
        path,
        setOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
    ).use { channel ->
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
    val attributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )
    if (!attributes.isRegularFile || attributes.isSymbolicLink) return null
    val key = attributes.fileKey() ?: return null
    return key.takeIf { readServiceState(path) == expected }
}

internal const val BROKER_SERVICE_STATE_SCHEMA_VERSION = 2

internal val BROKER_SERVICE_STATE_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    classDiscriminator = "state"
}
