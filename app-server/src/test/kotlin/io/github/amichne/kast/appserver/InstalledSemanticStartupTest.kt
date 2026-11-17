package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** Startup admission of the installed semantic coordinator for independent concurrent clients. */
class InstalledSemanticStartupTest {
    @Test
    fun `a concurrent client joins the in-progress generation without a competing submission`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                val readiness = InstalledSemanticCoordinator.readinessPath(root, root)
                val starting = CountDownLatch(1)
                val release = CountDownLatch(1)
                val sink = BrokerStartupActivitySink { activity ->
                    if (activity == BrokerStartupActivity.Started(BrokerStartupStage.INSTALLATION_OWNERSHIP)) {
                        starting.countDown()
                        release.await(LATCH_SECONDS, TimeUnit.SECONDS)
                    }
                    BrokerStartupActivityPublication.PUBLISHED
                }
                val service =
                    async(Dispatchers.IO) {
                        InstalledCoordinator.start(options(root, kast, sink), semantic = unexpected)
                    }
                try {
                    assertTrue(starting.await(LATCH_SECONDS, TimeUnit.SECONDS))
                    val inProgress = readServiceState(readiness) as BrokerServiceStateDocument.Starting
                    assertRecordsCurrentStartupProcess(readiness)
                    val pauses = AtomicInteger()
                    val joined =
                        InstalledSemanticCoordinator.connect(
                            root,
                            readiness,
                            currentExecutable(),
                            submit = { fail("A client must not submit a competing service during startup") },
                            budgetMillis = TimeUnit.SECONDS.toMillis(LATCH_SECONDS),
                        ) {
                            pauses.incrementAndGet()
                            release.countDown()
                            delay(POLL_MILLIS)
                        }
                    val ready = readServiceState(readiness) as BrokerServiceStateDocument.Ready
                    assertEquals(inProgress.serviceInstanceId, (joined as Refinement.Refined).value.generation)
                    assertEquals(inProgress.serviceInstanceId, ready.serviceInstanceId)
                    assertEquals(ProcessHandle.current().pid(), joined.value.processId)
                    assertTrue(pauses.get() >= 1)
                } finally {
                    release.countDown()
                    (service.await() as? InstalledCoordinatorStart.Started)?.coordinator?.close()
                }
            }
        }

    @Test
    fun `an owned stale publication is retired under the start lock and replaced by a fresh generation`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                val command = semanticLaunchFixture(root)
                val stale = writeReady(root, exitedPeer(command.executable))
                val submissions = AtomicInteger()
                var started: InstalledCoordinatorStart? = null
                val connected =
                    InstalledSemanticCoordinator.connect(
                        root,
                        command.readiness,
                        currentExecutable(),
                        submit = {
                            BrokerServiceStartLock.submitSemantic(command) {
                                submissions.incrementAndGet()
                                assertTrue(Files.notExists(command.readiness))
                                started = runBlocking {
                                    InstalledCoordinator.start(options(root, kast), semantic = unexpected)
                                }
                                Refinement.Refined(Unit)
                            }
                        },
                        budgetMillis = TimeUnit.SECONDS.toMillis(LATCH_SECONDS),
                    ) {
                        delay(POLL_MILLIS)
                    }
                try {
                    val generation = (connected as Refinement.Refined).value.generation
                    assertEquals(1, submissions.get())
                    assertNotEquals(stale.serviceInstanceId, generation)
                    assertEquals(
                        generation,
                        (readServiceState(command.readiness) as BrokerServiceStateDocument.Ready).serviceInstanceId,
                    )
                } finally {
                    (started as? InstalledCoordinatorStart.Started)?.coordinator?.close()
                }
            }
        }

    @Test
    fun `an installation replacement fence rejects before stale retirement or submission`() =
        withCoordinatorPayload { root, _ ->
            runBlocking {
                val command = semanticLaunchFixture(root)
                writeReady(root, exitedPeer(command.executable))
                val retained = Files.readAllBytes(command.readiness)
                Files.createFile(root.resolve(".lifecycle-transition.json"))
                assertEquals(
                    Refinement.Rejected(SemanticCoordinatorStartupFailure.InstallationFenced),
                    connectWithoutSubmission(root, command),
                )
                assertEquals(
                    Refinement.Rejected(PersistentBrokerServiceFailure.DISABLED),
                    BrokerServiceStartLock.submitSemantic(command) { fail("A fenced installation admitted a service") },
                )
                assertArrayEquals(retained, Files.readAllBytes(command.readiness))
            }
        }

    @Test
    fun `a live foreign incarnation or foreign identity rejects without retirement or a competing service`() =
        withCoordinatorPayload { root, _ ->
            runBlocking {
                val command = semanticLaunchFixture(root)
                val current = ProcessHandle.current()
                val live =
                    SemanticCoordinatorPeer(
                        current.pid(),
                        current.info().startInstant().orElseThrow().toString(),
                        command.executable.toString(),
                        checkNotNull(CoordinatorExecutableRevision.capture(command.executable)),
                        "foreign",
                        "sha256:" + "1".repeat(64),
                    )
                for (document in listOf(live to identity(root), exitedPeer(command.executable) to FOREIGN_IDENTITY)) {
                    writeReady(root, document.first, document.second)
                    val retained = Files.readAllBytes(command.readiness)
                    assertEquals(
                        Refinement.Rejected(SemanticCoordinatorStartupFailure.OwnershipRejected),
                        connectWithoutSubmission(root, command),
                    )
                    assertEquals(
                        Refinement.Rejected(PersistentBrokerServiceFailure.READINESS_REJECTED),
                        BrokerServiceStartLock.submitSemantic(command) {
                            fail("Ambiguous ownership admitted a service")
                        },
                    )
                    assertArrayEquals(retained, Files.readAllBytes(command.readiness))
                    Files.delete(command.readiness)
                }
            }
        }

    @Test
    fun `a replaced in-progress generation rejects instead of joining its successor`() =
        withCoordinatorPayload { root, _ ->
            runBlocking {
                val command = semanticLaunchFixture(root)
                writeStarting(root, UUID.randomUUID())
                var replaced = false
                val outcome =
                    InstalledSemanticCoordinator.connect(
                        root,
                        command.readiness,
                        command.executable,
                        submit = { fail("A client must not submit while a generation is starting") },
                        budgetMillis = TimeUnit.SECONDS.toMillis(LATCH_SECONDS),
                    ) {
                        if (!replaced) {
                            replaced = true
                            replaceStarting(root, UUID.randomUUID())
                        }
                    }
                assertEquals(Refinement.Rejected(SemanticCoordinatorStartupFailure.GenerationReplaced), outcome)
            }
        }

    @Test
    fun `a live or legacy in-progress generation that never becomes ready exhausts the explicit startup budget`() =
        withCoordinatorPayload { root, _ ->
            runTest {
                val command = semanticLaunchFixture(root)
                // A record from before startup evidence, and one whose recorded startup process is still live.
                for (process in listOf(null, ServiceProcessIncarnation.capture(ProcessHandle.current()))) {
                    writeServiceState(command.readiness, starting(root, UUID.randomUUID(), process))
                    val retained = Files.readAllBytes(command.readiness)
                    val outcome =
                        InstalledSemanticCoordinator.connect(
                            root,
                            command.readiness,
                            command.executable,
                            submit = { fail("A client must not submit while a generation is starting") },
                            budgetMillis = POLL_MILLIS * 4,
                        ) {
                            delay(POLL_MILLIS)
                        }
                    assertEquals(Refinement.Rejected(SemanticCoordinatorStartupFailure.StartupTimedOut), outcome)
                    assertEquals(
                        Refinement.Refined(Unit),
                        BrokerServiceStartLock.submitSemantic(command) { fail("A starting generation was replaced") },
                    )
                    assertArrayEquals(retained, Files.readAllBytes(command.readiness))
                    Files.delete(command.readiness)
                }
            }
        }
}

/** Recovery of an owned startup whose recorded process incarnation provably exited. */
class AbandonedSemanticStartupTest {
    @Test
    fun `an abandoned startup whose recorded process exited is retired and replaced by a fresh generation`() =
        withCoordinatorPayload { root, kast ->
            runBlocking {
                val command = semanticLaunchFixture(root)
                val current = ProcessHandle.current()
                // A reaped process, and a live process at the recorded pid whose start differs (a reused pid).
                val abandoned =
                    listOf(exitedProcess(), ServiceProcessIncarnation(current.pid(), "1970-01-01T00:00:00Z"))
                for (process in abandoned) {
                    val generation = UUID.randomUUID()
                    writeServiceState(command.readiness, starting(root, generation, process))
                    assertEquals(
                        Refinement.Rejected(WorkerControlFailure.RECOVERY_REQUIRED),
                        InstalledSemanticCoordinator.observe(root, command.readiness, command.executable),
                    )
                    val submissions = AtomicInteger()
                    var started: InstalledCoordinatorStart? = null
                    val connected =
                        InstalledSemanticCoordinator.connect(
                            root,
                            command.readiness,
                            currentExecutable(),
                            submit = {
                                BrokerServiceStartLock.submitSemantic(command) {
                                    submissions.incrementAndGet()
                                    assertTrue(Files.notExists(command.readiness))
                                    started = runBlocking {
                                        InstalledCoordinator.start(options(root, kast), semantic = unexpected)
                                    }
                                    Refinement.Refined(Unit)
                                }
                            },
                            budgetMillis = TimeUnit.SECONDS.toMillis(LATCH_SECONDS),
                        ) {
                            delay(POLL_MILLIS)
                        }
                    try {
                        val replacement = (connected as Refinement.Refined).value.generation
                        assertEquals(1, submissions.get())
                        assertNotEquals(generation.toString(), replacement)
                    } finally {
                        (started as? InstalledCoordinatorStart.Started)?.coordinator?.close()
                    }
                }
            }
        }

    @Test
    fun `an unproven startup incarnation rejects without retirement or a competing service`() =
        withCoordinatorPayload { root, _ ->
            runBlocking {
                val command = semanticLaunchFixture(root)
                // A non-positive pid, and a vanished pid whose recorded start is not an admitted instant.
                val unproven =
                    listOf(
                        ServiceProcessIncarnation(0, "1970-01-01T00:00:00Z"),
                        exitedProcess().copy(startedAt = "not-an-instant"),
                    )
                for (process in unproven) {
                    writeServiceState(command.readiness, starting(root, UUID.randomUUID(), process))
                    assertRejectedWithoutEffects(root, command)
                    Files.delete(command.readiness)
                }
            }
        }

    @Test
    fun `an exited startup never unlinks a foreign listener or FIFO at the unrecorded socket path`() =
        withCoordinatorPayload { root, _ ->
            runBlocking {
                val command = semanticLaunchFixture(root)
                val socket = Files.createDirectories(root.resolve("state/run")).resolve("c.sock")
                Files.setPosixFilePermissions(socket.parent, PosixFilePermissions.fromString("rwx------"))
                writeServiceState(command.readiness, starting(root, UUID.randomUUID(), exitedProcess()))
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                    listener.bind(UnixDomainSocketAddress.of(socket))
                    assertRejectedWithoutEffects(root, command, socket)
                }
                Files.delete(socket)
                assertEquals(0, ProcessBuilder("/usr/bin/mkfifo", "-m", "600", socket.toString()).start().waitFor())
                assertRejectedWithoutEffects(root, command, socket)
            }
        }

    /** Ownership rejects at inspection, under the start lock, and in fenced retirement, leaving every file intact. */
    private suspend fun assertRejectedWithoutEffects(root: Path, command: SemanticServiceLaunch, socket: Path? = null) {
        val retained = Files.readAllBytes(command.readiness)
        val key = socket?.let { Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() }
        assertEquals(
            Refinement.Rejected(SemanticCoordinatorStartupFailure.OwnershipRejected),
            connectWithoutSubmission(root, command),
        )
        assertEquals(
            Refinement.Rejected(PersistentBrokerServiceFailure.READINESS_REJECTED),
            BrokerServiceStartLock.submitSemantic(command) { fail("Ambiguous ownership admitted a service") },
        )
        val fence = Files.createFile(root.resolve(".lifecycle-transition.json"))
        assertEquals(SemanticCoordinatorRetirement.OWNERSHIP_UNPROVEN, retireFencedSemanticCoordinator(root, root))
        Files.delete(fence)
        assertArrayEquals(retained, Files.readAllBytes(command.readiness))
        if (socket != null)
            assertEquals(key, Files.readAttributes(socket, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
    }
}

private suspend fun connectWithoutSubmission(root: Path, command: SemanticServiceLaunch) =
    InstalledSemanticCoordinator.connect(
        root,
        command.readiness,
        command.executable,
        submit = { fail("Rejected ownership admitted a submission") },
        budgetMillis = TimeUnit.SECONDS.toMillis(LATCH_SECONDS),
    ) {
        fail("Rejected ownership must not wait")
    }

private fun exitedPeer(executable: Path): SemanticCoordinatorPeer {
    val process = exitedProcess()
    return SemanticCoordinatorPeer(
        process.pid,
        process.startedAt,
        executable.toString(),
        checkNotNull(CoordinatorExecutableRevision.capture(executable)),
        "stale",
        "sha256:" + "1".repeat(64),
    )
}

private fun writeReady(
    root: Path,
    peer: SemanticCoordinatorPeer,
    serviceIdentity: String = identity(root),
): BrokerServiceStateDocument.Ready =
    BrokerServiceStateDocument.Ready(
            BROKER_SERVICE_STATE_SCHEMA_VERSION,
            serviceIdentity,
            UUID.randomUUID().toString(),
            VENDORED_BROKER_VERSION,
            peer,
        )
        .also { writeServiceState(InstalledSemanticCoordinator.readinessPath(root, root), it) }

private fun writeStarting(root: Path, generation: UUID) =
    writeServiceState(InstalledSemanticCoordinator.readinessPath(root, root), starting(root, generation))

private fun replaceStarting(root: Path, generation: UUID) {
    val readiness = InstalledSemanticCoordinator.readinessPath(root, root)
    val staging = readiness.resolveSibling(".replacement.partial")
    writeServiceState(staging, starting(root, generation))
    Files.move(staging, readiness, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private fun starting(root: Path, generation: UUID, process: ServiceProcessIncarnation? = null) =
    BrokerServiceStateDocument.Starting(
        BROKER_SERVICE_STATE_SCHEMA_VERSION,
        identity(root),
        generation.toString(),
        VENDORED_BROKER_VERSION,
        process,
    )

private fun options(
    root: Path,
    kast: Path,
    sink: BrokerStartupActivitySink = BrokerStartupActivitySink.Disabled,
): InstalledCoordinatorOptions {
    val environment =
        mapOf(
            "BROKER_SERVICE_IDENTITY" to identity(root),
            "BROKER_READINESS_FILE" to InstalledSemanticCoordinator.readinessPath(root, root).toString(),
        )
    return (InstalledCoordinatorConfiguration.admit(kast, root, environment, sink) as Refinement.Refined).value
}

/** Derived independently from the launch contract: the SHA-256 of the canonical installation path. */
private fun identity(root: Path): String =
    "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(root.toString().toByteArray()))

private fun currentExecutable(): Path = Path.of(ProcessHandle.current().info().command().orElseThrow()).toRealPath()

private val unexpected = CoordinatorOperationExecutor { _, _ -> error("Unexpected semantic request") }

/** Independent shape of the first publication: the writer's own pid and start instant. */
private fun assertRecordsCurrentStartupProcess(readiness: Path) {
    val recorded = Json.parseToJsonElement(Files.readString(readiness)).jsonObject.getValue("startupProcess")
    val current = ProcessHandle.current()
    assertEquals(
        mapOf("pid" to current.pid().toString(), "startedAt" to current.info().startInstant().orElseThrow().toString()),
        recorded.jsonObject.mapValues { it.value.jsonPrimitive.content },
    )
}

private const val LATCH_SECONDS = 30L
private const val POLL_MILLIS = 25L
private val FOREIGN_IDENTITY = "sha256:${"b".repeat(64)}"

/** A real process incarnation that has been reaped, so its recorded identity provably exited. */
internal fun exitedProcess(): ServiceProcessIncarnation {
    val process = ProcessBuilder("/bin/sleep", "30").start()
    val recorded = checkNotNull(ServiceProcessIncarnation.capture(process.toHandle()))
    process.destroyForcibly()
    process.waitFor()
    return recorded
}
