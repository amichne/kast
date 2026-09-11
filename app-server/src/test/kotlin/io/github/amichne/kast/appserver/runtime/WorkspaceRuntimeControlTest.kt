@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.distribution.contract.configuration.*
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceRuntimeControlTest {
    @Test
    fun `control owns shared startup and durable receipt before launcher admission`(@TempDir directory: Path) =
        runTest {
            val fixture = Fixture(directory)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val effects =
                object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                    override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                        assertTrue(
                            Files.exists(directory.resolve("config/workspaces.json")),
                            "root registration must precede launch",
                        )
                        assertEquals(
                            1L,
                            Files.list(directory.resolve("state/workers")).use { it.count() },
                            "durable reservation must precede launch",
                        )
                        fixture.starts.incrementAndGet()
                        entered.complete(Unit)
                        release.await()
                        return InstalledWorkerStart.Ready(fixture.endpoint(request.root))
                    }

                    override suspend fun observe(route: InstalledWorkerEndpoint) =
                        InstalledWorkerObservation.EXACT_READY
                }
            val control = fixture.control(effects, this)
            val first = async { control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()) }
            runCurrent()
            assertTrue(entered.isCompleted, "control did not invoke owned launcher")
            val second = async { control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()) }
            first.cancelAndJoin()
            release.complete(Unit)
            assertInstanceOf(InstalledWorkerStart.Ready::class.java, second.await())
            assertEquals(1, fixture.starts.get())
            control.drain()
        }

    @Test
    fun `shared startup rejects a different pending lifecycle intent`(@TempDir directory: Path) = runTest {
        val fixture = Fixture(directory)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val effects =
            object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                    entered.complete(Unit)
                    release.await()
                    return InstalledWorkerStart.Rejected(WorkerControlFailure.STARTUP_REJECTED)
                }
            }
        val control = fixture.control(effects, this)
        val first = async { control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()) }
        runCurrent()
        assertTrue(entered.isCompleted, "shared startup did not enter the worker effect")
        try {
            val conflicting = control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Rebuild())
            assertEquals(WorkerControlFailure.IDENTITY_REJECTED, (conflicting as InstalledWorkerStart.Rejected).failure)
        } finally {
            release.complete(Unit)
            first.await()
            control.drain()
        }
    }

    @Test
    fun `default reuse joins pending explicitly selected IDE without replacing its startup`(@TempDir directory: Path) =
        runTest {
            val fixture = Fixture(directory)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val selectedIde = Files.createDirectory(directory.resolve("ide")).toRealPath()
            var starts = 0
            val effects =
                object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                    override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                        assertEquals(selectedIde, request.startup.ideHome)
                        starts++
                        entered.complete(Unit)
                        release.await()
                        return InstalledWorkerStart.Ready(fixture.endpoint(request.root))
                    }

                    override suspend fun observe(route: InstalledWorkerEndpoint) =
                        InstalledWorkerObservation.EXACT_READY

                    override suspend fun stop(route: InstalledWorkerEndpoint) = InstalledWorkerRetirement.EXACT_RETIRED
                }
            val control = fixture.control(effects, this)
            val first = async { control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse(selectedIde)) }
            runCurrent()
            assertTrue(entered.isCompleted, "selected startup did not enter the worker effect")
            val joined = async { control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()) }
            try {
                runCurrent()
                release.complete(Unit)
                assertInstanceOf(InstalledWorkerStart.Ready::class.java, first.await())
                assertInstanceOf(
                    InstalledWorkerStart.Ready::class.java,
                    joined.await(),
                    "default reuse rejected the pending selected IDE",
                )
                assertEquals(1, starts)
            } finally {
                release.complete(Unit)
                first.await()
                joined.await()
                control.drain()
            }
        }

    @Test
    fun `ready reuse rejects a conflicting explicit IDE selection`(@TempDir directory: Path) = runTest {
        val fixture = Fixture(directory)
        val selectedIde = Files.createDirectory(directory.resolve("ide-one")).toRealPath()
        val otherIde = Files.createDirectory(directory.resolve("ide-two")).toRealPath()
        var starts = 0
        val effects =
            object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                    starts++
                    return InstalledWorkerStart.Ready(fixture.endpoint(request.root))
                }

                override suspend fun observe(route: InstalledWorkerEndpoint) = InstalledWorkerObservation.EXACT_READY

                override suspend fun stop(route: InstalledWorkerEndpoint) = InstalledWorkerRetirement.EXACT_RETIRED
            }
        val control = fixture.control(effects, this)
        try {
            assertInstanceOf(
                InstalledWorkerStart.Ready::class.java,
                control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse(selectedIde)),
            )
            assertEquals(
                InstalledWorkerStart.Rejected(WorkerControlFailure.IDENTITY_REJECTED),
                control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse(otherIde)),
            )
            assertEquals(1, starts)
        } finally {
            control.drain()
        }
    }

    @Test
    fun `lost ready worker is exactly retired and restarted by the same demand`(@TempDir directory: Path) = runTest {
        val fixture = Fixture(directory)
        var observations = 0
        var starts = 0
        var stops = 0
        val effects =
            object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                    starts++
                    return InstalledWorkerStart.Ready(fixture.endpoint(request.root))
                }

                override suspend fun observe(route: InstalledWorkerEndpoint) =
                    if (++observations == 3) InstalledWorkerObservation.UNPROVEN
                    else InstalledWorkerObservation.EXACT_READY

                override suspend fun stop(route: InstalledWorkerEndpoint): InstalledWorkerRetirement {
                    stops++
                    return InstalledWorkerRetirement.EXACT_RETIRED
                }
            }
        val control = fixture.control(effects, this)
        try {
            assertInstanceOf(
                InstalledWorkerStart.Ready::class.java,
                control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()),
            )
            assertInstanceOf(
                InstalledWorkerStart.Ready::class.java,
                control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()),
            )
            assertEquals(2, starts)
            assertEquals(1, stops)
            assertEquals(1L, Files.list(directory.resolve("state/workers")).use { it.count() })
        } finally {
            control.drain()
        }
    }

    @Test
    fun `lost ready worker stays fenced when exact retirement cannot be proved`(@TempDir directory: Path) = runTest {
        val fixture = Fixture(directory)
        var observations = 0
        var starts = 0
        var stops = 0
        val effects =
            object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                    starts++
                    return InstalledWorkerStart.Ready(fixture.endpoint(request.root))
                }

                override suspend fun observe(route: InstalledWorkerEndpoint) =
                    if (++observations == 3) InstalledWorkerObservation.UNPROVEN
                    else InstalledWorkerObservation.EXACT_READY

                override suspend fun stop(route: InstalledWorkerEndpoint): InstalledWorkerRetirement {
                    stops++
                    return InstalledWorkerRetirement.UNPROVEN
                }
            }
        val control = fixture.control(effects, this)
        try {
            assertInstanceOf(
                InstalledWorkerStart.Ready::class.java,
                control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()),
            )
            assertEquals(
                InstalledWorkerStart.Rejected(WorkerControlFailure.RETIREMENT_UNPROVEN),
                control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()),
            )
            assertEquals(
                InstalledWorkerStart.Rejected(WorkerControlFailure.RECOVERY_REQUIRED),
                control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()),
            )
            assertEquals(1, starts)
            assertEquals(1, stops)
        } finally {
            control.drain()
        }
    }

    @Test
    fun `failed unpublished startup retires only through exact launch authority`(@TempDir directory: Path) = runTest {
        val fixture = Fixture(directory)
        var retirements = 0
        val effects =
            object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                override suspend fun start(request: InstalledWorkerStartRequest) =
                    InstalledWorkerStart.Rejected(WorkerControlFailure.STARTUP_REJECTED)

                override suspend fun retireUnpublished(root: Path): InstalledWorkerRetirement {
                    assertEquals(fixture.root, root)
                    retirements++
                    return InstalledWorkerRetirement.EXACT_RETIRED
                }
            }
        val control = fixture.control(effects, this)
        assertInstanceOf(
            InstalledWorkerStart.Rejected::class.java,
            control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()),
        )
        assertInstanceOf(
            InstalledWorkerStop.Stopped::class.java,
            control.stop(fixture.root),
            "failed startup could not be retired by exact owned launch authority",
        )
        assertEquals(1, retirements)
        assertEquals(0L, Files.list(directory.resolve("state/workers")).use { it.count() })
        control.drain()
    }

    @Test
    fun `interactive seed passes measured disclosure to original frontend before granting copy`(
        @TempDir directory: Path
    ) = runTest {
        val fixture = Fixture(directory)
        var approvals = 0
        val effects =
            object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                    val disclosure =
                        WorkerSeedDisclosure.admit(
                                setOf(WorkerSeedCategory.GLOBAL_VFS, WorkerSeedCategory.GLOBAL_INDEXES),
                                1234,
                            )
                            .refined()
                    if (request.consentAuthority.request(disclosure) == WorkerSeedConsent.GRANTED) approvals++
                    return InstalledWorkerStart.Rejected(WorkerControlFailure.STARTUP_REJECTED)
                }
            }
        val control = fixture.control(effects, this)
        var prompts = 0
        val authority = WorkerSeedConsentAuthority { disclosure ->
            assertEquals(1234L, disclosure.estimatedBytes)
            assertEquals(2, disclosure.categories.size)
            prompts++
            WorkerSeedConsent.GRANTED
        }
        control.demand(
            fixture.root,
            fixture.heap,
            InstalledWorkerStartup.Seed(null, null, WorkerSeedConsentSelection.INTERACTIVE),
            authority,
        )
        assertEquals(1, prompts, "interactive seed disclosure never reached original frontend")
        assertEquals(1, approvals)
        control.drain()
    }

    @Test
    fun `worker launch retains selected workspace overlay and source provenance`(@TempDir directory: Path) = runTest {
        val fixture = Fixture(directory)
        val workspaceKey =
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(fixture.root.toString().toByteArray())
                )
        val overlay = directory.resolve("config/workspaces/$workspaceKey/environment")
        Files.createDirectories(overlay.parent)
        Files.writeString(overlay, "KAST_INDEXER_MAX_HEAP=2g\n")
        var selected: ResolvedKastConfiguration? = null
        val effects =
            object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                    selected = request.configuration
                    return InstalledWorkerStart.Rejected(WorkerControlFailure.STARTUP_REJECTED)
                }

                override suspend fun retireUnpublished(root: Path) = InstalledWorkerRetirement.EXACT_RETIRED
            }
        val control = fixture.control(effects, this)
        control.demand(fixture.root, IndexerHeapSize.parse("2g").refined(), InstalledWorkerStartup.Reuse())
        val assignment = requireNotNull(selected).inspection().single { it.key == "KAST_INDEXER_MAX_HEAP" }
        assertEquals(
            ConfigurationSource.SAVED_WORKSPACE,
            assignment.source,
            "worker launch erased workspace configuration provenance",
        )
        assertEquals("2048m", assignment.value)
        control.drain()
    }

    @Test
    fun `explicit rebuild retires previous worker before admitting changed workspace heap`(@TempDir directory: Path) =
        runTest {
            val fixture = Fixture(directory)
            val workspaceKey =
                java.util.HexFormat.of()
                    .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(fixture.root.toString().toByteArray())
                    )
            val overlay = directory.resolve("config/workspaces/$workspaceKey/environment")
            Files.createDirectories(overlay.parent)
            Files.writeString(overlay, "KAST_INDEXER_MAX_HEAP=2g\n")
            val events = mutableListOf<String>()
            val effects =
                object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                    override suspend fun start(request: InstalledWorkerStartRequest): InstalledWorkerStart {
                        events += "start:${request.heap.mebibytes}"
                        return InstalledWorkerStart.Ready(fixture.endpoint(request.root))
                    }

                    override suspend fun observe(route: InstalledWorkerEndpoint) =
                        InstalledWorkerObservation.EXACT_READY

                    override suspend fun stop(route: InstalledWorkerEndpoint): InstalledWorkerRetirement {
                        events += "stop"
                        return InstalledWorkerRetirement.EXACT_RETIRED
                    }
                }
            val control = fixture.control(effects, this)
            try {
                assertInstanceOf(
                    InstalledWorkerStart.Ready::class.java,
                    control.demand(fixture.root, IndexerHeapSize.parse("2g").refined(), InstalledWorkerStartup.Reuse()),
                )
                Files.writeString(overlay, "KAST_INDEXER_MAX_HEAP=4g\n")
                assertInstanceOf(
                    InstalledWorkerStart.Rejected::class.java,
                    control.demand(fixture.root, IndexerHeapSize.parse("4g").refined(), InstalledWorkerStartup.Reuse()),
                )
                assertEquals(listOf("start:2048"), events)
                assertInstanceOf(
                    InstalledWorkerStart.Ready::class.java,
                    control.demand(
                        fixture.root,
                        IndexerHeapSize.parse("4g").refined(),
                        InstalledWorkerStartup.Rebuild(),
                    ),
                    "explicit rebuild failed to retire previous reservation before memory admission",
                )
                assertEquals(listOf("start:2048", "stop", "start:4096"), events)
            } finally {
                control.drain()
            }
        }

    @Test
    fun `registered root without reservation stops idempotently under root admission fence`(@TempDir directory: Path) =
        runTest {
            val fixture = Fixture(directory)
            WorkspaceEnrollmentStore(directory.toRealPath().resolve("config/workspaces.json"))
                .enroll(fixture.root)
                .refined()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var stops = 0
            val effects =
                object : InstalledWorkerEffects by InstalledWorkerEffects.Unavailable {
                    override suspend fun retireUnreserved(root: Path): InstalledWorkerRetirement {
                        assertEquals(fixture.root, root)
                        stops++
                        entered.complete(Unit)
                        release.await()
                        return InstalledWorkerRetirement.EXACT_RETIRED
                    }
                }
            val control = fixture.control(effects, this)
            val stopped = async { control.stop(fixture.root) }
            try {
                runCurrent()
                assertTrue(entered.isCompleted, "registered empty root never reached exact retirement authority")
                assertInstanceOf(
                    InstalledWorkerStart.Rejected::class.java,
                    control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()),
                    "new admission outran exact root retirement",
                )
                release.complete(Unit)
                assertInstanceOf(InstalledWorkerStop.Stopped::class.java, stopped.await())
                assertInstanceOf(InstalledWorkerStop.Stopped::class.java, control.stop(fixture.root))
                assertEquals(2, stops)
            } finally {
                release.complete(Unit)
                stopped.await()
                control.drain()
            }
        }

    @Test
    fun `unproven empty root retirement retains its root admission fence`(@TempDir directory: Path) = runTest {
        val fixture = Fixture(directory)
        WorkspaceEnrollmentStore(directory.toRealPath().resolve("config/workspaces.json"))
            .enroll(fixture.root)
            .refined()
        val control = fixture.control(InstalledWorkerEffects.Unavailable, this)
        try {
            assertEquals(
                InstalledWorkerStop.Rejected(WorkerControlFailure.RETIREMENT_UNPROVEN),
                control.stop(fixture.root),
            )
            assertEquals(
                InstalledWorkerStart.Rejected(WorkerControlFailure.RECOVERY_REQUIRED),
                control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse()),
            )
        } finally {
            control.drain()
        }
    }

    @Test
    fun `existing uncertain receipt prevents coordinator from forgetting resident ownership`(@TempDir directory: Path) {
        val fixture = Fixture(directory)
        Files.createDirectories(directory.resolve("state/workers"))
        Files.writeString(directory.resolve("state/workers/uncertain.json"), "{}")
        assertInstanceOf(
            Refinement.Rejected::class.java,
            fixture.create(InstalledWorkerEffects.Unavailable),
            "restart must not forget unresolved worker receipt",
        )
    }

    @Test
    fun `lifecycle fence blocks launch from already running control`(@TempDir directory: Path) = runTest {
        val fixture = Fixture(directory)
        val control = fixture.control(InstalledWorkerEffects.Unavailable, this)
        Files.writeString(directory.resolve(".lifecycle-transition.json"), "{}")
        assertEquals(
            WorkerControlFailure.LIFECYCLE_TRANSITION,
            (control.demand(fixture.root, fixture.heap, InstalledWorkerStartup.Reuse())
                    as InstalledWorkerStart.Rejected)
                .failure,
        )
        control.drain()
    }

    private class Fixture(val directory: Path) {
        val root =
            Files.createDirectories(directory.resolve("workspace"))
                .also { Files.writeString(it.resolve("settings.gradle.kts"), "") }
                .toRealPath()
        val heap = IndexerHeapSize.parse("1g").refined()
        val starts = AtomicInteger()
        val owner = ThreadBindingOwner.admit("installation", "00000000-0000-0000-0000-000000000001").refined()
        val configuration = ResolvedKastConfiguration.resolve(ConfigurationSources()).refined()

        fun endpoint(root: Path) =
            InstalledWorkerEndpoint.admit(
                    root,
                    SemanticRuntimeId.parse("sha256:" + "a".repeat(64)).refined(),
                    root.resolve("runtime.sock"),
                    SemanticRuntimeBootstrapAttemptId.admit("00000000-0000-4000-8000-000000000001").refined(),
                )
                .refined()

        fun create(effects: InstalledWorkerEffects, workerDispatcher: CoroutineDispatcher = Dispatchers.IO) =
            WorkspaceRuntimeControl.create(
                directory.toRealPath(),
                owner,
                BrokerServiceGeneration.fresh(),
                configuration,
                effects,
                workerDispatcher = workerDispatcher,
            )

        fun control(effects: InstalledWorkerEffects, scope: TestScope) =
            create(effects, StandardTestDispatcher(scope.testScheduler)).refined()
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Rejected: $failure")
    }
