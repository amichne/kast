package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.cli.LaunchctlExitContract
import io.github.amichne.kast.cli.LaunchctlInvocation
import io.github.amichne.kast.cli.LaunchctlInvoker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.ConnectException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun interface BrokerSocketProbe {
    fun probe(path: Path): BrokerSocketReachability
}

internal enum class BrokerSocketReachability { REACHABLE, UNREACHABLE, REJECTED }

internal fun interface BrokerSocketPathObserver {
    fun observe(path: Path): BrokerSocketPathObservation
}

/** Finite Unix socket-path evidence established without following symbolic links. */
internal sealed interface BrokerSocketPathObservation {
    /** The leaf or its immediate parent is absent beneath an admitted canonical directory. */
    data object Absent : BrokerSocketPathObservation

    data object Socket : BrokerSocketPathObservation
    data object WrongType : BrokerSocketPathObservation
    data object Rejected : BrokerSocketPathObservation
}

internal object JdkBrokerSocketPathObserver : BrokerSocketPathObserver {
    override fun observe(path: Path): BrokerSocketPathObservation {
        if (!path.isAbsolute || path.normalize() != path) {
            return BrokerSocketPathObservation.Rejected
        }
        val parent = path.parent ?: return BrokerSocketPathObservation.Rejected
        when (admitParent(parent)) {
            BrokerSocketParentAdmission.Absent -> return BrokerSocketPathObservation.Absent
            BrokerSocketParentAdmission.Rejected -> return BrokerSocketPathObservation.Rejected
            BrokerSocketParentAdmission.Admitted -> Unit
        }
        val mode = try {
            Files.getAttribute(path, UNIX_MODE_ATTRIBUTE, LinkOption.NOFOLLOW_LINKS) as? Int
                ?: return BrokerSocketPathObservation.Rejected
        } catch (_: NoSuchFileException) {
            return BrokerSocketPathObservation.Absent
        } catch (_: IOException) {
            return BrokerSocketPathObservation.Rejected
        } catch (_: UnsupportedOperationException) {
            return BrokerSocketPathObservation.Rejected
        } catch (_: SecurityException) {
            return BrokerSocketPathObservation.Rejected
        }
        return if (mode and UNIX_FILE_TYPE_MASK == UNIX_SOCKET_FILE_TYPE) {
            BrokerSocketPathObservation.Socket
        } else {
            BrokerSocketPathObservation.WrongType
        }
    }

    private fun admitParent(parent: Path): BrokerSocketParentAdmission = try {
        if (
            parent.toRealPath() == parent &&
            Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
        ) {
            BrokerSocketParentAdmission.Admitted
        } else {
            BrokerSocketParentAdmission.Rejected
        }
    } catch (_: NoSuchFileException) {
        if (Files.isSymbolicLink(parent)) {
            BrokerSocketParentAdmission.Rejected
        } else {
            val existingParent = parent.parent ?: return BrokerSocketParentAdmission.Rejected
            if (canonicalDirectory(existingParent)) {
                BrokerSocketParentAdmission.Absent
            } else {
                BrokerSocketParentAdmission.Rejected
            }
        }
    } catch (_: IOException) {
        BrokerSocketParentAdmission.Rejected
    } catch (_: SecurityException) {
        BrokerSocketParentAdmission.Rejected
    }

    private fun canonicalDirectory(path: Path): Boolean = try {
        path.toRealPath() == path && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private const val UNIX_MODE_ATTRIBUTE = "unix:mode"
    private const val UNIX_FILE_TYPE_MASK = 0xF000
    private const val UNIX_SOCKET_FILE_TYPE = 0xC000
}

private enum class BrokerSocketParentAdmission { Admitted, Absent, Rejected }

internal object JdkBrokerSocketProbe : BrokerSocketProbe {
    private val pathObserver: BrokerSocketPathObserver = JdkBrokerSocketPathObserver

    override fun probe(path: Path): BrokerSocketReachability {
        when (pathObserver.observe(path)) {
            BrokerSocketPathObservation.Absent -> return BrokerSocketReachability.UNREACHABLE
            BrokerSocketPathObservation.WrongType,
            BrokerSocketPathObservation.Rejected,
                -> return BrokerSocketReachability.REJECTED
            BrokerSocketPathObservation.Socket -> Unit
        }
        return try {
            runBlocking(Dispatchers.IO) { exchangeInitialize(path) }
        } catch (failure: Exception) {
            if (failure.hasCause<ConnectException>()) {
                BrokerSocketReachability.UNREACHABLE
            } else {
                when (pathObserver.observe(path)) {
                    BrokerSocketPathObservation.Absent -> BrokerSocketReachability.UNREACHABLE
                    BrokerSocketPathObservation.Socket,
                    BrokerSocketPathObservation.WrongType,
                    BrokerSocketPathObservation.Rejected,
                        -> BrokerSocketReachability.REJECTED
                }
            }
        }
    }

    private suspend fun exchangeInitialize(path: Path): BrokerSocketReachability {
        val client = HttpClient(CIO) {
            install(WebSockets) { maxFrameSize = MAXIMUM_READINESS_FRAME_BYTES }
        }
        return try {
            withTimeoutOrNull(READINESS_EXCHANGE_TIMEOUT_MILLIS) {
                val session = client.webSocketSession {
                    url("ws://localhost/rpc")
                    unixSocket(path.toString())
                }
                try {
                    session.send(READINESS_INITIALIZE_REQUEST)
                    while (true) {
                        val frame = session.incoming.receiveCatching().getOrNull()
                            ?: return@withTimeoutOrNull BrokerSocketReachability.REJECTED
                        val message = (frame as? Frame.Text)?.readText()
                            ?: return@withTimeoutOrNull BrokerSocketReachability.REJECTED
                        val document = parseObject(message)
                            ?: return@withTimeoutOrNull BrokerSocketReachability.REJECTED
                        val responseId = (document["id"] as? JsonPrimitive)?.contentOrNull
                        if (responseId != READINESS_REQUEST_ID) continue
                        return@withTimeoutOrNull if (
                            document["method"] == null &&
                            document.containsKey("result") &&
                            !document.containsKey("error")
                        ) {
                            BrokerSocketReachability.REACHABLE
                        } else {
                            BrokerSocketReachability.REJECTED
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    BrokerSocketReachability.REJECTED
                } finally {
                    session.close()
                }
            } ?: BrokerSocketReachability.REJECTED
        } finally {
            client.close()
        }
    }

    private fun parseObject(message: String): JsonObject? = try {
        Json.parseToJsonElement(message) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private inline fun <reified Failure : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { current -> current.cause }
            .any { current -> current is Failure }

    private const val READINESS_REQUEST_ID = "kast-readiness-v1"
    private const val READINESS_EXCHANGE_TIMEOUT_MILLIS = 3_000L
    private const val MAXIMUM_READINESS_FRAME_BYTES = 1024L * 1024L
    private val READINESS_INITIALIZE_REQUEST =
        """{"id":"$READINESS_REQUEST_ID","method":"initialize","params":{"clientInfo":{"name":"kast-readiness","version":"$VENDORED_BROKER_VERSION"},"capabilities":{"experimentalApi":true}}}"""
}

internal fun interface BrokerServiceSleeper {
    fun sleep(): BrokerServiceSleep
}

internal enum class BrokerServiceSleep { CONTINUE, INTERRUPTED }

private object ThreadBrokerServiceSleeper : BrokerServiceSleeper {
    override fun sleep(): BrokerServiceSleep = try {
        Thread.sleep(POLL_MILLIS)
        BrokerServiceSleep.CONTINUE
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        BrokerServiceSleep.INTERRUPTED
    }
}

/** Product-level deadline proof: every enclosing budget strictly contains its child phases. */
internal object BrokerServiceStartupBudgets {
    // Kast version + schema (20s), Codex version + schema (60s), upstream UDS (10s).
    val admittedChildPhasesNanos: Long = TimeUnit.SECONDS.toNanos(90L)
    val hostTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(120L)
    val retirementTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(10L)
    val lockTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(180L)

    init {
        require(admittedChildPhasesNanos < hostTimeoutNanos)
        require(hostTimeoutNanos + retirementTimeoutNanos < lockTimeoutNanos)
    }
}

private sealed interface BrokerReadinessObservation {
    data object Missing : BrokerReadinessObservation
    data object Invalid : BrokerReadinessObservation

    sealed interface Published : BrokerReadinessObservation {
        val identity: BrokerServiceIdentity
        val brokerVersion: String
        val instanceId: UUID
    }

    data class Starting(
        override val identity: BrokerServiceIdentity,
        override val brokerVersion: String,
        override val instanceId: UUID,
    ) : Published

    data class Ready(
        override val identity: BrokerServiceIdentity,
        override val brokerVersion: String,
        override val instanceId: UUID,
    ) : Published

    data class StartupRejected(
        override val identity: BrokerServiceIdentity,
        override val brokerVersion: String,
        override val instanceId: UUID,
        val failure: BrokerServerFailure,
    ) : Published
}

/** launchd-owned persistent service boundary for one canonical CODEX_HOME namespace. */
internal class MacOsPersistentBrokerServiceHost(
    private val launchctl: LaunchctlInvoker = JdkBrokerLaunchctlInvoker,
    private val socketProbe: BrokerSocketProbe = JdkBrokerSocketProbe,
    private val sleeper: BrokerServiceSleeper = ThreadBrokerServiceSleeper,
    private val startupTimeoutNanos: Long = DEFAULT_STARTUP_TIMEOUT_NANOS,
    private val retirementTimeoutNanos: Long = DEFAULT_RETIREMENT_TIMEOUT_NANOS,
) : PersistentBrokerServiceHost {
    override fun ensure(
        command: BrokerServiceLaunchCommand,
    ): PersistentBrokerServiceAdmission {
        when (prepareStateDirectory(command)) {
            BrokerStateDirectoryPreparation.Prepared -> Unit
            BrokerStateDirectoryPreparation.Rejected -> return rejected(
                PersistentBrokerServiceFailure.STATE_DIRECTORY_REJECTED,
            )
        }
        return when (
            val execution = BrokerServiceStartLock.withAcquired(command.serviceLock) {
                ensureExclusively(command)
            }
        ) {
            is BrokerServiceLockExecution.Executed -> execution.admission
            BrokerServiceLockExecution.Interrupted -> rejected(
                PersistentBrokerServiceFailure.INTERRUPTED,
            )
            BrokerServiceLockExecution.Rejected -> rejected(
                PersistentBrokerServiceFailure.SERVICE_LOCK_REJECTED,
            )
            BrokerServiceLockExecution.TimedOut -> rejected(
                PersistentBrokerServiceFailure.STARTUP_TIMED_OUT,
            )
        }
    }

    private fun ensureExclusively(
        command: BrokerServiceLaunchCommand,
    ): PersistentBrokerServiceAdmission = when (observeService(command.serviceLabel)) {
        BrokerLaunchdServiceObservation.Present -> ensurePresent(command)
        BrokerLaunchdServiceObservation.Absent -> reconcileAbsent(command)
        BrokerLaunchdServiceObservation.Interrupted -> rejected(
            PersistentBrokerServiceFailure.INTERRUPTED,
        )
        BrokerLaunchdServiceObservation.Rejected -> rejected(
            PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED,
        )
        BrokerLaunchdServiceObservation.TimedOut -> rejected(
            PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
        )
    }

    private fun ensurePresent(
        command: BrokerServiceLaunchCommand,
    ): PersistentBrokerServiceAdmission = when (val readiness = observeReadiness(command)) {
        BrokerReadinessObservation.Missing -> awaitReadiness(command)
        BrokerReadinessObservation.Invalid -> rejected(
            PersistentBrokerServiceFailure.READINESS_REJECTED,
        )
        is BrokerReadinessObservation.Starting -> if (readiness.isCurrent(command)) {
            awaitReadiness(command)
        } else {
            retireAndSubmit(command, readiness)
        }
        is BrokerReadinessObservation.Ready -> if (readiness.isCurrent(command)) {
            when (socketProbe.probe(command.publicSocket)) {
                BrokerSocketReachability.REACHABLE -> PersistentBrokerServiceAdmission.Ready
                BrokerSocketReachability.UNREACHABLE -> retireAndSubmit(command, readiness)
                BrokerSocketReachability.REJECTED -> retireAndSubmit(command, readiness)
            }
        } else {
            retireAndSubmit(command, readiness)
        }
        is BrokerReadinessObservation.StartupRejected -> if (readiness.isCurrent(command)) {
            retireRejectedStartup(command, readiness)
        } else {
            retireAndSubmit(command, readiness)
        }
    }

    private fun reconcileAbsent(
        command: BrokerServiceLaunchCommand,
    ): PersistentBrokerServiceAdmission = when (val readiness = observeReadiness(command)) {
        BrokerReadinessObservation.Missing -> when (socketProbe.probe(command.publicSocket)) {
            BrokerSocketReachability.UNREACHABLE -> submitAndAwait(command)
            BrokerSocketReachability.REACHABLE -> rejected(
                PersistentBrokerServiceFailure.PUBLIC_SOCKET_OWNED,
            )
            BrokerSocketReachability.REJECTED -> rejected(
                PersistentBrokerServiceFailure.SOCKET_PROBE_REJECTED,
            )
        }
        BrokerReadinessObservation.Invalid -> rejected(
            PersistentBrokerServiceFailure.READINESS_REJECTED,
        )
        is BrokerReadinessObservation.Published -> when (socketProbe.probe(command.publicSocket)) {
            BrokerSocketReachability.REACHABLE -> rejected(
                PersistentBrokerServiceFailure.PUBLIC_SOCKET_OWNED,
            )
            BrokerSocketReachability.REJECTED -> rejected(
                PersistentBrokerServiceFailure.SOCKET_PROBE_REJECTED,
            )
            BrokerSocketReachability.UNREACHABLE -> when (
                retireReadiness(command, readiness)
            ) {
                BrokerReadinessRetirement.Retired -> if (
                    readiness is BrokerReadinessObservation.StartupRejected &&
                    readiness.isCurrent(command)
                ) {
                    rejected(readiness.failure.persistentServiceFailure())
                } else {
                    submitAndAwait(command)
                }
                BrokerReadinessRetirement.Rejected -> rejected(
                    PersistentBrokerServiceFailure.READINESS_REJECTED,
                )
            }
        }
    }

    private fun retireAndSubmit(
        command: BrokerServiceLaunchCommand,
        readiness: BrokerReadinessObservation.Published,
    ): PersistentBrokerServiceAdmission = when (retireService(command.serviceLabel)) {
        BrokerLaunchdServiceRetirement.Retired -> when (
            awaitRetirement(command, readiness)
        ) {
            BrokerLaunchdServiceRetirement.Retired -> submitAndAwait(command)
            BrokerLaunchdServiceRetirement.Interrupted -> rejected(
                PersistentBrokerServiceFailure.INTERRUPTED,
            )
            BrokerLaunchdServiceRetirement.Rejected -> rejected(
                PersistentBrokerServiceFailure.SERVICE_RETIREMENT_REJECTED,
            )
            BrokerLaunchdServiceRetirement.TimedOut -> rejected(
                PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
            )
        }
        BrokerLaunchdServiceRetirement.Interrupted -> rejected(
            PersistentBrokerServiceFailure.INTERRUPTED,
        )
        BrokerLaunchdServiceRetirement.Rejected -> rejected(
            PersistentBrokerServiceFailure.SERVICE_RETIREMENT_REJECTED,
        )
        BrokerLaunchdServiceRetirement.TimedOut -> rejected(
            PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
        )
    }

    private fun retireRejectedStartup(
        command: BrokerServiceLaunchCommand,
        rejection: BrokerReadinessObservation.StartupRejected,
    ): PersistentBrokerServiceAdmission = when (observeService(command.serviceLabel)) {
        BrokerLaunchdServiceObservation.Absent -> when (
            retireReadiness(command, rejection)
        ) {
            BrokerReadinessRetirement.Retired -> rejected(
                rejection.failure.persistentServiceFailure(),
            )
            BrokerReadinessRetirement.Rejected -> rejected(
                PersistentBrokerServiceFailure.READINESS_REJECTED,
            )
        }
        BrokerLaunchdServiceObservation.Present -> when (
            retireService(command.serviceLabel)
        ) {
            BrokerLaunchdServiceRetirement.Retired -> when (
                awaitRetirement(command, rejection)
            ) {
                BrokerLaunchdServiceRetirement.Retired -> rejected(
                    rejection.failure.persistentServiceFailure(),
                )
                BrokerLaunchdServiceRetirement.Interrupted -> rejected(
                    PersistentBrokerServiceFailure.INTERRUPTED,
                )
                BrokerLaunchdServiceRetirement.Rejected -> rejected(
                    PersistentBrokerServiceFailure.SERVICE_RETIREMENT_REJECTED,
                )
                BrokerLaunchdServiceRetirement.TimedOut -> rejected(
                    PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
                )
            }
            BrokerLaunchdServiceRetirement.Interrupted -> rejected(
                PersistentBrokerServiceFailure.INTERRUPTED,
            )
            BrokerLaunchdServiceRetirement.Rejected -> rejected(
                PersistentBrokerServiceFailure.SERVICE_RETIREMENT_REJECTED,
            )
            BrokerLaunchdServiceRetirement.TimedOut -> rejected(
                PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
            )
        }
        BrokerLaunchdServiceObservation.Interrupted -> rejected(
            PersistentBrokerServiceFailure.INTERRUPTED,
        )
        BrokerLaunchdServiceObservation.Rejected -> rejected(
            PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED,
        )
        BrokerLaunchdServiceObservation.TimedOut -> rejected(
            PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
        )
    }

    private fun submitAndAwait(
        command: BrokerServiceLaunchCommand,
    ): PersistentBrokerServiceAdmission = when (submit(command)) {
        BrokerLaunchdServiceSubmission.Submitted,
        BrokerLaunchdServiceSubmission.Raced,
            -> awaitReadiness(command)
        BrokerLaunchdServiceSubmission.Interrupted -> rejected(
            PersistentBrokerServiceFailure.INTERRUPTED,
        )
        BrokerLaunchdServiceSubmission.Rejected -> rejected(
            PersistentBrokerServiceFailure.SERVICE_SUBMISSION_REJECTED,
        )
        BrokerLaunchdServiceSubmission.TimedOut -> rejected(
            PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
        )
    }

    private fun awaitReadiness(
        command: BrokerServiceLaunchCommand,
    ): PersistentBrokerServiceAdmission {
        val deadline = System.nanoTime() + startupTimeoutNanos
        while (System.nanoTime() < deadline) {
            val readiness = observeReadiness(command)
            if (readiness is BrokerReadinessObservation.StartupRejected) {
                if (!readiness.isCurrent(command)) {
                    return rejected(PersistentBrokerServiceFailure.READINESS_REJECTED)
                }
                return retireRejectedStartup(command, readiness)
            }
            val service = observeService(command.serviceLabel)
            when (service) {
                BrokerLaunchdServiceObservation.Present -> Unit
                BrokerLaunchdServiceObservation.Absent -> return rejected(
                    PersistentBrokerServiceFailure.SERVICE_SUBMISSION_REJECTED,
                )
                BrokerLaunchdServiceObservation.Interrupted -> return rejected(
                    PersistentBrokerServiceFailure.INTERRUPTED,
                )
                BrokerLaunchdServiceObservation.Rejected -> return rejected(
                    PersistentBrokerServiceFailure.SERVICE_OBSERVATION_REJECTED,
                )
                BrokerLaunchdServiceObservation.TimedOut -> return rejected(
                    PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
                )
            }
            when (readiness) {
                BrokerReadinessObservation.Missing -> Unit
                BrokerReadinessObservation.Invalid -> return rejected(
                    PersistentBrokerServiceFailure.READINESS_REJECTED,
                )
                is BrokerReadinessObservation.Starting -> if (!readiness.isCurrent(command)) {
                    return rejected(PersistentBrokerServiceFailure.READINESS_REJECTED)
                }
                is BrokerReadinessObservation.Ready -> {
                    if (!readiness.isCurrent(command)) {
                        return rejected(PersistentBrokerServiceFailure.READINESS_REJECTED)
                    }
                    when (socketProbe.probe(command.publicSocket)) {
                        BrokerSocketReachability.REACHABLE ->
                            return PersistentBrokerServiceAdmission.Ready
                        BrokerSocketReachability.UNREACHABLE -> Unit
                        BrokerSocketReachability.REJECTED -> return rejected(
                            PersistentBrokerServiceFailure.SOCKET_PROBE_REJECTED,
                        )
                    }
                }
                is BrokerReadinessObservation.StartupRejected ->
                    return retireRejectedStartup(command, readiness)
            }
            if (sleeper.sleep() == BrokerServiceSleep.INTERRUPTED) {
                return rejected(PersistentBrokerServiceFailure.INTERRUPTED)
            }
        }
        return retireTimedOutStartup(command)
    }

    private fun retireTimedOutStartup(
        command: BrokerServiceLaunchCommand,
    ): PersistentBrokerServiceAdmission {
        val retiring = when (val readiness = observeReadiness(command)) {
            BrokerReadinessObservation.Invalid -> return rejected(
                PersistentBrokerServiceFailure.READINESS_REJECTED,
            )
            is BrokerReadinessObservation.StartupRejected -> if (readiness.isCurrent(command)) {
                return retireRejectedStartup(command, readiness)
            } else {
                return rejected(PersistentBrokerServiceFailure.READINESS_REJECTED)
            }
            BrokerReadinessObservation.Missing,
            is BrokerReadinessObservation.Starting,
            is BrokerReadinessObservation.Ready,
                -> readiness
        }
        return when (retireService(command.serviceLabel)) {
        BrokerLaunchdServiceRetirement.Retired -> when (
            awaitRetirement(command, retiring)
        ) {
            BrokerLaunchdServiceRetirement.Retired -> rejected(
                PersistentBrokerServiceFailure.STARTUP_TIMED_OUT,
            )
            BrokerLaunchdServiceRetirement.Interrupted -> rejected(
                PersistentBrokerServiceFailure.INTERRUPTED,
            )
            BrokerLaunchdServiceRetirement.Rejected -> rejected(
                PersistentBrokerServiceFailure.SERVICE_RETIREMENT_REJECTED,
            )
            BrokerLaunchdServiceRetirement.TimedOut -> rejected(
                PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
            )
        }
        BrokerLaunchdServiceRetirement.Interrupted -> rejected(
            PersistentBrokerServiceFailure.INTERRUPTED,
        )
        BrokerLaunchdServiceRetirement.Rejected -> rejected(
            PersistentBrokerServiceFailure.SERVICE_RETIREMENT_REJECTED,
        )
        BrokerLaunchdServiceRetirement.TimedOut -> rejected(
            PersistentBrokerServiceFailure.LAUNCHCTL_TIMED_OUT,
        )
        }
    }

    private fun submit(command: BrokerServiceLaunchCommand): BrokerLaunchdServiceSubmission {
        val invocation = launchctl.invoke(
            listOf(
                LAUNCHCTL_EXECUTABLE,
                "submit",
                "-l",
                command.serviceLabel.value,
                "-o",
                command.serviceLog.toString(),
                "-e",
                command.serviceLog.toString(),
                "--",
                ENV_EXECUTABLE,
                "-i",
                "HOME=${command.userHome}",
                "PATH=${command.codex.parent}:${command.kast.parent}:/usr/bin:/bin:/usr/sbin:/sbin",
                "JAVA_HOME=${command.javaHome}",
                "KAST_OPTS=${command.jvmUserHomeOption.value}",
                "CODEX_HOME=${command.codexHome}",
                "CODEX_EXECUTABLE=${command.codex}",
                "BROKER_SERVICE_IDENTITY=${command.identity.value}",
                "BROKER_READINESS_FILE=${command.readinessFile}",
                command.kast.toString(),
                "broker",
                "serve",
            ),
            LaunchctlExitContract.CompletionOnly,
        )
        return when (invocation) {
            LaunchctlInvocation.Completed -> BrokerLaunchdServiceSubmission.Submitted
            LaunchctlInvocation.Interrupted -> BrokerLaunchdServiceSubmission.Interrupted
            LaunchctlInvocation.TimedOut -> BrokerLaunchdServiceSubmission.TimedOut
            LaunchctlInvocation.Absent,
            LaunchctlInvocation.Rejected,
                -> when (observeService(command.serviceLabel)) {
                    BrokerLaunchdServiceObservation.Present ->
                        BrokerLaunchdServiceSubmission.Raced
                    BrokerLaunchdServiceObservation.Interrupted ->
                        BrokerLaunchdServiceSubmission.Interrupted
                    BrokerLaunchdServiceObservation.TimedOut ->
                        BrokerLaunchdServiceSubmission.TimedOut
                    BrokerLaunchdServiceObservation.Absent,
                    BrokerLaunchdServiceObservation.Rejected,
                        -> BrokerLaunchdServiceSubmission.Rejected
                }
        }
    }

    private fun observeService(
        label: BrokerLaunchdServiceLabel,
    ): BrokerLaunchdServiceObservation = when (
        launchctl.invoke(
            listOf(LAUNCHCTL_EXECUTABLE, "list", label.value),
            LaunchctlExitContract.CompletionOrAbsent(LAUNCHCTL_SERVICE_NOT_FOUND),
        )
    ) {
        LaunchctlInvocation.Completed -> BrokerLaunchdServiceObservation.Present
        LaunchctlInvocation.Absent -> BrokerLaunchdServiceObservation.Absent
        LaunchctlInvocation.Interrupted -> BrokerLaunchdServiceObservation.Interrupted
        LaunchctlInvocation.Rejected -> BrokerLaunchdServiceObservation.Rejected
        LaunchctlInvocation.TimedOut -> BrokerLaunchdServiceObservation.TimedOut
    }

    private fun retireService(
        label: BrokerLaunchdServiceLabel,
    ): BrokerLaunchdServiceRetirement = when (
        launchctl.invoke(
            listOf(LAUNCHCTL_EXECUTABLE, "remove", label.value),
            LaunchctlExitContract.CompletionOnly,
        )
    ) {
        LaunchctlInvocation.Completed -> BrokerLaunchdServiceRetirement.Retired
        LaunchctlInvocation.Interrupted -> BrokerLaunchdServiceRetirement.Interrupted
        LaunchctlInvocation.TimedOut -> BrokerLaunchdServiceRetirement.TimedOut
        LaunchctlInvocation.Absent,
        LaunchctlInvocation.Rejected,
            -> BrokerLaunchdServiceRetirement.Rejected
    }

    /** Waits for child-owned readiness retirement after socket close, fencing any replacement. */
    private fun awaitRetirement(
        command: BrokerServiceLaunchCommand,
        retiring: BrokerReadinessObservation,
    ): BrokerLaunchdServiceRetirement {
        val deadline = System.nanoTime() + retirementTimeoutNanos
        while (System.nanoTime() < deadline) {
            when (observeService(command.serviceLabel)) {
                BrokerLaunchdServiceObservation.Absent -> Unit
                BrokerLaunchdServiceObservation.Present -> {
                    if (sleeper.sleep() == BrokerServiceSleep.INTERRUPTED) {
                        return BrokerLaunchdServiceRetirement.Interrupted
                    }
                    continue
                }
                BrokerLaunchdServiceObservation.Interrupted ->
                    return BrokerLaunchdServiceRetirement.Interrupted
                BrokerLaunchdServiceObservation.Rejected ->
                    return BrokerLaunchdServiceRetirement.Rejected
                BrokerLaunchdServiceObservation.TimedOut ->
                    return BrokerLaunchdServiceRetirement.TimedOut
            }
            when (val readiness = observeReadiness(command)) {
                BrokerReadinessObservation.Missing -> when (
                    socketProbe.probe(command.publicSocket)
                ) {
                    BrokerSocketReachability.UNREACHABLE ->
                        return BrokerLaunchdServiceRetirement.Retired
                    BrokerSocketReachability.REACHABLE -> Unit
                    BrokerSocketReachability.REJECTED ->
                        return BrokerLaunchdServiceRetirement.Rejected
                }
                BrokerReadinessObservation.Invalid ->
                    return BrokerLaunchdServiceRetirement.Rejected
                is BrokerReadinessObservation.Published -> {
                    if (readiness != retiring) {
                        return BrokerLaunchdServiceRetirement.Rejected
                    }
                    when (socketProbe.probe(command.publicSocket)) {
                        BrokerSocketReachability.REACHABLE -> Unit
                        BrokerSocketReachability.REJECTED ->
                            return BrokerLaunchdServiceRetirement.Rejected
                        BrokerSocketReachability.UNREACHABLE -> when (
                            retireReadiness(command, readiness)
                        ) {
                            BrokerReadinessRetirement.Retired ->
                                return BrokerLaunchdServiceRetirement.Retired
                            BrokerReadinessRetirement.Rejected ->
                                return BrokerLaunchdServiceRetirement.Rejected
                        }
                    }
                }
            }
            if (sleeper.sleep() == BrokerServiceSleep.INTERRUPTED) {
                return BrokerLaunchdServiceRetirement.Interrupted
            }
        }
        return BrokerLaunchdServiceRetirement.Rejected
    }

    private fun prepareStateDirectory(
        command: BrokerServiceLaunchCommand,
    ): BrokerStateDirectoryPreparation = try {
        Files.createDirectories(
            command.stateDirectory,
            PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------"),
            ),
        )
        Files.setPosixFilePermissions(
            command.stateDirectory,
            PosixFilePermissions.fromString("rwx------"),
        )
        val exact = command.stateDirectory.toRealPath() == command.stateDirectory &&
            Files.isDirectory(command.stateDirectory, LinkOption.NOFOLLOW_LINKS)
        val pathsAdmitted = listOf(
            command.readinessFile,
            command.serviceLog,
            command.serviceLock,
        ).none(Files::isSymbolicLink)
        if (exact && pathsAdmitted) {
            BrokerStateDirectoryPreparation.Prepared
        } else {
            BrokerStateDirectoryPreparation.Rejected
        }
    } catch (_: IOException) {
        BrokerStateDirectoryPreparation.Rejected
    } catch (_: SecurityException) {
        BrokerStateDirectoryPreparation.Rejected
    }

    private fun observeReadiness(
        command: BrokerServiceLaunchCommand,
    ): BrokerReadinessObservation {
        val attributes = try {
            Files.readAttributes(
                command.readinessFile,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            return BrokerReadinessObservation.Missing
        } catch (_: IOException) {
            return BrokerReadinessObservation.Invalid
        } catch (_: SecurityException) {
            return BrokerReadinessObservation.Invalid
        }
        if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            return BrokerReadinessObservation.Invalid
        }
        val raw = try {
            Files.readString(command.readinessFile)
        } catch (_: IOException) {
            return BrokerReadinessObservation.Invalid
        } catch (_: SecurityException) {
            return BrokerReadinessObservation.Invalid
        }
        val document = try {
            BROKER_SERVICE_STATE_JSON.decodeFromString(
                BrokerServiceStateDocument.serializer(),
                raw,
            )
        } catch (_: SerializationException) {
            return BrokerReadinessObservation.Invalid
        } catch (_: IllegalArgumentException) {
            return BrokerReadinessObservation.Invalid
        }
        val identity = BrokerServiceIdentity.admit(document.serviceIdentity)
            ?: return BrokerReadinessObservation.Invalid
        val instanceId = try {
            UUID.fromString(document.serviceInstanceId)
        } catch (_: IllegalArgumentException) {
            return BrokerReadinessObservation.Invalid
        }
        if (
            document.schemaVersion != BROKER_SERVICE_STATE_SCHEMA_VERSION ||
            !BROKER_VERSION.matches(document.brokerVersion) ||
            instanceId.toString() != document.serviceInstanceId
        ) {
            return BrokerReadinessObservation.Invalid
        }
        return when (document) {
            is BrokerServiceStateDocument.Starting -> BrokerReadinessObservation.Starting(
                identity,
                document.brokerVersion,
                instanceId,
            )
            is BrokerServiceStateDocument.Ready -> BrokerReadinessObservation.Ready(
                identity,
                document.brokerVersion,
                instanceId,
            )
            is BrokerServiceStateDocument.Rejected -> BrokerReadinessObservation.StartupRejected(
                identity,
                document.brokerVersion,
                instanceId,
                document.failure,
            )
        }
    }

    private fun retireReadiness(
        command: BrokerServiceLaunchCommand,
        expected: BrokerReadinessObservation.Published,
    ): BrokerReadinessRetirement {
        val original = try {
            Files.readAttributes(
                command.readinessFile,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            return BrokerReadinessRetirement.Rejected
        } catch (_: SecurityException) {
            return BrokerReadinessRetirement.Rejected
        }
        val originalKey = original.fileKey() ?: return BrokerReadinessRetirement.Rejected
        if (!original.isRegularFile || original.isSymbolicLink) {
            return BrokerReadinessRetirement.Rejected
        }
        if (observeReadiness(command) != expected) return BrokerReadinessRetirement.Rejected
        val current = try {
            Files.readAttributes(
                command.readinessFile,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            return BrokerReadinessRetirement.Rejected
        } catch (_: SecurityException) {
            return BrokerReadinessRetirement.Rejected
        }
        if (
            !current.isRegularFile || current.isSymbolicLink ||
            current.fileKey() != originalKey
        ) {
            return BrokerReadinessRetirement.Rejected
        }
        return try {
            Files.delete(command.readinessFile)
            BrokerReadinessRetirement.Retired
        } catch (_: IOException) {
            BrokerReadinessRetirement.Rejected
        } catch (_: SecurityException) {
            BrokerReadinessRetirement.Rejected
        }
    }

    private fun BrokerReadinessObservation.Published.isCurrent(
        command: BrokerServiceLaunchCommand,
    ): Boolean = identity == command.identity && brokerVersion == VENDORED_BROKER_VERSION

    private fun BrokerServerFailure.persistentServiceFailure(): PersistentBrokerServiceFailure =
        when (this) {
            BrokerServerFailure.UNAVAILABLE -> PersistentBrokerServiceFailure.UNAVAILABLE
            BrokerServerFailure.CONFIGURATION_REJECTED ->
                PersistentBrokerServiceFailure.CONFIGURATION_REJECTED
            BrokerServerFailure.KAST_QUALIFICATION_REJECTED ->
                PersistentBrokerServiceFailure.KAST_QUALIFICATION_REJECTED
            BrokerServerFailure.CATALOG_REJECTED ->
                PersistentBrokerServiceFailure.CATALOG_REJECTED
            BrokerServerFailure.CODEX_QUALIFICATION_REJECTED ->
                PersistentBrokerServiceFailure.CODEX_QUALIFICATION_REJECTED
            BrokerServerFailure.THREAD_STORE_REJECTED ->
                PersistentBrokerServiceFailure.THREAD_STORE_REJECTED
            BrokerServerFailure.UPSTREAM_REJECTED ->
                PersistentBrokerServiceFailure.UPSTREAM_REJECTED
            BrokerServerFailure.SERVER_REJECTED ->
                PersistentBrokerServiceFailure.SERVER_REJECTED
            BrokerServerFailure.READINESS_REJECTED ->
                PersistentBrokerServiceFailure.READINESS_REJECTED
            BrokerServerFailure.INTERRUPTED -> PersistentBrokerServiceFailure.INTERRUPTED
        }

    private fun rejected(
        failure: PersistentBrokerServiceFailure,
    ): PersistentBrokerServiceAdmission.Rejected =
        PersistentBrokerServiceAdmission.Rejected(failure)

    private companion object {
        val BROKER_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        val DEFAULT_STARTUP_TIMEOUT_NANOS: Long = BrokerServiceStartupBudgets.hostTimeoutNanos
        val DEFAULT_RETIREMENT_TIMEOUT_NANOS: Long =
            BrokerServiceStartupBudgets.retirementTimeoutNanos
        const val LAUNCHCTL_EXECUTABLE = "/bin/launchctl"
        const val ENV_EXECUTABLE = "/usr/bin/env"
        const val LAUNCHCTL_SERVICE_NOT_FOUND = 113
    }
}

private enum class BrokerStateDirectoryPreparation { Prepared, Rejected }

private sealed interface BrokerServiceLockExecution {
    data class Executed(
        val admission: PersistentBrokerServiceAdmission,
    ) : BrokerServiceLockExecution

    data object Interrupted : BrokerServiceLockExecution
    data object Rejected : BrokerServiceLockExecution
    data object TimedOut : BrokerServiceLockExecution
}

private object BrokerServiceStartLock {
    fun withAcquired(
        path: Path,
        operation: () -> PersistentBrokerServiceAdmission,
    ): BrokerServiceLockExecution {
        if (Files.isSymbolicLink(path)) return BrokerServiceLockExecution.Rejected
        val channel = try {
            FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            return BrokerServiceLockExecution.Rejected
        } catch (_: SecurityException) {
            return BrokerServiceLockExecution.Rejected
        }
        return channel.use { opened ->
            val deadline = System.nanoTime() + LOCK_TIMEOUT_NANOS
            while (System.nanoTime() < deadline) {
                val lock = try {
                    opened.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } catch (_: IOException) {
                    return@use BrokerServiceLockExecution.Rejected
                }
                if (lock != null) {
                    return@use lock.use {
                        BrokerServiceLockExecution.Executed(operation())
                    }
                }
                try {
                    Thread.sleep(LOCK_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@use BrokerServiceLockExecution.Interrupted
                }
            }
            BrokerServiceLockExecution.TimedOut
        }
    }

    private val LOCK_TIMEOUT_NANOS = BrokerServiceStartupBudgets.lockTimeoutNanos
    private const val LOCK_POLL_MILLIS = 25L
}

private enum class BrokerLaunchdServiceObservation {
    Present,
    Absent,
    Rejected,
    Interrupted,
    TimedOut,
}

private enum class BrokerLaunchdServiceSubmission {
    Submitted,
    Raced,
    Rejected,
    Interrupted,
    TimedOut,
}

private enum class BrokerLaunchdServiceRetirement { Retired, Rejected, Interrupted, TimedOut }

private enum class BrokerReadinessRetirement { Retired, Rejected }

private object JdkBrokerLaunchctlInvoker : LaunchctlInvoker {
    override fun invoke(
        arguments: List<String>,
        exitContract: LaunchctlExitContract,
    ): LaunchctlInvocation {
        if (System.getProperty("os.name") != "Mac OS X") return LaunchctlInvocation.Rejected
        val process = try {
            ProcessBuilder(arguments)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: IOException) {
            return LaunchctlInvocation.Rejected
        } catch (_: SecurityException) {
            return LaunchctlInvocation.Rejected
        }
        val completed = try {
            process.waitFor(LAUNCHCTL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return LaunchctlInvocation.Interrupted
        } catch (_: SecurityException) {
            process.destroyForcibly()
            return LaunchctlInvocation.Rejected
        }
        if (!completed) {
            process.destroyForcibly()
            return LaunchctlInvocation.TimedOut
        }
        val exitCode = process.exitValue()
        if (exitCode == 0) return LaunchctlInvocation.Completed
        return when (exitContract) {
            LaunchctlExitContract.CompletionOnly -> LaunchctlInvocation.Rejected
            is LaunchctlExitContract.CompletionOrAbsent -> if (
                exitCode == exitContract.absentExitCode
            ) {
                LaunchctlInvocation.Absent
            } else {
                LaunchctlInvocation.Rejected
            }
        }
    }

    private const val LAUNCHCTL_TIMEOUT_SECONDS = 5L
}

private const val POLL_MILLIS = 50L
