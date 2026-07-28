package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.ServerMaxResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

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
