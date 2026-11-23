package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.CoordinatorHostProfile
import io.github.amichne.kast.distribution.contract.InstallationLayout
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The semantic coordinator publishes a readiness record and a private socket; it never creates a broker epoch. */
class PassiveSemanticCoordinatorTest {
    // A short root keeps the private socket beneath the platform's socket path limit.
    private val installation = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kc-")
    private val state = InstallationLayout.of(installation).state
    private val started = ProcessHandle.current().info().startInstant().orElseThrow()

    @BeforeEach
    fun installed() {
        val manifest = SemanticFixtureManifest(3, installation.toString(), CODEX_HOME)
        Files.writeString(installation.resolve("installation.json"), Json.encodeToString(manifest))
    }

    @AfterEach
    fun removed() {
        assertTrue(installation.toFile().deleteRecursively())
    }

    @Test
    fun `running coordinator reports the version its own status exchange returned`() {
        publish(ready(started))
        val requests = mutableListOf<Path>()
        val observed = observeRuntime(installation) { socket, _ -> reply().also { requests.add(socket) } }
        assertEquals(listOf(state.run.coordinatorSocket), requests)
        assertEquals(Observation.verified("0.0.4808"), observed.loadedVersion)
        // The exchange counts Codex sessions and Codex-prepared workspaces, so it cannot list semantic workspaces.
        assertEquals("BROKER_NOT_OBSERVED", observed.activeWorkspaces.reason)
        assertEquals("BROKER_NOT_OBSERVED", observed.liveConnections.reason)
    }

    @Test
    fun `running coordinator is observed beside an earlier broker epoch`() {
        Files.createDirectories(state.directory)
        Files.writeString(state.epoch, "left by an earlier broker")
        publish(ready(started))
        assertEquals(Observation.verified("0.0.4808"), observeRuntime(installation) { _, _ -> reply() }.loadedVersion)
    }

    @Test
    fun `no readiness record and no socket means the coordinator is not running`() {
        assertEquals(RuntimeObservationFailure.COORDINATOR_NOT_RUNNING, failureWithoutExchange())
    }

    @Test
    fun `stale socket of an exited coordinator process is not running`() {
        publish(ready(started.minusSeconds(1)))
        withListener { assertEquals(RuntimeObservationFailure.COORDINATOR_NOT_RUNNING, failureWithoutExchange()) }
    }

    @Test
    fun `socket without a readiness record proves neither state`() {
        withListener { assertEquals(RuntimeObservationFailure.READINESS_UNAVAILABLE, failureWithoutExchange()) }
    }

    @Test
    fun `starting record follows the process that wrote it`() {
        publish(starting(started))
        assertEquals(RuntimeObservationFailure.COORDINATOR_STARTING, failureWithoutExchange())
        publish(starting(started.minusSeconds(1)))
        assertEquals(RuntimeObservationFailure.COORDINATOR_NOT_RUNNING, failureWithoutExchange())
    }

    @Test
    fun `failed startup stays distinct from a coordinator that was never started`() {
        publish(SemanticFixtureReadiness("rejected", failure = "SERVER_REJECTED"))
        assertEquals(RuntimeObservationFailure.COORDINATOR_STARTUP_REJECTED, failureWithoutExchange())
    }

    @Test
    fun `malformed reply and foreign identity never report a version`() {
        publish(ready(started))
        assertEquals(RuntimeObservationFailure.RESPONSE_REJECTED, failure { "not a status document" })
        assertEquals(RuntimeObservationFailure.IDENTITY_MISMATCH, failure { reply(installationId = digest('c')) })
        assertEquals(RuntimeObservationFailure.IDENTITY_MISMATCH, failure { reply(generation = OTHER_GENERATION) })
    }

    @Test
    fun `damaged readiness record is rejected`() {
        publish(ready(started))
        Files.writeString(readiness(), "damaged")
        assertEquals(RuntimeObservationFailure.READINESS_REJECTED, failureWithoutExchange())
    }

    @Test
    fun `live process that never answers is bounded and not reported as stopped`() {
        publish(ready(started))
        withListener {
            assertTimeout(Duration.ofSeconds(10)) {
                assertNull(boundedExchange(state.run.coordinatorSocket, runtimeStatusRequestDocument()))
            }
        }
        assertEquals(RuntimeObservationFailure.EXCHANGE_UNAVAILABLE, failure { null })
    }

    @Test
    fun `record of another service leaves the observation to the Codex broker`() {
        publish(SemanticFixtureReadiness("ready", serviceIdentity = digest('d')))
        assertEquals(RuntimeObservationFailure.EPOCH_UNAVAILABLE, failureWithoutExchange())
    }

    private fun failure(exchange: () -> String?): RuntimeObservationFailure =
        (observeRuntime(installation) { _, _ -> exchange() } as PassiveRuntimeObservation.Rejected).failure

    private fun failureWithoutExchange(): RuntimeObservationFailure = failure { error("Unexpected exchange") }

    private fun readiness(): Path = state.readiness(CoordinatorHostProfile.of(CODEX_HOME))

    private fun publish(record: SemanticFixtureReadiness) {
        Files.createDirectories(readiness().parent)
        val owned = record.copy(serviceIdentity = record.serviceIdentity ?: "sha256:${sha256Text("$installation")}")
        Files.writeString(readiness(), Json { explicitNulls = false }.encodeToString(owned))
    }

    private fun ready(startedAt: Instant) =
        SemanticFixtureReadiness(
            "ready",
            semanticPeer = SemanticFixturePeer(ProcessHandle.current().pid(), "$startedAt", INSTALLATION_ID),
        )

    private fun starting(startedAt: Instant) =
        SemanticFixtureReadiness(
            "starting",
            startupProcess = SemanticFixtureProcess(ProcessHandle.current().pid(), "$startedAt"),
        )

    /** Listens on the private socket and never answers. */
    private fun withListener(action: () -> Unit) {
        Files.createDirectories(state.run.directory)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use {
            it.bind(UnixDomainSocketAddress.of(state.run.coordinatorSocket))
            action()
        }
    }

    private fun reply(installationId: String = INSTALLATION_ID, generation: String = GENERATION): String =
        Json.encodeToString(
            SemanticFixtureStatus(
                "status",
                SemanticFixtureCoordinator(installationId, generation, generation, "configuration"),
                SemanticFixtureProjection("0.0.4808", emptyList(), null, emptyList()),
            )
        )
}

private const val CODEX_HOME = "/fixture/codex"
private const val GENERATION = "9a3f2a15-b973-4dd2-80ba-cc89bb6f07d9"
private const val OTHER_GENERATION = "12345678-1234-1234-1234-123456789abc"
private val INSTALLATION_ID = digest('a')

private fun digest(character: Char): String = "sha256:" + character.toString().repeat(64)

@Serializable
private data class SemanticFixtureManifest(val schemaVersion: Int, val installationRoot: String, val codexHome: String)

@Serializable
private data class SemanticFixtureReadiness(
    val state: String,
    val serviceIdentity: String? = null,
    val semanticPeer: SemanticFixturePeer? = null,
    val startupProcess: SemanticFixtureProcess? = null,
    val failure: String? = null,
    @kotlinx.serialization.Required val schemaVersion: Int = 3,
    @kotlinx.serialization.Required val serviceInstanceId: String = GENERATION,
    @kotlinx.serialization.Required val brokerVersion: String = "0.7.0",
)

@Serializable private data class SemanticFixturePeer(val pid: Long, val startedAt: String, val installationId: String)

@Serializable private data class SemanticFixtureProcess(val pid: Long, val startedAt: String)

@Serializable
private data class SemanticFixtureCoordinator(
    val installationId: String,
    val stateEpoch: String,
    val serviceGeneration: String,
    val configurationIdentity: String,
)

@Serializable
private data class SemanticFixtureProjection(
    val loadedVersion: String?,
    val activeWorkspaces: List<String>,
    val liveConnections: Int?,
    val hostedServices: List<String>,
)

@Serializable
private data class SemanticFixtureStatus(
    val type: String,
    val coordinator: SemanticFixtureCoordinator,
    val projection: SemanticFixtureProjection,
)
