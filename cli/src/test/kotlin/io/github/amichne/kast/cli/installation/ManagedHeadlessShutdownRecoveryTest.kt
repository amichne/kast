package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.protocol.contract.HostAutoReload
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostMode
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedHeadlessShutdownRecoveryTest {
    @Test
    fun `deadline preserves shutdown ownership for a later invocation after work completes`() {
        val fixture = Fixture()
        val requests = mutableListOf<ManagedHostRequest>()
        var now = 0L
        val first =
            stopManagedHeadlessHost(
                "/idea",
                "/profile",
                execute = { request ->
                    requests += request
                    when (request) {
                        is ManagedHostRequest.Inspect -> fixture.response(fixture.inspected)
                        is ManagedHostRequest.Settle ->
                            fixture.response(IdeLifecycleResult.HostSettling("host", request.transaction))
                        else -> error("Timeout must not stop or resume the busy Host")
                    }
                },
                awaitExit = { error("No stop was requested") },
                pauseSettlement = { now = TimeUnit.SECONDS.toNanos(20) },
                nanoTime = { now },
            )
        assertEquals(ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_NOT_SETTLED), first)
        assertEquals(3, requests.size)
        val transactions = requests.filterIsInstance<ManagedHostRequest.Settle>().map { it.transaction }
        assertEquals(1, transactions.distinct().size)
        fixture.retry(transactions.first())
    }

    @Test
    fun `interrupted settlement retains ownership for another invocation`() {
        val fixture = Fixture()
        var transaction: String? = null
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            val first =
                stopManagedHeadlessHost(
                    "/idea",
                    "/profile",
                    execute = { request ->
                        when (request) {
                            is ManagedHostRequest.Inspect -> fixture.response(fixture.inspected)
                            is ManagedHostRequest.Settle -> {
                                transaction = request.transaction
                                fixture.response(IdeLifecycleResult.HostSettling("host", request.transaction))
                            }
                            else -> error("Interruption must not stop or resume the busy Host")
                        }
                    },
                    awaitExit = { error("No stop was requested") },
                    pauseSettlement = { throw InterruptedException("controlled interruption") },
                    nanoTime = { 0L },
                )
            assertEquals(ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.INTERRUPTED), first)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        fixture.retry(requireNotNull(transaction))
    }

    @Test
    fun `unavailable settlement reply does not discard the transaction for retry`() {
        val fixture = Fixture()
        var transaction: String? = null
        val first =
            stopManagedHeadlessHost(
                "/idea",
                "/profile",
                execute = { request ->
                    when (request) {
                        is ManagedHostRequest.Inspect -> fixture.response(fixture.inspected)
                        is ManagedHostRequest.Settle -> {
                            transaction = request.transaction
                            ManagedHostResponse.Rejected(ManagedHostFailure.HOST_REJECTED)
                        }
                        else -> error("An unavailable reply grants no stop or resume")
                    }
                },
                awaitExit = { error("No stop was requested") },
            )
        assertEquals(ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_NOT_SETTLED), first)
        fixture.retry(requireNotNull(transaction))
    }

    @Test
    fun `a foreign settlement fence is never stopped or resumed`() {
        val fixture = Fixture()
        val requests = mutableListOf<ManagedHostRequest>()
        val result =
            stopManagedHeadlessHost(
                "/idea",
                "/profile",
                execute = { request ->
                    requests += request
                    when (request) {
                        is ManagedHostRequest.Inspect -> fixture.response(fixture.inspected)
                        is ManagedHostRequest.Settle ->
                            ManagedHostResponse.Rejected(
                                ManagedHostFailure.HOST_REJECTED,
                                IdeLifecycleResult.Blocked(IdeLifecycleFailure.REQUEST_CONFLICT),
                            )
                        else -> error("A foreign transaction must remain fenced")
                    }
                },
                awaitExit = { error("No stop was requested") },
            )
        assertEquals(ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_NOT_SETTLED), result)
        assertEquals(2, requests.size)
    }

    @Test
    fun `unavailable stop reply preserves the same settlement for retry`() {
        val fixture = Fixture()
        var transaction: String? = null
        val result =
            stopManagedHeadlessHost(
                "/idea",
                "/profile",
                execute = { request ->
                    when (request) {
                        is ManagedHostRequest.Inspect -> fixture.response(fixture.inspected)
                        is ManagedHostRequest.Settle -> {
                            transaction = request.transaction
                            fixture.response(
                                IdeLifecycleResult.HostSettled("host", request.transaction, fixture.identity)
                            )
                        }
                        is ManagedHostRequest.Stop -> {
                            assertEquals(transaction, request.transaction)
                            ManagedHostResponse.Rejected(ManagedHostFailure.HOST_REJECTED)
                        }
                        else -> error("An unavailable stop reply cannot authorize resume")
                    }
                },
                awaitExit = { error("No stop acknowledgement was received") },
            )
        assertEquals(ManagedHeadlessShutdownOutcome.Rejected(ManagedHeadlessShutdownFailure.HOST_REJECTED), result)
        fixture.retry(requireNotNull(transaction))
    }

    @Test
    fun `shutdown ownership is stable but distinct for each identity coordinate`() {
        val identity = Fixture().identity
        val transaction = managedShutdownTransaction(identity)
        assertEquals("shutdown-v1:107be339-34a6-3d68-a513-00261844c371", transaction)
        assertEquals(transaction, managedShutdownTransaction(identity.copy()))
        listOf(
                identity.copy(ideaHome = "/other-idea"),
                identity.copy(profile = "/other-profile"),
                identity.copy(pid = 43),
                identity.copy(processStartEpochMillis = 101),
                identity.copy(hostLifetime = "replacement-host"),
            )
            .forEach { assertNotEquals(transaction, managedShutdownTransaction(it)) }
        assertEquals(
            transaction,
            managedShutdownTransaction(
                identity.copy(
                    buildIdentity = identity.buildIdentity.copy(version = "other"),
                    autoReload = HostAutoReload.ENABLED,
                )
            ),
        )
    }

    private class Fixture {
        val process = ManagedIdeProcess(42, 100, "/idea/bin/idea")
        val identity =
            IdeHostRuntimeIdentity(
                "/idea",
                "/profile",
                42,
                100,
                "host",
                IdeHostBuildIdentity("1.2.3", "a".repeat(40), "b".repeat(40)),
                mode = IdeHostMode.MANAGED_HEADLESS,
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

        fun response(result: IdeLifecycleResult) = ManagedHostResponse.Inspected(listOf(process), result)

        fun retry(originalTransaction: String) {
            val effects = mutableListOf<String>()
            val result =
                stopManagedHeadlessHost(
                    "/idea",
                    "/profile",
                    execute = { request ->
                        when (request) {
                            is ManagedHostRequest.Inspect -> {
                                effects += "inspect"
                                response(inspected)
                            }
                            is ManagedHostRequest.Settle -> {
                                effects += "settle"
                                assertEquals(originalTransaction, request.transaction)
                                response(IdeLifecycleResult.HostSettled("host", originalTransaction, identity))
                            }
                            is ManagedHostRequest.Stop -> {
                                effects += "stop"
                                assertEquals(originalTransaction, request.transaction)
                                response(IdeLifecycleResult.HostStopRequested("host", originalTransaction, identity))
                            }
                            else -> error("Unexpected recovery effect: $request")
                        }
                    },
                    awaitExit = {
                        assertEquals(process, it)
                        effects += "await-exit"
                        true
                    },
                )
            assertEquals(ManagedHeadlessShutdownOutcome.Completed, result)
            assertEquals(listOf("inspect", "settle", "stop", "await-exit"), effects)
        }
    }
}
