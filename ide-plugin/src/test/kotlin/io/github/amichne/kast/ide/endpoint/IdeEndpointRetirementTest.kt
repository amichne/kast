package io.github.amichne.kast.ide.endpoint

import com.intellij.openapi.Disposable
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeEndpointRetirementTest {
    @Test
    fun `READY retires owned artifacts exactly once and preserves a later generation`() =
        withDirectory { directory ->
            val endpoint = publishEndpoint(directory)
            val stateDirectory = Path.of(endpoint.location.stateDirectoryPath.value)
            val descriptor = Path.of(endpoint.location.descriptorPath.value)
            val socket = Path.of(endpoint.location.socketPath.value)

            val first = endpoint.retire(IdeEndpointRetirementCause.SERVING_TERMINATED).retired()

            assertFalse(Files.exists(descriptor))
            assertFalse(Files.exists(socket))
            assertFalse(Files.exists(stateDirectory))
            assertEquals(IdeEndpointRetirementCause.SERVING_TERMINATED, first.cause)

            Files.createDirectory(stateDirectory)
            Files.writeString(descriptor, "later-generation")
            val repeated = endpoint.retire(
                IdeEndpointRetirementCause.PROJECT_OR_PLUGIN_DISPOSAL,
            ).retired()

            assertSame(first, repeated)
            assertEquals("later-generation", Files.readString(descriptor))
            Files.delete(descriptor)
            Files.delete(stateDirectory)
        }

    @Test
    fun `retirement closes a pending accept and produces finite transport rejection`() =
        withDirectory { directory ->
            val endpoint = publishEndpoint(directory)
            val started = CountDownLatch(1)
            val serving = CompletableFuture.supplyAsync {
                started.countDown()
                runRetirementSuspend { endpoint.serveNext() }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            endpoint.retire(IdeEndpointRetirementCause.SERVICE_CANCELLATION).retired()

            assertEquals(
                IdeEndpointConnectionHandling.Rejected(
                    IdeEndpointConnectionFailure.ACCEPT_FAILED,
                ),
                serving.get(5, TimeUnit.SECONDS),
            )
        }

    @Test
    fun `retirement closes an accepted client blocked on an incomplete frame`() =
        withDirectory { directory ->
            val endpoint = publishEndpoint(directory)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                assertTrue(
                    client.connect(
                        UnixDomainSocketAddress.of(Path.of(endpoint.location.socketPath.value)),
                    ),
                )
                val serving = CompletableFuture.supplyAsync {
                    runRetirementSuspend { endpoint.serveNext() }
                }
                assertThrows(TimeoutException::class.java) {
                    serving.get(200, TimeUnit.MILLISECONDS)
                }

                endpoint.retire(IdeEndpointRetirementCause.SERVICE_CANCELLATION).retired()

                assertEquals(
                    IdeEndpointConnectionHandling.Rejected(
                        IdeEndpointConnectionFailure.INVALID_REQUEST_FRAME,
                    ),
                    serving.get(5, TimeUnit.SECONDS),
                )
            }
        }

    @Test
    fun `project service disposal retires its READY endpoint`() = withDirectory { directory ->
        assertTrue(Disposable::class.java.isAssignableFrom(IdeEndpointService::class.java))
        val fixture = readyCoordinator(directory)
        val scope = CoroutineScope(SupervisorJob())
        val service = IdeEndpointService.testing(scope, fixture.coordinator)

        service.dispose()

        assertEndpointArtifactsAbsent(fixture.endpoint)
        scope.cancel()
    }

    @Test
    fun `project service scope cancellation retires its READY endpoint`() =
        withDirectory { directory ->
            val fixture = readyCoordinator(directory)
            val scope = CoroutineScope(SupervisorJob())
            IdeEndpointService.testing(scope, fixture.coordinator)

            scope.cancel()

            assertEventuallyArtifactsAbsent(fixture.endpoint)
        }

    @Test
    fun `transient cleanup failure retains a retryable transition to retirement`() =
        withDirectory { directory ->
            val endpoint = publishEndpoint(directory)
            val stateDirectory = Path.of(endpoint.location.stateDirectoryPath.value)
            Files.setPosixFilePermissions(
                stateDirectory,
                PosixFilePermissions.fromString("r-x------"),
            )

            val rejected = endpoint.retire(IdeEndpointRetirementCause.SERVICE_CANCELLATION)
            assertEquals(
                IdeEndpointRetirement.Rejected(
                    IdeEndpointRetirementFailure.ARTIFACT_DELETE_FAILED,
                ),
                rejected,
            )

            Files.setPosixFilePermissions(
                stateDirectory,
                PosixFilePermissions.fromString("rwx------"),
            )
            endpoint.retire(IdeEndpointRetirementCause.PROJECT_OR_PLUGIN_DISPOSAL).retired()
            assertFalse(Files.exists(stateDirectory))
        }
}

internal fun publishEndpoint(directory: Path): ReadyIdeEndpoint =
    IdeEndpointOwner(JdkIdeEndpointPublisher).publish(prepareEndpoint(directory).prepared()).ready()

internal data class ReadyCoordinatorFixture(
    val coordinator: IdeEndpointCoordinator,
    val endpoint: ReadyIdeEndpoint,
)

internal fun readyCoordinator(directory: Path): ReadyCoordinatorFixture {
    val coordinator = IdeEndpointCoordinator(JdkIdeEndpointPublisher)
    assertEquals(IdeEndpointServiceStart.Started, coordinator.begin())
    val launch = coordinator.listenersInstalled() as IdeEndpointSignalPlan.Launch
    val activation = coordinator.planCompletion(
        launch.attempt,
        IdeEndpointStartup.Prepared(prepareEndpoint(directory).prepared()),
    ) as IdeEndpointCompletionPlan.Activate
    val endpoint = when (val plan = coordinator.activate(activation.request)) {
        is IdeEndpointActivationPlan.Serve -> plan.endpoint
        is IdeEndpointActivationPlan.Retired -> fail("endpoint retired during fixture publication")
        IdeEndpointActivationPlan.Stop -> fail("endpoint stopped during fixture publication")
    }
    return ReadyCoordinatorFixture(coordinator, endpoint)
}

internal fun IdeEndpointRetirement.retired(): RetiredIdeEndpoint = when (this) {
    is IdeEndpointRetirement.Retired -> endpoint
    is IdeEndpointRetirement.Rejected -> fail("retirement rejected: $failure")
}

private fun assertEndpointArtifactsAbsent(endpoint: ReadyIdeEndpoint) {
    assertFalse(Files.exists(Path.of(endpoint.location.descriptorPath.value)))
    assertFalse(Files.exists(Path.of(endpoint.location.socketPath.value)))
    assertFalse(Files.exists(Path.of(endpoint.location.stateDirectoryPath.value)))
}

private fun assertEventuallyArtifactsAbsent(endpoint: ReadyIdeEndpoint) {
    val stateDirectory = Path.of(endpoint.location.stateDirectoryPath.value)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (Files.exists(stateDirectory) && System.nanoTime() < deadline) Thread.sleep(10)
    assertEndpointArtifactsAbsent(endpoint)
}

private fun <Value> runRetirementSuspend(block: suspend () -> Value): Value {
    var completion: Result<Value>? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Value> {
            override val context = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Value>) {
                completion = result
            }
        },
    )
    while (completion == null) Thread.onSpinWait()
    return checkNotNull(completion).getOrThrow()
}
