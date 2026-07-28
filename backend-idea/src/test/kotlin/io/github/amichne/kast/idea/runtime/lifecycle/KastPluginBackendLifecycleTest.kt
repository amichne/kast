package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.ServerMaxResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class KastPluginBackendLifecycleTest {
    private val workspaceRoot = Path.of("/workspace").toAbsolutePath().normalize()

    @Test
    fun `pending Gradle admission survives restart until Gradle completes`() {
        val fixture = LifecycleFixture()

        fixture.lifecycle.start(
            workspaceRoot = workspaceRoot,
            config = KastConfig.defaults(),
            initialAdmission = KastGradleIndexAdmission.Pending,
        )
        fixture.lifecycle.restart(workspaceRoot, KastConfig.defaults())

        assertEquals(KastPluginBackendLifecycleStatus.STOPPING, fixture.lifecycle.status())
        assertEquals(listOf(KastGradleIndexAdmission.Pending), fixture.starts.map(KastPluginBackendStart::admission))

        fixture.handles.single().closeCompletion.complete(Unit)

        assertEquals(KastPluginBackendLifecycleStatus.RUNNING, fixture.lifecycle.status())
        assertEquals(
            listOf(KastGradleIndexAdmission.Pending, KastGradleIndexAdmission.Pending),
            fixture.starts.map(KastPluginBackendStart::admission),
        )
    }

    @Test
    fun `ready Gradle admission survives restart without starting before the old drain completes`() {
        val fixture = LifecycleFixture()
        fixture.lifecycle.start(
            workspaceRoot = workspaceRoot,
            config = KastConfig.defaults(),
            initialAdmission = KastGradleIndexAdmission.Pending,
        )
        val firstHandle = fixture.handles.single()
        fixture.lifecycle.markIndexReady()

        fixture.lifecycle.restart(workspaceRoot, KastConfig.defaults())

        assertEquals(1, firstHandle.startIndexingCount)
        assertEquals(1, fixture.starts.size)
        firstHandle.closeCompletion.complete(Unit)
        assertEquals(
            listOf(KastGradleIndexAdmission.Pending, KastGradleIndexAdmission.Ready),
            fixture.starts.map(KastPluginBackendStart::admission),
        )
    }

    @Test
    fun `failed Gradle admission survives a config reload restart`() {
        val fixture = LifecycleFixture()
        val failure = IllegalStateException("Gradle import failed")
        val defaults = KastConfig.defaults()
        fixture.lifecycle.start(
            workspaceRoot = workspaceRoot,
            config = defaults,
            initialAdmission = KastGradleIndexAdmission.Pending,
        )
        val firstHandle = fixture.handles.single()
        fixture.lifecycle.markIndexFailed(failure)
        val changed = defaults.copy(
            server = defaults.server.copy(maxResults = ServerMaxResults(17)),
        )

        assertEquals(
            KastConfigReloadDecision.RESTART_BACKEND,
            fixture.lifecycle.reload(workspaceRoot, changed),
        )
        assertSame(failure, firstHandle.failedIndexing.single())
        assertEquals(1, fixture.starts.size)

        firstHandle.closeCompletion.complete(Unit)

        val restartedAdmission = fixture.starts.last().admission
        assertTrue(restartedAdmission is KastGradleIndexAdmission.Failed)
        assertSame(failure, (restartedAdmission as KastGradleIndexAdmission.Failed).error)
    }

    @Test
    fun `dynamic unload initiates one drain and remains vetoed until stop completes`() {
        val fixture = LifecycleFixture()
        fixture.lifecycle.start(workspaceRoot, KastConfig.defaults())
        val handle = fixture.handles.single()

        assertFalse(fixture.lifecycle.prepareForDynamicUnload())
        assertEquals(KastPluginBackendLifecycleStatus.STOPPING, fixture.lifecycle.status())
        assertFalse(fixture.lifecycle.prepareForDynamicUnload())
        assertEquals(1, handle.closeCount)
        assertEquals(emptyList<Path>(), fixture.stopped)

        handle.closeCompletion.complete(Unit)

        assertEquals(listOf(workspaceRoot), fixture.stopped)
        assertEquals(KastPluginBackendLifecycleStatus.STOPPED, fixture.lifecycle.status())
        assertTrue(fixture.lifecycle.prepareForDynamicUnload())
    }

    @Test
    fun `dynamic unload veto prepares every open Kast project and ignores other plugins`() {
        var firstReady = false
        var secondReady = false
        var calls = 0
        val prepareServices = listOf<() -> Boolean>(
            {
                calls += 1
                firstReady
            },
            {
                calls += 1
                secondReady
            },
        )

        assertNull(dynamicPluginUnloadVetoReason("other.plugin", prepareServices))
        assertEquals(0, calls)
        assertEquals(KAST_PLUGIN_DRAINING_UNLOAD_MESSAGE, dynamicPluginUnloadVetoReason(KAST_PLUGIN_ID, prepareServices))
        assertEquals(2, calls)

        firstReady = true
        secondReady = true
        assertNull(dynamicPluginUnloadVetoReason(KAST_PLUGIN_ID, prepareServices))
        assertEquals(4, calls)
    }

    @Test
    fun `dynamic unload veto never waits for a concurrent lifecycle transition`() {
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val handle = FakeBackendHandle()
        val lifecycle = KastPluginBackendLifecycle(
            startBackend = {
                startEntered.countDown()
                assertTrue(releaseStart.await(5, TimeUnit.SECONDS))
                handle
            },
        )
        val starter = thread(isDaemon = true) {
            lifecycle.start(workspaceRoot, KastConfig.defaults())
        }
        assertTrue(startEntered.await(5, TimeUnit.SECONDS))

        assertFalse(lifecycle.prepareForDynamicUnload())

        releaseStart.countDown()
        starter.join(5_000)
        assertFalse(starter.isAlive)
        assertFalse(lifecycle.prepareForDynamicUnload())
        handle.closeCompletion.complete(Unit)
        assertTrue(lifecycle.prepareForDynamicUnload())
    }

    private class LifecycleFixture {
        val starts = mutableListOf<KastPluginBackendStart>()
        val handles = mutableListOf<FakeBackendHandle>()
        val stopped = mutableListOf<Path>()
        val lifecycle = KastPluginBackendLifecycle(
            startBackend = { request ->
                starts += request
                FakeBackendHandle().also(handles::add)
            },
            onStopCompleted = { workspaceRoot, failure ->
                require(failure == null)
                stopped.add(workspaceRoot)
            },
        )
    }

    private class FakeBackendHandle : KastIdeaBackendHandle {
        val closeCompletion = CompletableFuture<Unit>()
        val failedIndexing = mutableListOf<Throwable>()
        var startIndexingCount = 0
            private set
        var closeCount = 0
            private set

        override fun startIndexing() {
            startIndexingCount += 1
        }

        override fun failIndexing(error: Throwable) {
            failedIndexing += error
        }

        override fun closeAsync(): CompletableFuture<Unit> {
            closeCount += 1
            return closeCompletion
        }
    }
}
