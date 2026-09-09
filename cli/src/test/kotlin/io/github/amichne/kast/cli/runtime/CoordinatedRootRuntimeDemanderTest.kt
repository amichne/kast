package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CoordinatedRootRuntimeDemanderTest {
    @Test fun `semantic admission routes through shared control and retains typed failure`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(directory) as CanonicalRootDiscovery.Discovered).root
        var calls = 0
        val client = object : WorkerControlClient {
            override suspend fun demand(requested: Path, heap: IndexerHeapSize, startup: InstalledWorkerStartup): InstalledWorkerStart {
                assertEquals(root.path, requested); assertEquals(2048, heap.mebibytes)
                assertInstanceOf(InstalledWorkerStartup.Rebuild::class.java, startup)
                calls++
                return InstalledWorkerStart.Rejected(WorkerControlFailure.CAPACITY_REJECTED)
            }
            override suspend fun retired(root: Path): InstalledWorkerStop = error("demand cannot retire")
        }
        val admission = CoordinatedRootRuntimeDemander(client, IndexerHeapSize.parse("2g").refined()).demand(root, HostedRuntimeDemand.Lifecycle,
            RuntimeStartupRequest.Requested(StartupIdeHome.Standard, StartupCacheIntent.Rebuild))
        assertEquals(1, calls, "direct CLI bypassed shared worker control")
        assertEquals(RuntimeAdmission.Rejected(RuntimeAdmissionFailure.WorkerControlRejected(WorkerControlFailure.CAPACITY_REJECTED)), admission)
    }

    @Test fun `coordinator ownership proof survives runtime admission`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(directory) as CanonicalRootDiscovery.Discovered).root
        val endpoint = InstalledWorkerEndpoint.admit(root.path, SemanticRuntimeId.parse("sha256:" + "a".repeat(64)).refined(), root.path.resolve("runtime.sock"),
            SemanticRuntimeBootstrapAttemptId.admit("00000000-0000-4000-8000-000000000001").refined()).refined()
        val binding = WorkerRouteBinding.Installation.admit("installation", "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "a".repeat(64), 1).refined()
        val client = object : WorkerControlClient {
            override suspend fun demand(root: Path, heap: IndexerHeapSize, startup: InstalledWorkerStartup) = InstalledWorkerStart.Ready(endpoint, binding)
            override suspend fun retired(root: Path): InstalledWorkerStop = error("not retirement")
        }
        val result = CoordinatedRootRuntimeDemander(client, IndexerHeapSize.Default).demand(root, HostedRuntimeDemand.Lifecycle, RuntimeStartupRequest.Default) as RuntimeAdmission.Ready
        assertSame(binding, result.workerBinding, "runtime admission erased coordinator ownership")
        assertInstanceOf(RuntimeWireAuthority.Qualified::class.java, result.wireAuthority, "runtime admission erased bootstrap attempt")
        assertEquals(endpoint.attempt, (result.wireAuthority as RuntimeWireAuthority.Qualified).identity.bootstrapAttempt)
    }

    @Test fun `startup and transport retain one decreasing invocation allowance`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(directory) as CanonicalRootDiscovery.Discovered).root
        val endpoint = InstalledWorkerEndpoint.admit(root.path, SemanticRuntimeId.parse("sha256:" + "a".repeat(64)).refined(), root.path.resolve("runtime.sock"),
            SemanticRuntimeBootstrapAttemptId.admit("00000000-0000-4000-8000-000000000001").refined()).refined()
        val binding = WorkerRouteBinding.Installation.admit("installation", "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", "a".repeat(64), 1).refined()
        var nanos = 0L
        val policy = RuntimeInvocationBudgetPolicy({ io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(100).refined() }, { nanos })
        val client = object : WorkerControlClient {
            override suspend fun demand(root: Path, heap: IndexerHeapSize, startup: InstalledWorkerStartup): InstalledWorkerStart {
                nanos += 60_000_000; return InstalledWorkerStart.Ready(endpoint, binding)
            }
            override suspend fun retired(root: Path): InstalledWorkerStop = error("not retirement")
        }
        val admitted = CoordinatedRootRuntimeDemander(client, IndexerHeapSize.Default, policy).demand(root, HostedRuntimeDemand.Lifecycle, RuntimeStartupRequest.Default) as RuntimeAdmission.Ready
        assertInstanceOf(RuntimeInvocationDeadline.Running::class.java, admitted.deadline, "startup erased original invocation allowance")
        val deadline = admitted.deadline as RuntimeInvocationDeadline.Running
        assertEquals(40L, deadline.remaining().refined().value)
        nanos += 40_000_000
        assertEquals(Refinement.Rejected(WireTransportFailure.TIMED_OUT), deadline.remaining())
    }

    @Test fun `interactive seed remains interactive through coordinator request`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(directory) as CanonicalRootDiscovery.Discovered).root
        var requests = 0
        val client = object : WorkerControlClient {
            override suspend fun demand(root: Path, heap: IndexerHeapSize, startup: InstalledWorkerStartup): InstalledWorkerStart {
                assertEquals(WorkerSeedConsentSelection.INTERACTIVE, (startup as InstalledWorkerStartup.Seed).consent)
                requests++; return InstalledWorkerStart.Rejected(WorkerControlFailure.STARTUP_REJECTED)
            }
            override suspend fun retired(root: Path): InstalledWorkerStop = error("not retirement")
        }
        CoordinatedRootRuntimeDemander(client, IndexerHeapSize.Default).demand(root, HostedRuntimeDemand.Lifecycle,
            RuntimeStartupRequest.Requested(StartupIdeHome.Standard, StartupCacheIntent.Seed(StartupIdeaSystem.Standard, IndexSeedConsentRequest.INTERACTIVE)))
        assertEquals(1, requests, "interactive seed was rejected before frontend disclosure exchange")
    }

    @Test fun `control response for another root is rejected before semantic exchange`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(directory) as CanonicalRootDiscovery.Discovered).root
        val other = Files.createDirectory(directory.resolve("other"))
        val endpoint = InstalledWorkerEndpoint.admit(other, SemanticRuntimeId.parse("sha256:" + "a".repeat(64)).refined(), other.resolve("runtime.sock"),
            SemanticRuntimeBootstrapAttemptId.admit("00000000-0000-4000-8000-000000000001").refined()).refined()
        val client = object : WorkerControlClient {
            override suspend fun demand(root: Path, heap: IndexerHeapSize, startup: InstalledWorkerStartup) = InstalledWorkerStart.Ready(endpoint)
            override suspend fun retired(root: Path): InstalledWorkerStop = error("not retirement")
        }
        assertEquals(RuntimeAdmission.Rejected(RuntimeAdmissionFailure.WorkerControlRejected(WorkerControlFailure.IDENTITY_REJECTED)),
            CoordinatedRootRuntimeDemander(client, IndexerHeapSize.Default).demand(root, HostedRuntimeDemand.Lifecycle, RuntimeStartupRequest.Default))
    }
}
private fun <V,F> Refinement<V,F>.refined(): V = when(this) { is Refinement.Refined -> value; is Refinement.Rejected -> error("Rejected $failure") }
