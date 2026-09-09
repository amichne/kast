package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerServiceGeneration
import io.github.amichne.kast.appserver.WorkspaceRegistration
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceWorkerAdmissionTest {
    @Test fun `concurrent demand shares one reservation and waiter cancellation preserves startup`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root)
        val requests = List(24) { async(Dispatchers.Default) { fixture.coordinator.request(fixture.first, fixture.memory) } }.awaitAll()
        assertEquals(1, requests.filterIsInstance<WorkerDemand.Start>().size, "concurrent root demand must produce exactly one launch reservation")
        val start = requests.filterIsInstance<WorkerDemand.Start>().single()
        requests.filterIsInstance<WorkerDemand.Awaiting>().forEach { assertSame(start.readiness, it.readiness) }
        val waiting = CompletableDeferred<Unit>()
        val cancelled = launch { waiting.complete(Unit); start.readiness.await() }
        waiting.await(); cancelled.cancelAndJoin()
        val surviving = async { start.readiness.await() }
        val permit = fixture.coordinator.consume(start.reservation.id, fixture.first).refined()
        val ready = fixture.coordinator.publishReady(permit, fixture.attempt).refined()
        assertSame(ready, (withTimeout(1_000) { surviving.await() } as WorkerReadiness.Ready).route)
        assertSame(ready, (fixture.coordinator.request(fixture.first, fixture.memory) as WorkerDemand.Ready).route)
        assertEquals(1, fixture.coordinator.snapshot().workers.size)
        assertEquals(0, fixture.coordinator.snapshot().starting)
    }

    @Test fun `resident startup and aggregate reservations enforce independent caps`(@TempDir root: Path) {
        val fixture = Fixture(root)
        val resident = fixture.coordinator(1, 1, 10_000)
        val first = resident.request(fixture.first, fixture.memory) as WorkerDemand.Start
        resident.publishReady(resident.consume(first.reservation.id, fixture.first).refined(), fixture.attempt).refined()
        assertEquals(WorkerAdmissionFailure.RESIDENT_CAPACITY_EXCEEDED, (resident.request(fixture.second, fixture.memory) as WorkerDemand.Rejected).failure)
        val startup = fixture.coordinator(2, 1, 10_000)
        val starting = startup.request(fixture.first, fixture.memory) as WorkerDemand.Start
        assertEquals(WorkerAdmissionFailure.STARTUP_CAPACITY_EXCEEDED, (startup.request(fixture.second, fixture.memory) as WorkerDemand.Rejected).failure)
        startup.publishReady(startup.consume(starting.reservation.id, fixture.first).refined(), fixture.attempt).refined()
        assertInstanceOf(WorkerDemand.Start::class.java, startup.request(fixture.second, fixture.memory))
        assertEquals(2 * fixture.memory.total.value, startup.snapshot().reserved.value)
        val aggregate = fixture.coordinator(2, 2, 2 * fixture.memory.total.value - 1)
        assertInstanceOf(WorkerDemand.Start::class.java, aggregate.request(fixture.first, fixture.memory))
        assertEquals(WorkerAdmissionFailure.AGGREGATE_RESERVATION_EXCEEDED, (aggregate.request(fixture.second, fixture.memory) as WorkerDemand.Rejected).failure)
        assertEquals(fixture.memory.total, aggregate.snapshot().reserved)
    }

    @Test fun `launch consumption is bound to installation epoch generation and exact workspace`(@TempDir root: Path) {
        val fixture = Fixture(root)
        val otherOwner = ThreadBindingOwner.admit("installation", "00000000-0000-0000-0000-000000000002").refined()
        assertEquals(WorkerAdmissionFailure.INSTALLATION_EPOCH_MISMATCH, (fixture.coordinator.request(fixture.first.copy(owner = otherOwner), fixture.memory) as WorkerDemand.Rejected).failure)
        assertEquals(WorkerAdmissionFailure.SERVICE_GENERATION_MISMATCH, (fixture.coordinator.request(fixture.first.copy(serviceGeneration = BrokerServiceGeneration.fresh()), fixture.memory) as WorkerDemand.Rejected).failure)
        val start = fixture.coordinator.request(fixture.first, fixture.memory) as WorkerDemand.Start
        assertEquals(WorkerAdmissionFailure.RESERVATION_IDENTITY_MISMATCH, (fixture.coordinator.consume(start.reservation.id, fixture.second) as Refinement.Rejected).failure)
        val permit = fixture.coordinator.consume(start.reservation.id, fixture.first).refined()
        assertEquals(WorkerAdmissionFailure.RESERVATION_ALREADY_CONSUMED, (fixture.coordinator.consume(start.reservation.id, fixture.first) as Refinement.Rejected).failure)
        assertEquals(WorkerAdmissionFailure.PUBLICATION_REJECTED, (fixture.coordinator.publishReady(WorkerLaunchPermit(start.reservation), fixture.attempt) as Refinement.Rejected).failure)
        val route = fixture.coordinator.publishReady(permit, fixture.attempt).refined()
        assertEquals(fixture.first, route.reservation.identity)
        assertEquals(fixture.attempt, route.bootstrapAttempt)
    }

    @Test fun `uncertain startup keeps capacity reserved until exact retirement`(@TempDir root: Path): Unit = runBlocking {
        val fixture = Fixture(root)
        val start = fixture.coordinator.request(fixture.first, fixture.memory) as WorkerDemand.Start
        fixture.coordinator.consume(start.reservation.id, fixture.first).refined()
        fixture.coordinator.quarantine(start.reservation, WorkerAdmissionFailure.STARTUP_FAILED).refined()
        assertEquals(WorkerAdmissionFailure.STARTUP_FAILED, (start.readiness.await() as WorkerReadiness.Rejected).failure)
        assertEquals(WorkerAdmissionFailure.RECOVERY_REQUIRED, (fixture.coordinator.request(fixture.first, fixture.memory) as WorkerDemand.Rejected).failure)
        assertEquals(WorkerAdmissionFailure.STARTUP_CAPACITY_EXCEEDED, (fixture.coordinator.request(fixture.second, fixture.memory) as WorkerDemand.Rejected).failure)
        assertEquals(WorkerAdmissionFailure.RETIREMENT_UNPROVEN, (fixture.coordinator.retire(start.reservation) { WorkerRetirementObservation.Unproven } as Refinement.Rejected).failure)
        assertEquals(1, fixture.coordinator.snapshot().workers.size)
        assertEquals(1, fixture.coordinator.snapshot().starting)
        val unrelated = WorkerReservation(WorkerReservationId.fresh(), fixture.first, fixture.memory)
        assertEquals(WorkerAdmissionFailure.RETIREMENT_UNPROVEN, (fixture.coordinator.retire(start.reservation) { WorkerRetirementObservation.ExactRetired(unrelated) } as Refinement.Rejected).failure)
        fixture.coordinator.retire(start.reservation) { WorkerRetirementObservation.ExactRetired(it) }.refined()
        assertEquals(0, fixture.coordinator.snapshot().workers.size)
        assertEquals(WorkerMemoryMiB.Zero, fixture.coordinator.snapshot().reserved)
        assertInstanceOf(WorkerDemand.Start::class.java, fixture.coordinator.request(fixture.second, fixture.memory))
    }

    @Test fun `ready quarantine keeps resident accounting without blocking unrelated startup`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root)
        val start = fixture.coordinator.request(fixture.first, fixture.memory) as WorkerDemand.Start
        fixture.coordinator.publishReady(fixture.coordinator.consume(start.reservation.id, fixture.first).refined(), fixture.attempt).refined()
        fixture.coordinator.quarantine(start.reservation, WorkerAdmissionFailure.WORKER_LOST).refined()
        assertEquals(0, fixture.coordinator.snapshot().starting)
        assertInstanceOf(WorkerDemand.Start::class.java, fixture.coordinator.request(fixture.second, fixture.memory))
        assertEquals(2, fixture.coordinator.snapshot().workers.size)
        val changed = DeclaredWorkerMemory.admit(IndexerHeapSize.parse("2g").refined(), 256, 256).refined()
        assertEquals(WorkerAdmissionFailure.RECOVERY_REQUIRED, (fixture.coordinator.request(fixture.first, changed) as WorkerDemand.Rejected).failure)
        fixture.coordinator.retire(start.reservation) { WorkerRetirementObservation.ExactRetired(it) }.refined()
        assertEquals(1, fixture.coordinator.snapshot().workers.size)
    }

    @Test fun `resource requests reject silent reconfiguration and invalid arithmetic`(@TempDir root: Path) {
        val fixture = Fixture(root)
        assertInstanceOf(WorkerDemand.Start::class.java, fixture.coordinator.request(fixture.first, fixture.memory))
        val changed = DeclaredWorkerMemory.admit(IndexerHeapSize.parse("2g").refined(), 256, 256).refined()
        assertEquals(WorkerAdmissionFailure.RESOURCE_CONFIGURATION_CONFLICT, (fixture.coordinator.request(fixture.first, changed) as WorkerDemand.Rejected).failure)
        assertEquals(WorkerPolicyFailure.MEMORY_OVERFLOW, (DeclaredWorkerMemory.admit(IndexerHeapSize.Default, Long.MAX_VALUE, 0) as Refinement.Rejected).failure)
        assertEquals(WorkerPolicyFailure.MEMORY_REJECTED, (DeclaredWorkerMemory.admit(IndexerHeapSize.Default, -1, 0) as Refinement.Rejected).failure)
        assertEquals(WorkerPolicyFailure.STARTUP_LIMIT_REJECTED, (WorkerAdmissionPolicy.admit(1, 2, 10_000) as Refinement.Rejected).failure)
    }

    @Test fun `reservation identity comes from the explicit deterministic source`(@TempDir root: Path) {
        val fixture = Fixture(root)
        val ledger = fixture.coordinator(2, 2, 10_000)
        val first = ledger.request(fixture.first, fixture.memory) as WorkerDemand.Start
        val same = ledger.request(fixture.first, fixture.memory)
        val second = ledger.request(fixture.second, fixture.memory) as WorkerDemand.Start
        assertInstanceOf(WorkerDemand.Awaiting::class.java, same)
        assertEquals(java.util.UUID(0, 1), first.reservation.id.value)
        assertEquals(java.util.UUID(0, 2), second.reservation.id.value)
    }

    @Test fun `identity source collision rejects before charging another workspace`(@TempDir root: Path) {
        val fixture = Fixture(root)
        val fixed = WorkerReservationId.admit(java.util.UUID(0, 1).toString()).refined()
        val ledger = WorkerAdmissionCoordinator(fixture.owner, fixture.generation, WorkerAdmissionPolicy.admit(2, 2, 10_000).refined(), WorkerReservationIdSource { fixed })
        assertInstanceOf(WorkerDemand.Start::class.java, ledger.request(fixture.first, fixture.memory))
        val second = ledger.request(fixture.second, fixture.memory)
        assertTrue(second is WorkerDemand.Rejected, "colliding reservation id admitted twice")
        assertEquals(WorkerAdmissionFailure.RESERVATION_IDENTITY_MISMATCH, (second as WorkerDemand.Rejected).failure)
        assertEquals(1, ledger.snapshot().workers.size)
        assertEquals(fixture.memory.total, ledger.snapshot().reserved)
    }

    private class Fixture(root: Path) {
        val owner = ThreadBindingOwner.admit("installation", "00000000-0000-0000-0000-000000000001").refined()
        val generation = BrokerServiceGeneration.fresh()
        val first = WorkspaceWorkerIdentity(owner, generation, WorkspaceRegistration(CanonicalBrokerDirectory.admit(Files.createDirectory(root.resolve("first")).toRealPath())!!))
        val second = WorkspaceWorkerIdentity(owner, generation, WorkspaceRegistration(CanonicalBrokerDirectory.admit(Files.createDirectory(root.resolve("second")).toRealPath())!!))
        val memory = DeclaredWorkerMemory.admit(IndexerHeapSize.parse("1g").refined(), 256, 256).refined()
        val attempt = SemanticRuntimeBootstrapAttemptId.admit("00000000-0000-4000-8000-000000000001").refined()
        val coordinator = coordinator(2, 1, 10_000)
        fun coordinator(resident: Int, starting: Int, aggregate: Long): WorkerAdmissionCoordinator {
            var sequence = 0L
            return WorkerAdmissionCoordinator(owner, generation, WorkerAdmissionPolicy.admit(resident, starting, aggregate).refined(),
                WorkerReservationIdSource { WorkerReservationId.admit(java.util.UUID(0, ++sequence).toString()).refined() })
        }
    }

    companion object {
        private fun <T, E> Refinement<T, E>.refined(): T = when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> throw AssertionError("Expected admitted value, got $failure")
        }
    }
}
