package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
            RuntimeLifecycleResult.Completed(RuntimeLifecycleState.STOPPED),
            lifecycle.status(endpoint),
        )
        artifacts.present = setOf(RuntimeEndpointArtifact.DESCRIPTOR)
        assertEquals(
            RuntimeLifecycleResult.Completed(RuntimeLifecycleState.STALE),
            lifecycle.status(endpoint),
        )
        reachability = RuntimeEndpointReachability.Reachable
        assertEquals(
            RuntimeLifecycleResult.Completed(RuntimeLifecycleState.RUNNING),
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
            RuntimeLifecycleResult.Completed(RuntimeLifecycleState.STOPPED),
            lifecycle.stop(endpoint),
        )
        assertEquals(endpoint, observed)
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
            RuntimeLifecycleResult.Rejected(RuntimeLifecycleFailure.ACTIVE_ENDPOINT),
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
                RuntimeEndpointArtifact.SOCKET,
                RuntimeEndpointArtifact.DESCRIPTOR,
                RuntimeEndpointArtifact.STATE,
            ),
        )
        var reachability: RuntimeEndpointReachability = RuntimeEndpointReachability.Reachable
        val lifecycle = ExactRootRuntimeLifecycle(
            endpointProbe = RuntimeEndpointProbe { reachability },
            processAuthority = RuntimeProcessAuthority { RuntimeProcessObservation.Absent },
            artifacts = artifacts,
        )

        assertEquals(
            RuntimeLifecycleResult.Rejected(RuntimeLifecycleFailure.ACTIVE_ENDPOINT),
            lifecycle.clean(endpoint),
        )
        reachability = RuntimeEndpointReachability.Unreachable
        assertEquals(
            RuntimeLifecycleResult.Completed(
                RuntimeLifecycleState.STOPPED,
                RuntimeEndpointArtifact.entries.toSet(),
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
            RuntimeLifecycleResult.Completed(
                RuntimeLifecycleState.STOPPED,
                RuntimeEndpointArtifact.entries.toSet(),
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
}

private class FakeRuntimeEndpointArtifacts(
    var present: Set<RuntimeEndpointArtifact> = emptySet(),
) : RuntimeEndpointArtifacts {
    override fun observe(endpoint: RuntimeEndpoint): RuntimeEndpointArtifactObservation =
        RuntimeEndpointArtifactObservation.Observed(present)

    override fun clean(endpoint: RuntimeEndpoint): RuntimeEndpointArtifactCleaning {
        val removed = present
        present = emptySet()
        return RuntimeEndpointArtifactCleaning.Cleaned(removed)
    }
}
