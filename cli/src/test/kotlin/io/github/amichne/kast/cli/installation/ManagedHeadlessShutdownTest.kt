package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.protocol.contract.HostAutoReload
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostMode
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagedHeadlessShutdownTest {
    @Test
    fun `settlement precedes stop and the same process is awaited before success`() {
        val (process, identity, inspected) = fixture()
        val effects = mutableListOf<String>()
        val transactions = mutableSetOf<String>()
        var settling = true
        val result =
            stopManagedHeadlessHost(
                "/idea",
                "/private/profile",
                { request ->
                    assertEquals("/idea", request.ideaHome)
                    assertEquals("/private/profile", request.profile)
                    val response =
                        when (request) {
                            is ManagedHostRequest.Inspect -> {
                                effects += "inspect"
                                inspected
                            }
                            is ManagedHostRequest.Settle -> {
                                effects += "settle"
                                transactions += request.transaction
                                assertEquals("host", request.host)
                                if (settling) {
                                    settling = false
                                    IdeLifecycleResult.HostSettling("host", request.transaction)
                                } else IdeLifecycleResult.HostSettled("host", request.transaction, identity)
                            }
                            is ManagedHostRequest.Stop -> {
                                effects += "stop"
                                assertEquals(setOf(request.transaction), transactions)
                                IdeLifecycleResult.HostStopRequested("host", request.transaction, identity)
                            }
                            else -> error("Unexpected operation: $request")
                        }
                    ManagedHostResponse.Inspected(listOf(process), response)
                },
                awaitExit = {
                    effects += "await-exit"
                    assertEquals(process, it)
                    true
                },
                pauseSettlement = { effects += "await-settlement" },
            )
        assertEquals(ManagedHeadlessShutdownOutcome.Completed, result)
        assertEquals(listOf("inspect", "settle", "await-settlement", "settle", "stop", "await-exit"), effects)
    }

    @Test
    fun `absent managed host completes without settlement or process effects`() {
        val requests = mutableListOf<ManagedHostRequest>()
        val result =
            stopManagedHeadlessHost(
                "/idea",
                "/private/profile",
                {
                    requests += it
                    ManagedHostResponse.Inspected(emptyList())
                },
                { error("No process was admitted") },
            )
        assertEquals(ManagedHeadlessShutdownOutcome.Completed, result)
        assertEquals(listOf(ManagedHostRequest.Inspect("/idea", "/private/profile")), requests)
    }

    @Test
    fun `failed host admission stops before settlement and exit waiting`() {
        val requests = mutableListOf<ManagedHostRequest>()
        val result =
            stopManagedHeadlessHost(
                "/idea",
                "/private/profile",
                {
                    requests += it
                    ManagedHostResponse.Rejected(ManagedHostFailure.PROFILE_MISMATCH)
                },
                { error("Unowned processes must not be waited on") },
            )
        assertEquals(ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_REJECTED), result)
        assertEquals(1, requests.size)
    }

    @Test
    fun `desktop identity is rejected before settlement or stop`() {
        val fixture = fixture()
        val result =
            stopManagedHeadlessHost(
                "/idea",
                "/private/profile",
                { request ->
                    assertEquals(ManagedHostRequest.Inspect("/idea", "/private/profile"), request)
                    ManagedHostResponse.Inspected(
                        listOf(fixture.process),
                        fixture.inspected.copy(runtimeIdentity = fixture.identity.copy(mode = IdeHostMode.DESKTOP)),
                    )
                },
                { error("Desktop process must remain untouched") },
            )
        assertEquals(ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.ADMISSION_REJECTED), result)
    }

    @Test
    fun `settlement refusal prevents stop and exit waiting`() {
        val fixture = fixture()
        val effects = mutableListOf<String>()
        val result =
            stopManagedHeadlessHost(
                "/idea",
                "/private/profile",
                { request ->
                    when (request) {
                        is ManagedHostRequest.Inspect -> {
                            effects += "inspect"
                            ManagedHostResponse.Inspected(listOf(fixture.process), fixture.inspected)
                        }
                        is ManagedHostRequest.Settle -> {
                            effects += "settle"
                            ManagedHostResponse.Rejected(ManagedHostFailure.HOST_REJECTED)
                        }
                        else -> error("Stop must not follow a rejected settlement")
                    }
                },
                { error("No stop was admitted") },
            )
        assertEquals(ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_NOT_SETTLED), result)
        assertEquals(listOf("inspect", "settle"), effects)
    }

    private data class Fixture(
        val process: ManagedIdeProcess,
        val identity: IdeHostRuntimeIdentity,
        val inspected: IdeLifecycleResult.Inspected,
    )

    private fun fixture(): Fixture {
        val process = ManagedIdeProcess(42, 100, "/idea/bin/idea")
        val identity =
            IdeHostRuntimeIdentity(
                "/idea",
                "/private/profile",
                42,
                100,
                "host",
                IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
                HostAutoReload.DISABLED,
                IdeHostMode.MANAGED_HEADLESS,
            )
        val inspected =
            IdeLifecycleResult.Inspected(
                "host",
                "/idea",
                "262.1.1",
                emptyList(),
                HostedCompatibilityDocument(
                    "262.1.1",
                    "262.1.1-IJ",
                    "1.2.3",
                    HostedContractDocument(
                        runtimeProtocolIdentity = "test",
                        operationRegistryDigest = "test",
                        wireSchemaDigest = "test",
                        capabilities = emptyList(),
                    ),
                ),
                runtimeIdentity = identity,
            )
        return Fixture(process, identity, inspected)
    }
}
