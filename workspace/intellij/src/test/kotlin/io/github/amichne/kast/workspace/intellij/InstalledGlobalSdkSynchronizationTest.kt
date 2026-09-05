package io.github.amichne.kast.workspace.intellij

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InstalledGlobalSdkSynchronizationTest {
    @Test
    fun `bootstrap cannot proceed while cached SDK state is still synchronizing`() = runBlocking {
        val synchronized = CompletableDeferred<Unit>()
        val bootstrap = async {
            synchronizeInstalledGlobalSdkModel { synchronized.await() }
        }
        yield()
        assertFalse(bootstrap.isCompleted)
        synchronized.complete(Unit)
        assertEquals(InstalledGlobalSdkSynchronization.SYNCHRONIZED, bootstrap.await())
    }

    @Test
    fun `SDK readiness requires successful JPS synchronization`() = runBlocking {
        val events = mutableListOf<String>()
        val outcome = synchronizeInstalledGlobalSdkModel { events += "jps-synchronized" }
        assertEquals(InstalledGlobalSdkSynchronization.SYNCHRONIZED, outcome)
        assertEquals(listOf("jps-synchronized"), events)
    }

    @Test
    fun `SDK synchronization failures remain closed and cancellation propagates`() = runBlocking {
        assertEquals(InstalledGlobalSdkSynchronization.PLATFORM_UNAVAILABLE,
            synchronizeInstalledGlobalSdkModel { throw IllegalStateException("private payload") })
        assertEquals(InstalledGlobalSdkSynchronization.PLATFORM_LINKAGE_INVALID,
            synchronizeInstalledGlobalSdkModel { throw NoClassDefFoundError("private payload") })
        assertEquals(InstalledGlobalSdkSynchronization.TIMED_OUT,
            synchronizeInstalledGlobalSdkModel { withTimeout(1) { awaitCancellation() } })
        assertThrows(CancellationException::class.java) {
            runBlocking { synchronizeInstalledGlobalSdkModel { throw CancellationException("cancelled") } }
        }
    }
}
