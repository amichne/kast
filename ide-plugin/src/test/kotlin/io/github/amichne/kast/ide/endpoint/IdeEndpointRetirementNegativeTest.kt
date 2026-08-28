package io.github.amichne.kast.ide.endpoint

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class IdeEndpointRetirementNegativeTest {
    @Test
    fun `physically replaced descriptor is preserved and retirement rejects its identity`() =
        withDirectory { directory ->
            val endpoint = publishEndpoint(directory)
            val descriptor = Path.of(endpoint.location.descriptorPath.value)
            val stateDirectory = Path.of(endpoint.location.stateDirectoryPath.value)
            Files.delete(descriptor)
            Files.writeString(descriptor, "replacement-descriptor")

            assertRetirementRejected(
                endpoint.retire(IdeEndpointRetirementCause.PROJECT_OR_PLUGIN_DISPOSAL),
                IdeEndpointRetirementFailure.DESCRIPTOR_IDENTITY_MISMATCH,
            )

            assertEquals("replacement-descriptor", Files.readString(descriptor))
            assertFalse(Files.exists(Path.of(endpoint.location.socketPath.value)))
            Files.delete(descriptor)
            Files.delete(stateDirectory)
        }

    @Test
    fun `physically replaced socket is preserved and retirement rejects its identity`() =
        withDirectory { directory ->
            val endpoint = publishEndpoint(directory)
            val socket = Path.of(endpoint.location.socketPath.value)
            val stateDirectory = Path.of(endpoint.location.stateDirectoryPath.value)
            endpoint.closeListeningSocketForTest()
            Files.delete(socket)
            Files.writeString(socket, "replacement-socket")

            assertRetirementRejected(
                endpoint.retire(IdeEndpointRetirementCause.SERVICE_CANCELLATION),
                IdeEndpointRetirementFailure.SOCKET_IDENTITY_MISMATCH,
            )

            assertEquals("replacement-socket", Files.readString(socket))
            assertFalse(Files.exists(Path.of(endpoint.location.descriptorPath.value)))
            Files.delete(socket)
            Files.delete(stateDirectory)
        }

    @Test
    fun `disposal racing publication retires the late endpoint instead of leaking it`() =
        withDirectory { directory ->
            val prepared = prepareEndpoint(directory).prepared()
            val published = CountDownLatch(1)
            val release = CountDownLatch(1)
            val coordinator = IdeEndpointCoordinator(IdeEndpointPublisher { candidate ->
                val activation = JdkIdeEndpointPublisher.publish(candidate)
                published.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                activation
            })
            assertEquals(IdeEndpointServiceStart.Started, coordinator.begin())
            val launch = coordinator.listenersInstalled() as IdeEndpointSignalPlan.Launch
            val activation = coordinator.planCompletion(
                launch.attempt,
                IdeEndpointStartup.Prepared(prepared),
            ) as IdeEndpointCompletionPlan.Activate
            val result = CompletableFuture.supplyAsync { coordinator.activate(activation.request) }
            assertTrue(published.await(5, TimeUnit.SECONDS))

            assertEquals(
                IdeEndpointCoordinatorRetirement.NoReadyEndpoint,
                coordinator.retire(IdeEndpointRetirementCause.PROJECT_OR_PLUGIN_DISPOSAL),
            )
            release.countDown()

            val plan = result.get(5, TimeUnit.SECONDS)
            assertTrue(plan is IdeEndpointActivationPlan.Retired)
            assertTrue(
                (plan as IdeEndpointActivationPlan.Retired).result is IdeEndpointRetirement.Retired,
            )
            assertFalse(Files.exists(Path.of(prepared.location.descriptorPath.value)))
            assertFalse(Files.exists(Path.of(prepared.location.socketPath.value)))
            assertFalse(Files.exists(Path.of(prepared.location.stateDirectoryPath.value)))
        }
}

private fun assertRetirementRejected(
    result: IdeEndpointRetirement,
    expected: IdeEndpointRetirementFailure,
) = when (result) {
    is IdeEndpointRetirement.Retired -> fail("unexpected retirement: ${result.endpoint}")
    is IdeEndpointRetirement.Rejected -> assertEquals(expected, result.failure)
}
