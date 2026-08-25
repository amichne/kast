package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class RuntimeLifecycleTest {
    @Test
    fun `status distinguishes running stopped and stale endpoint state`(@TempDir temporary: Path) {
        val endpoint = endpoint(temporary)
        val artifacts = FakeRuntimeEndpointArtifacts()
        var reachability: RuntimeEndpointReachability = RuntimeEndpointReachability.Unreachable
        val lifecycle = ExactRootRuntimeLifecycle(
            endpointProbe = RuntimeEndpointProbe { reachability },
            processAuthority = RuntimeProcessAuthority { RuntimeProcessObservation.Absent },
            artifacts = artifacts,
        )

        assertEquals(
            RuntimeStatusResult.Observed(RuntimeLifecycleState.STOPPED),
            lifecycle.status(endpoint),
        )
        artifacts.present = setOf(RuntimePersistentState)
        assertEquals(
            RuntimeStatusResult.Observed(RuntimeLifecycleState.STOPPED),
            lifecycle.status(endpoint),
        )
        artifacts.present = setOf(
            RuntimeEndpointMarker.DESCRIPTOR,
            RuntimePersistentState,
        )
        assertEquals(
            RuntimeStatusResult.Observed(RuntimeLifecycleState.STALE),
            lifecycle.status(endpoint),
        )
        reachability = RuntimeEndpointReachability.Reachable
        assertEquals(
            RuntimeStatusResult.Observed(RuntimeLifecycleState.RUNNING),
            lifecycle.status(endpoint),
        )
    }

    @Test
    fun `stop terminates only the process owned by the exact endpoint`(@TempDir temporary: Path) {
        val endpoint = endpoint(temporary)
        var observed: RuntimeEndpoint? = null
        var reachability: RuntimeEndpointReachability = RuntimeEndpointReachability.Reachable
        val lifecycle = ExactRootRuntimeLifecycle(
            endpointProbe = RuntimeEndpointProbe { reachability },
            processAuthority = RuntimeProcessAuthority { candidate ->
                observed = candidate
                RuntimeProcessObservation.Owned(RuntimeOwnedProcess {
                    reachability = RuntimeEndpointReachability.Unreachable
                    RuntimeProcessTermination.Terminated
                })
            },
            artifacts = FakeRuntimeEndpointArtifacts(),
        )

        assertEquals(
            RuntimeStopResult.Stopped(),
            lifecycle.stop(endpoint),
        )
        assertEquals(endpoint, observed)
    }

    @Test
    fun `default stop closes the exact child process and preserves persistent state`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val descriptor = endpoint.socketPath.resolveSibling(
            "${endpoint.socketPath.fileName}.endpoint.json",
        )
        val state = endpoint.socketPath.parent.toRealPath().resolve(
            "${endpoint.socketPath.fileName}.state",
        )
        val persisted = Files.createDirectories(state).resolve("workspace-publication.sqlite")
        Files.writeString(persisted, "persistent")
        val process = startRuntimeFixture(endpoint)

        try {
            assertEquals(
                RuntimeStopResult.Stopped(
                    setOf(
                        RuntimeEndpointMarker.SOCKET,
                        RuntimeEndpointMarker.DESCRIPTOR,
                    ),
                ),
                ExactRootRuntimeLifecycle().stop(endpoint),
            )
            assertEquals(false, process.isAlive)
            assertEquals(false, Files.exists(endpoint.socketPath))
            assertEquals(false, Files.exists(descriptor))
            assertEquals("persistent", Files.readString(persisted))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.onExit().get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `stop removes endpoint markers and preserves persistent state`(@TempDir temporary: Path) {
        val endpoint = endpoint(temporary)
        val descriptor = endpoint.socketPath.resolveSibling(
            "${endpoint.socketPath.fileName}.endpoint.json",
        )
        val state = endpoint.socketPath.parent.toRealPath().resolve(
            "${endpoint.socketPath.fileName}.state",
        )
        Files.writeString(endpoint.socketPath, "stale")
        Files.writeString(descriptor, "stale")
        val persisted = Files.createDirectories(state).resolve("workspace-publication.sqlite")
        Files.writeString(persisted, "persistent")
        val lifecycle = ExactRootRuntimeLifecycle(
            endpointProbe = RuntimeEndpointProbe { RuntimeEndpointReachability.Unreachable },
            processAuthority = RuntimeProcessAuthority { RuntimeProcessObservation.Absent },
        )

        assertEquals(
            RuntimeStopResult.Stopped(
                setOf(
                    RuntimeEndpointMarker.SOCKET,
                    RuntimeEndpointMarker.DESCRIPTOR,
                ),
            ),
            lifecycle.stop(endpoint),
        )
        assertEquals(false, Files.exists(endpoint.socketPath))
        assertEquals(false, Files.exists(descriptor))
        assertEquals("persistent", Files.readString(persisted))
        assertEquals(
            RuntimeStatusResult.Observed(RuntimeLifecycleState.STOPPED),
            lifecycle.status(endpoint),
        )
    }

    @Test
    fun `stop rejects when the endpoint remains reachable after owned process exit`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val lifecycle = ExactRootRuntimeLifecycle(
            endpointProbe = RuntimeEndpointProbe { RuntimeEndpointReachability.Reachable },
            processAuthority = RuntimeProcessAuthority {
                RuntimeProcessObservation.Owned(
                    RuntimeOwnedProcess { RuntimeProcessTermination.Terminated },
                )
            },
            artifacts = FakeRuntimeEndpointArtifacts(),
        )

        assertEquals(
            RuntimeStopResult.Rejected(RuntimeStopFailure.ACTIVE_ENDPOINT),
            lifecycle.stop(endpoint),
        )
    }

    @Test
    fun `clean rejects a reachable endpoint and removes only exact stale artifacts`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val artifacts = FakeRuntimeEndpointArtifacts(
            setOf(
                RuntimeEndpointMarker.SOCKET,
                RuntimeEndpointMarker.DESCRIPTOR,
                RuntimePersistentState,
            ),
        )
        var reachability: RuntimeEndpointReachability = RuntimeEndpointReachability.Reachable
        val lifecycle = ExactRootRuntimeLifecycle(
            endpointProbe = RuntimeEndpointProbe { reachability },
            processAuthority = RuntimeProcessAuthority { RuntimeProcessObservation.Absent },
            artifacts = artifacts,
        )

        assertEquals(
            RuntimeCleanResult.Rejected(RuntimeCleanFailure.ACTIVE_ENDPOINT),
            lifecycle.clean(endpoint),
        )
        reachability = RuntimeEndpointReachability.Unreachable
        assertEquals(
            RuntimeCleanResult.Cleaned(
                allRuntimeEndpointArtifacts,
            ),
            lifecycle.clean(endpoint),
        )
        assertEquals(emptySet<RuntimeEndpointArtifact>(), artifacts.present)
    }

    @Test
    fun `default clean removes the exact endpoint tree and preserves its siblings`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)
        val descriptor = endpoint.socketPath.resolveSibling(
            "${endpoint.socketPath.fileName}.endpoint.json",
        )
        val state = endpoint.socketPath.parent.toRealPath().resolve(
            "${endpoint.socketPath.fileName}.state",
        )
        val sibling = Files.writeString(temporary.resolve("preserved.txt"), "preserved")
        Files.writeString(endpoint.socketPath, "stale")
        Files.writeString(descriptor, "stale")
        Files.createDirectories(state)
        Files.writeString(state.resolve("cache.bin"), "stale")

        assertEquals(
            RuntimeCleanResult.Cleaned(
                allRuntimeEndpointArtifacts,
            ),
            ExactRootRuntimeLifecycle().clean(endpoint),
        )
        assertEquals(false, Files.exists(endpoint.socketPath))
        assertEquals(false, Files.exists(descriptor))
        assertEquals(false, Files.exists(state))
        assertEquals("preserved", Files.readString(sibling))
    }

    private fun endpoint(temporary: Path): RuntimeEndpoint {
        val rootPath = Files.createDirectories(temporary.resolve("repo"))
        Files.writeString(rootPath.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val root = when (val discovery = FilesystemCanonicalRootDiscovery.discover(rootPath)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> error(discovery.failure)
        }
        val runtimeId = when (
            val parsed = SemanticRuntimeId.parse("sha256:${"a".repeat(64)}")
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error(parsed.failure)
        }
        return when (
            val resolution = RuntimeEndpoint.at(root, runtimeId, temporary.resolve("runtime.sock"))
        ) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> error(resolution.failure)
        }
    }

    private fun startRuntimeFixture(endpoint: RuntimeEndpoint): Process {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            RuntimeLifecycleFixtureProcess::class.java.name,
            INDEXER_FIXTURE_MARKER,
            "--workspace-root=${endpoint.root.path}",
            "--socket-path=${endpoint.socketPath}",
            "--runtime-id=${endpoint.runtimeId.value}",
        ).redirectErrorStream(true).start()
        val ready = process.inputReader().readLine()
        check(ready == FIXTURE_READY) {
            "runtime fixture failed before readiness: ${ready ?: "<no output>"}"
        }
        return process
    }
}

internal object RuntimeLifecycleFixtureProcess {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val socket = arguments.single { argument -> argument.startsWith(SOCKET_ARGUMENT_PREFIX) }
            .removePrefix(SOCKET_ARGUMENT_PREFIX)
            .let(Path::of)
        val descriptor = socket.resolveSibling("${socket.fileName}.endpoint.json")
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socket))
        Files.writeString(descriptor, "fixture")
        Runtime.getRuntime().addShutdownHook(Thread(channel::close))
        println(FIXTURE_READY)
        while (true) channel.accept().use { }
    }
}

private class FakeRuntimeEndpointArtifacts(
    var present: Set<RuntimeEndpointArtifact> = emptySet(),
) : RuntimeEndpointArtifacts {
    override fun observeMarkers(endpoint: RuntimeEndpoint): RuntimeEndpointMarkerObservation =
        RuntimeEndpointMarkerObservation.Observed(
            present.filterIsInstance<RuntimeEndpointMarker>().toSet(),
        )

    override fun retireMarkers(
        endpoint: InactiveRuntimeEndpoint,
    ): RuntimeEndpointMarkerRetirement {
        val removed = present.filterIsInstance<RuntimeEndpointMarker>().toSet()
        present -= removed
        return RuntimeEndpointMarkerRetirement.Retired(removed)
    }

    override fun clean(endpoint: InactiveRuntimeEndpoint): RuntimeEndpointArtifactCleaning {
        val removed = present
        present = emptySet()
        return RuntimeEndpointArtifactCleaning.Cleaned(removed)
    }
}

private val allRuntimeEndpointArtifacts: Set<RuntimeEndpointArtifact> =
    RuntimeEndpointMarker.entries.toSet() + RuntimePersistentState

private const val INDEXER_FIXTURE_MARKER = "io.github.amichne.kast.indexer.KastIndexerMainKt"
private const val SOCKET_ARGUMENT_PREFIX = "--socket-path="
private const val FIXTURE_READY = "ready"
