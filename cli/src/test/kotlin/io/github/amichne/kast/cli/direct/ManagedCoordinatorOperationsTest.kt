package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationProtocolFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.distribution.contract.ContainerFailure
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.canonicalRootFixture
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedCoordinatorOperationsTest {
    private val rootA = canonicalRootFixture(Path.of("/workspaces/a"))
    private val rootB = canonicalRootFixture(Path.of("/workspaces/b"))
    private val defaults = WorkspaceSettings.explicit(emptyMap())
    private val smallerHeap = WorkspaceSettings.explicit(mapOf(HEAP to "1024"))

    @Test
    fun `concurrent first requests for one workspace share one preparation and one Host`() = runBlocking {
        val workspaces = ScriptedWorkspaces().blockPreparation(rootA)
        ManagedCoordinatorOperations(SERVICE, workspaces).use { operations ->
            val requests = List(4) { async(Dispatchers.Default) { operations.execute(rootA, defaults, prepareRead) } }
            workspaces.awaitPreparationStart()
            workspaces.releasePreparation(rootA)

            assertEquals(List(4) { "a#1" }, requests.awaitAll().map(::host))
            assertEquals(listOf(rootA), workspaces.preparations.toList())
        }
    }

    @Test
    fun `a blocked preparation for one workspace does not delay another workspace`() = runBlocking {
        val workspaces = ScriptedWorkspaces().blockPreparation(rootA)
        ManagedCoordinatorOperations(SERVICE, workspaces).use { operations ->
            val first = async(Dispatchers.Default) { operations.execute(rootA, defaults, prepareRead) }
            workspaces.awaitPreparationStart()

            val other =
                try {
                    withTimeout(WAIT) { operations.execute(rootB, defaults, prepareRead) }
                } finally {
                    workspaces.releasePreparation(rootA)
                }

            assertEquals("b#1", host(other))
            assertEquals("a#2", host(first.await()))
        }
    }

    @Test
    fun `cancelling one waiter keeps the shared preparation for another waiter`() = runBlocking {
        val workspaces = ScriptedWorkspaces().blockPreparation(rootA)
        ManagedCoordinatorOperations(SERVICE, workspaces).use { operations ->
            val cancelled = async(Dispatchers.Default) { operations.execute(rootA, defaults, prepareRead) }
            workspaces.awaitPreparationStart()
            val remaining = async(Dispatchers.Default) { operations.execute(rootA, defaults, prepareRead) }
            cancelled.cancelAndJoin()
            workspaces.releasePreparation(rootA)

            assertEquals("a#1", host(withTimeout(WAIT) { remaining.await() }))
            assertEquals(listOf(rootA), workspaces.preparations.toList())
            assertEquals(0, workspaces.interruptedPreparations.get())
        }
    }

    @Test
    fun `explicit conflicting Host settings reject before a read and request-local settings reuse the Host`() =
        runBlocking {
            val workspaces = ScriptedWorkspaces()
            ManagedCoordinatorOperations(SERVICE, workspaces).use { operations ->
                assertEquals("a#1", host(operations.execute(rootA, defaults, prepareRead)))

                assertEquals(
                    managedFailure(ContainerFailure.CONFIGURATION_CONFLICT),
                    operations.execute(rootA, smallerHeap, prepareRead),
                )
                val output = WorkspaceSettings.explicit(mapOf("KAST_OUTPUT_TOON" to "1"))
                assertEquals("a#1", host(operations.execute(rootA, output, prepareRead)))

                assertEquals(listOf(rootA), workspaces.preparations.toList())
                assertEquals(listOf("a#1", "a#1"), workspaces.reads.toList())
            }
        }

    @Test
    fun `a conflicting caller neither joins nor waits for another caller's preparation`() = runBlocking {
        val workspaces = ScriptedWorkspaces().blockPreparation(rootA)
        ManagedCoordinatorOperations(SERVICE, workspaces).use { operations ->
            val first = async(Dispatchers.Default) { operations.execute(rootA, defaults, prepareRead) }
            workspaces.awaitPreparationStart()

            assertEquals(
                managedFailure(ContainerFailure.CONFIGURATION_CONFLICT),
                withTimeout(WAIT) { operations.execute(rootA, smallerHeap, prepareRead) },
            )
            workspaces.releasePreparation(rootA)

            assertEquals("a#1", host(first.await()))
            assertEquals(listOf(rootA), workspaces.preparations.toList())
        }
    }

    @Test
    fun `draining one workspace settles its in-flight read and leaves another workspace operational`() = runBlocking {
        val workspaces = ScriptedWorkspaces()
        ManagedCoordinatorOperations(SERVICE, workspaces).use { operations ->
            assertEquals("a#1", host(operations.execute(rootA, defaults, prepareRead)))
            assertEquals("b#2", host(operations.execute(rootB, defaults, prepareRead)))
            workspaces.blockReads()
            val inFlight = async(Dispatchers.Default) { operations.execute(rootA, defaults, prepareRead) }
            workspaces.awaitReadStart()
            workspaces.allowReads("b#2")

            val drained = async(Dispatchers.Default) { operations.drain(rootA) }
            withTimeout(WAIT) {
                while (operations.phase(rootA) != WorkspacePhase.DRAINING) delay(POLL)
            }
            assertEquals(lifecycleTransition, operations.execute(rootA, defaults, prepareRead))
            assertEquals("b#2", host(withTimeout(WAIT) { operations.execute(rootB, defaults, prepareRead) }))
            assertEquals(emptyList<String>(), workspaces.closedHosts.toList())

            workspaces.releaseReads()
            assertEquals("a#1", host(inFlight.await()))
            assertEquals(WorkspaceDrain.DRAINED, withTimeout(WAIT) { drained.await() })
            assertEquals(listOf("a#1"), workspaces.closedHosts.toList())
            assertEquals(null, operations.phase(rootA))
            assertEquals(WorkspacePhase.READY, operations.phase(rootB))

            assertEquals("a#3", host(operations.execute(rootA, defaults, prepareRead)))
            assertEquals(WorkspaceDrain.NOT_REGISTERED, operations.drain(canonicalRootFixture(Path.of("/none"))))
        }
    }

    @Test
    fun `idle Ready holds no lease while a read holds only its own request lease`() = runBlocking {
        val workspaces = ScriptedWorkspaces()
        ManagedCoordinatorOperations(SERVICE, workspaces).use { operations ->
            assertEquals("a#1", host(operations.execute(rootA, defaults, prepareRead)))
            assertEquals(0, workspaces.openLeases.get())
            assertEquals(WorkspacePhase.READY, operations.phase(rootA))

            workspaces.blockReads()
            val inFlight = async(Dispatchers.Default) { operations.execute(rootA, defaults, prepareRead) }
            workspaces.awaitReadStart()
            assertEquals(1, workspaces.openLeases.get())
            workspaces.releaseReads()
            inFlight.await()
            assertEquals(0, workspaces.openLeases.get())
        }
    }

    @Test
    fun `a vanished Host is replaced once with a fresh preparation and existing authority never prepares`() =
        runBlocking {
            val workspaces = ScriptedWorkspaces()
            ManagedCoordinatorOperations(SERVICE, workspaces).use { operations ->
                assertEquals("a#1", host(operations.execute(rootA, defaults, prepareRead)))
                workspaces.vanished += "a#1"

                assertEquals("a#2", host(operations.execute(rootA, defaults, prepareRead)))
                assertEquals(listOf(rootA, rootA), workspaces.preparations.toList())
                assertEquals(listOf("a#1"), workspaces.closedHosts.toList())

                workspaces.vanished += "a#2"
                workspaces.vanished += "a#3"
                assertEquals(hostUnavailable, operations.execute(rootA, defaults, prepareRead))
                assertEquals(listOf(rootA, rootA, rootA), workspaces.preparations.toList())

                workspaces.existingUnavailable = true
                assertEquals(hostUnavailable, operations.execute(rootB, defaults, retainedRead))
                assertEquals(listOf(rootA, rootA, rootA), workspaces.preparations.toList())
                assertEquals(0, workspaces.openLeases.get())
            }
        }

    private fun host(result: DaemonOperationResult): String =
        Json.decodeFromString(HostDocument.serializer(), (result as DaemonOperationResult.Complete).document.value).host

    private fun managedFailure(failure: ContainerFailure): DaemonOperationResult =
        DaemonOperationResult.Rejected(
            DaemonOperationClientRejection.Server(DaemonOperationFailure.ManagedWorkspace(failure))
        )

    private val lifecycleTransition =
        DaemonOperationResult.Rejected(
            DaemonOperationClientRejection.Server(
                DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION)
            )
        )

    private companion object {
        const val HEAP = "KAST_HOST_MAX_HEAP_MIB"
        val SERVICE = mapOf("PATH" to "/usr/bin:/bin")
        val WAIT = 10.seconds
        const val POLL = 5L

        val prepareRead = publicTool("runByName")
        val retainedRead = publicTool("readRetained")

        fun publicTool(example: String): DaemonOperationCall {
            val arguments =
                PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS).examples.getValue(example).value
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, arguments)
            return DaemonOperationCall.PublicTool((admitted as Refinement.Refined).value)
        }

        val hostUnavailable =
            DaemonOperationResult.Rejected(
                DaemonOperationClientRejection.Server(
                    DaemonOperationFailure.Workspace(
                        "workspace",
                        WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.HOST_UNAVAILABLE),
                    )
                )
            )
    }
}

@Serializable private data class HostDocument(val host: String)

private val hostDocument = CanonicalJsonDocument.generated(HostDocument.serializer())

/** Scripted installed effects. Host labels are the workspace name and a global incarnation number. */
private class ScriptedWorkspaces : ManagedWorkspaceAcquisition {
    val preparations = ConcurrentLinkedQueue<CanonicalRoot>()
    val reads = ConcurrentLinkedQueue<String>()
    val closedHosts = ConcurrentLinkedQueue<String>()
    val vanished: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val openLeases = AtomicInteger()
    val interruptedPreparations = AtomicInteger()
    @Volatile var existingUnavailable = false
    private val incarnations = AtomicInteger()
    private val preparationGates = ConcurrentHashMap<CanonicalRoot, CompletableFuture<Unit>>()
    private val preparationStarts = Semaphore(0)
    private val readStarts = Semaphore(0)
    @Volatile private var readGate: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    private val unblockedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun blockPreparation(root: CanonicalRoot) = apply { preparationGates[root] = CompletableFuture() }

    fun releasePreparation(root: CanonicalRoot) {
        preparationGates.getValue(root).complete(Unit)
    }

    fun awaitPreparationStart() = assertTrue(preparationStarts.tryAcquire(10, TimeUnit.SECONDS))

    fun blockReads() {
        readGate = CompletableFuture()
    }

    fun allowReads(host: String) {
        unblockedHosts += host
    }

    fun releaseReads() {
        readGate.complete(Unit)
    }

    fun awaitReadStart() = assertTrue(readStarts.tryAcquire(10, TimeUnit.SECONDS))

    override fun prepare(
        root: CanonicalRoot,
        environment: Map<String, String>,
    ): Refinement<PreparedWorkspaceBinding, DaemonOperationFailure> = leased {
        preparations += root
        preparationStarts.release()
        try {
            preparationGates[root]?.get(20, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interruptedPreparations.incrementAndGet()
            return@leased Refinement.Rejected(
                DaemonOperationFailure.ManagedWorkspace(ContainerFailure.CANCELLED_INTERRUPT)
            )
        }
        val host = "${root.path.fileName}#${incarnations.incrementAndGet()}"
        Refinement.Refined(
            PreparedWorkspaceBinding(identity(root, environment), { _, _ -> read(host) }) { closedHosts += host }
        )
    }

    override fun <T> withRequestLease(
        root: CanonicalRoot,
        environment: Map<String, String>,
        action: (WorkspaceHostIdentity) -> T,
    ): Refinement<T, DaemonOperationFailure> = leased { Refinement.Refined(action(identity(root, environment))) }

    override fun existing(
        root: CanonicalRoot,
        environment: Map<String, String>,
        operation: DaemonOperationCall,
    ): DaemonOperationResult = leased {
        if (existingUnavailable)
            DaemonOperationResult.Rejected(
                DaemonOperationClientRejection.Server(
                    DaemonOperationFailure.Workspace(
                        "workspace",
                        WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.HOST_UNAVAILABLE),
                    )
                )
            )
        else DaemonOperationResult.Complete(hostDocument.create(HostDocument("existing")))
    }

    private fun read(host: String): DaemonOperationResult {
        if (host in vanished)
            return DaemonOperationResult.Rejected(
                DaemonOperationClientRejection.Server(
                    DaemonOperationFailure.Workspace(
                        "workspace",
                        WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.HOST_UNAVAILABLE),
                    )
                )
            )
        val blocked = readGate
        if (host !in unblockedHosts && !blocked.isDone) {
            readStarts.release()
            blocked.get(20, TimeUnit.SECONDS)
        }
        reads += host
        return DaemonOperationResult.Complete(hostDocument.create(HostDocument(host)))
    }

    /** The fake Host identity is derived only from the explicit heap setting that reaches the lease. */
    private fun identity(root: CanonicalRoot, environment: Map<String, String>) =
        WorkspaceHostIdentity(root.path.resolve(".home"), environment.filterKeys { it == "KAST_HOST_MAX_HEAP_MIB" })

    private inline fun <T> leased(action: () -> T): T {
        openLeases.incrementAndGet()
        try {
            return action()
        } finally {
            openLeases.decrementAndGet()
        }
    }
}
