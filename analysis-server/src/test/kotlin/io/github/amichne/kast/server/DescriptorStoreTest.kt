package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.DescriptorRegistryPath
import io.github.amichne.kast.api.client.IndexerBackendName
import io.github.amichne.kast.api.client.ProcessId
import io.github.amichne.kast.api.client.ProcessStartEpochMillis
import io.github.amichne.kast.api.client.RuntimeInstanceId
import io.github.amichne.kast.api.client.RuntimeProcessIdentity
import io.github.amichne.kast.api.client.RuntimeSocketPath
import io.github.amichne.kast.api.client.RuntimeWorkspaceRoot
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.api.client.ServerInstanceOwnership
import io.github.amichne.kast.api.contract.compatibility.RuntimeImplementationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlinx.serialization.json.Json

class DescriptorStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun readDescriptors(daemonsFile: Path): List<ServerInstanceDescriptor> =
        json.decodeFromString(Files.readString(daemonsFile))

    @Test
    fun `writes and deletes descriptor via registry`() {
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = RuntimeWorkspaceRoot.parse("/tmp/workspace"),
            backendVersion = RuntimeImplementationVersion("0.1.0"),
            socketPath = RuntimeSocketPath.of(Path.of("/tmp/workspace/.kast/s")),
            ownership = ServerInstanceOwnership.LegacyWithoutProcessId,
        )
        val daemonsFile = tempDir.resolve("daemons.json")
        val store = DescriptorStore(DescriptorRegistryPath.of(daemonsFile))

        store.write(descriptor)
        assertEquals(listOf(descriptor), readDescriptors(daemonsFile))

        store.delete(descriptor)
        assertFalse(daemonsFile.exists())
    }

    @Test
    fun `endpoint launch lock spans bind evidence and descriptor registration`() {
        val runDirectory = tempDir.resolve("run")
        val runDirectoryAlias = tempDir.resolve("run-alias")
        Files.createDirectories(runDirectory)
        Files.createSymbolicLink(runDirectoryAlias, runDirectory)
        val socketPath = RuntimeSocketPath.of(runDirectory.resolve("indexer.sock"))
        val aliasedSocketPath = RuntimeSocketPath.of(runDirectoryAlias.resolve("indexer.sock"))
        assertEquals(socketPath, aliasedSocketPath, "Socket path aliases must share one lock identity")
        val firstStore = DescriptorStore(
            DescriptorRegistryPath.of(tempDir.resolve("first-instances/daemons.json")),
        )
        val secondStore = DescriptorStore(
            DescriptorRegistryPath.of(tempDir.resolve("second-instances/daemons.json")),
        )
        val effectiveOwner = readEffectiveProcessOwnerUid(tempDir)
        val firstBound = CountDownLatch(1)
        val allowFirstRegistration = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondBindEntered = AtomicBoolean(false)
        val firstResult = AtomicReference<Result<LaunchedEndpoint<TestBoundSocketServer>>>()
        val secondResult = AtomicReference<Result<LaunchedEndpoint<TestBoundSocketServer>>>()

        val firstThread = thread(name = "first-endpoint-launch") {
            firstResult.set(
                runCatching {
                    firstStore.launchEndpoint(launchRequest(socketPath, effectiveOwner)) {
                        bindTestEndpoint(socketPath, runDirectory.resolve("indexer.sock")).also {
                            firstBound.countDown()
                            check(allowFirstRegistration.await(5, TimeUnit.SECONDS)) {
                                "Timed out waiting to register the first descriptor"
                            }
                        }
                    }
                },
            )
        }
        assertTrue(firstBound.await(5, TimeUnit.SECONDS), "The first launch did not bind its endpoint")

        val secondThread = thread(name = "second-endpoint-launch") {
            secondStarted.countDown()
            secondResult.set(
                runCatching {
                    secondStore.launchEndpoint(launchRequest(aliasedSocketPath, effectiveOwner)) {
                        secondBindEntered.set(true)
                        bindTestEndpoint(aliasedSocketPath, runDirectoryAlias.resolve("indexer.sock"))
                    }
                },
            )
        }

        assertTrue(secondStarted.await(1, TimeUnit.SECONDS), "The competing launch did not start")
        assertTrue(awaitBlocked(secondThread), "The competing launch did not wait for the endpoint lock")
        assertFalse(secondBindEntered.get(), "The competing launch entered bind before registration")

        allowFirstRegistration.countDown()
        firstThread.join(5_000)
        secondThread.join(5_000)
        assertFalse(firstThread.isAlive, "The first launch did not complete")
        assertFalse(secondThread.isAlive, "The competing launch did not complete")

        val launched = firstResult.get().getOrThrow()
        try {
            assertTrue(secondResult.get().isFailure, "The competing launch replaced a reachable endpoint")
            assertFalse(secondBindEntered.get(), "The competing launch reached bind after ownership validation")
            val ownership = launched.descriptor.ownership as ServerInstanceOwnership.Owned
            assertEquals(readBoundSocketEvidence(socketPath.toPath()).socketOwnerUid, ownership.ownerUid)
        } finally {
            launched.server.close()
        }
    }

    @Test
    fun `actual socket owner must match the effective process owner`() {
        val bindPath = tempDir.resolve("o.sock")
        val socketPath = RuntimeSocketPath.of(bindPath)
        val daemonsFile = tempDir.resolve("instances/daemons.json")
        val actualOwner = readEffectiveProcessOwnerUid(tempDir)
        val differentOwner = EffectiveProcessOwnerUid.of(actualOwner.value + 1)

        assertThrows(IllegalStateException::class.java) {
            DescriptorStore(DescriptorRegistryPath.of(daemonsFile)).launchEndpoint<TestBoundSocketServer>(
                launchRequest(socketPath, differentOwner),
            ) {
                bindTestEndpoint(socketPath, bindPath)
            }
        }

        assertFalse(socketPath.toPath().exists(), "Owner mismatch leaked the bound endpoint")
        assertFalse(daemonsFile.exists(), "Owner mismatch registered an authoritative descriptor")
    }

    @Test
    fun `absent socket does not bypass descriptor registry symlink rejection`() {
        val socketPath = RuntimeSocketPath.of(tempDir.resolve("run/absent.sock"))
        val descriptorDirectory = tempDir.resolve("instances")
        val registryTarget = tempDir.resolve("registry-target.json")
        val daemonsFile = descriptorDirectory.resolve("daemons.json")
        Files.createDirectories(descriptorDirectory)
        Files.writeString(registryTarget, "[]")
        Files.createSymbolicLink(daemonsFile, registryTarget)
        val bindEntered = AtomicBoolean(false)

        assertThrows(IllegalStateException::class.java) {
            DescriptorStore(DescriptorRegistryPath.of(daemonsFile)).launchEndpoint<TestBoundSocketServer>(
                launchRequest(socketPath, readEffectiveProcessOwnerUid(tempDir)),
            ) {
                bindEntered.set(true)
                error("Bind must not run for a symbolic-link registry")
            }
        }

        assertFalse(bindEntered.get(), "Registry validation ran after the bind phase")
        assertTrue(Files.isSymbolicLink(daemonsFile), "Launch replaced the descriptor registry symbolic link")
        assertEquals("[]", Files.readString(registryTarget))
    }

    private fun launchRequest(
        socketPath: RuntimeSocketPath,
        effectiveOwner: EffectiveProcessOwnerUid,
    ): EndpointLaunchRequest = EndpointLaunchRequest(
        workspaceRoot = RuntimeWorkspaceRoot.canonicalize(tempDir),
        backendName = IndexerBackendName.INDEXER,
        backendVersion = RuntimeImplementationVersion("test"),
        socketPath = socketPath,
        runtimeInstanceId = RuntimeInstanceId.create(),
        processIdentity = RuntimeProcessIdentity(
            processId = ProcessId.current(),
            processStartEpochMillis = ProcessStartEpochMillis.of(1),
        ),
        effectiveProcessOwnerUid = effectiveOwner,
    )

    private fun bindTestEndpoint(
        socketPath: RuntimeSocketPath,
        bindPath: Path = socketPath.toPath(),
    ): BoundEndpoint<TestBoundSocketServer> {
        Files.createDirectories(bindPath.parent)
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(bindPath))
        return BoundEndpoint(
            server = TestBoundSocketServer(channel, bindPath),
            evidence = readBoundSocketEvidence(socketPath.toPath()),
        )
    }

    private fun awaitBlocked(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.BLOCKED) return true
            Thread.sleep(1)
        }
        return false
    }

    private class TestBoundSocketServer(
        private val channel: ServerSocketChannel,
        private val socketPath: Path,
    ) : LocalRpcServer {
        override fun await() = Unit

        override fun close() {
            channel.close()
            Files.deleteIfExists(socketPath)
        }
    }
}
