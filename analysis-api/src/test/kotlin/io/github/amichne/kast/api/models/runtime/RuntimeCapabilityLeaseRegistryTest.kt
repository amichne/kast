package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.client.RuntimeInstanceId
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeCapabilityLeaseRegistryTest {
    @Test
    fun `request and continuation must both drain before fixed grace issues one permit`() {
        val scheduler = FakeLeaseScheduler()
        val epoch = RuntimeInstanceId.create()
        val registry = RuntimeCapabilityLeaseRegistry(
            epoch = epoch,
            scheduler = scheduler,
        )
        val permits = mutableListOf<RuntimeStopPermit>()
        registry.onStopPermit(permits::add)

        val request = registry.acquire(RuntimeCapabilityLeaseKind.REQUEST)
        val continuation = registry.acquire(RuntimeCapabilityLeaseKind.CONTINUATION)
        request.close()
        assertTrue(scheduler.pending.isEmpty())
        continuation.close()

        assertEquals(Duration.ofMinutes(5), scheduler.pending.single().delay)
        scheduler.fire()
        assertEquals(listOf(epoch), permits.map { it.epoch })
    }

    @Test
    fun `new demand supersedes a grace permit`() {
        val scheduler = FakeLeaseScheduler()
        val registry = RuntimeCapabilityLeaseRegistry(
            epoch = RuntimeInstanceId.create(),
            scheduler = scheduler,
        )
        val permits = mutableListOf<RuntimeStopPermit>()
        registry.onStopPermit(permits::add)

        registry.acquire(RuntimeCapabilityLeaseKind.REQUEST).close()
        val stale = scheduler.pending.single()
        val replacement = registry.acquire(RuntimeCapabilityLeaseKind.REQUEST)
        stale.action()
        assertTrue(permits.isEmpty())
        replacement.close()
        scheduler.fire()
        assertEquals(1, permits.size)
    }

    @Test
    fun `new demand supersedes an already issued stop permit before shutdown admission`() {
        val scheduler = FakeLeaseScheduler()
        val registry = RuntimeCapabilityLeaseRegistry(
            epoch = RuntimeInstanceId.create(),
            scheduler = scheduler,
        )
        var issued: RuntimeStopPermit? = null
        registry.onStopPermit { issued = it }

        registry.acquire(RuntimeCapabilityLeaseKind.REQUEST).close()
        scheduler.fire()
        val replacement = registry.acquire(RuntimeCapabilityLeaseKind.REQUEST)

        assertEquals(
            RuntimeStopPermitAdmission.Superseded,
            registry.admitStop(checkNotNull(issued)),
        )
        replacement.close()
    }

    private class FakeLeaseScheduler : RuntimeLeaseScheduler {
        val pending = mutableListOf<Scheduled>()

        override fun schedule(delay: Duration, action: () -> Unit): RuntimeLeaseSchedule {
            val scheduled = Scheduled(delay, action)
            pending += scheduled
            return RuntimeLeaseSchedule { scheduled.cancelled = true }
        }

        fun fire() {
            val scheduled = pending.last { !it.cancelled }
            scheduled.action()
        }

        data class Scheduled(
            val delay: Duration,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )
    }
}
