package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InvocationResponsesTest {
    @Test
    fun `capacity admission rejects unbounded or negative allowances`() {
        assertEquals(
            io.github.amichne.kast.kernel.Refinement.Rejected(InvocationFenceFailure.LIMIT_REJECTED),
            InvocationCapacityLimit.admit(-1),
        )
        assertEquals(
            io.github.amichne.kast.kernel.Refinement.Rejected(InvocationFenceFailure.LIMIT_REJECTED),
            InvocationCapacityLimit.admit(Int.MAX_VALUE),
        )
        assertEquals(0, limit(0).value)
        assertEquals(1, limit(1).value)
    }

    @Test
    fun `evicted completed response retains conflict and replay evidence across restart`(@TempDir root: Path) =
        runTest {
            val file = root.toRealPath().resolve("invocations.json")
            val responses =
                InvocationResponses(InvocationFence(file, maximumActive = limit(1)), maximumCompleted = limit(1))
            val first = started(responses, "first")
            val result = reply("first result")
            assertEquals(result, first.complete(result))
            assertEquals(result, first.result.await())
            started(responses, "second").complete(reply("second result"))
            assertEquals(rejected(InvocationFenceFailure.ALREADY_COMPLETED), begin(responses, "first"))
            assertEquals(
                rejected(InvocationFenceFailure.INPUT_CONFLICT),
                begin(responses, "first", InvocationFingerprint.of("changed")),
            )
            val reopened = InvocationResponses(InvocationFence(file), maximumCompleted = limit(1))
            assertEquals(rejected(InvocationFenceFailure.ALREADY_COMPLETED), begin(reopened, "first"))
        }

    @Test
    fun `active response survives completed cache churn and joins one owner`() = runTest {
        val responses =
            InvocationResponses(InvocationFence(null, maximumActive = limit(2)), maximumCompleted = limit(1))
        val pending = started(responses, "pending")
        assertEquals(setOf(UpgradeBlocker.INVOCATION_ACTIVE), responses.upgradeBlockers())
        repeat(3) { ordinal -> started(responses, "completed-$ordinal").complete(reply("done")) }
        val duplicate = assertInstanceOf(InvocationResponseAdmission.Existing::class.java, begin(responses, "pending"))
        assertSame(pending.result, duplicate.result)
        assertFalse(duplicate.result.isCompleted)
        assertEquals(
            rejected(InvocationFenceFailure.INPUT_CONFLICT),
            begin(responses, "pending", InvocationFingerprint.of("changed")),
        )
        val second = started(responses, "second")
        assertEquals(rejected(InvocationFenceFailure.CAPACITY_EXCEEDED), begin(responses, "third"))
        val result = reply("pending result")
        pending.complete(result)
        assertEquals(result, duplicate.result.await())
        second.complete(reply("second result"))
        assertEquals(emptySet<UpgradeBlocker>(), responses.upgradeBlockers())
    }

    @Test
    fun `uncertain failure identity survives settlement publication and eviction`() = runTest {
        val responses = InvocationResponses(InvocationFence(null), maximumCompleted = limit(1))
        val evidence = mutableListOf<InvocationCertainty>()
        val owner = started(responses, "uncertain", evidence::add)
        val failure = reply("NATIVE_TRANSPORT_REJECTED", InvocationCertainty.UNCERTAIN)
        assertEquals(failure, owner.settle(failure))
        assertEquals(failure, owner.complete(reply("later known result")))
        assertEquals(failure, owner.result.await())
        assertEquals(failure, owner.complete(reply("duplicate publication")))
        assertEquals(listOf(InvocationCertainty.UNCERTAIN), evidence)
        started(responses, "other").complete(reply("done"))
        assertEquals(rejected(InvocationFenceFailure.OUTCOME_UNCERTAIN), begin(responses, "uncertain"))
    }

    @Test
    fun `durable intent precedes approvals and known rejection remains fenced`(@TempDir root: Path) {
        val file = root.toRealPath().resolve("invocations.json")
        val responses = InvocationResponses(InvocationFence(file), maximumCompleted = limit(0))
        val evidence = mutableListOf<InvocationCertainty>()
        val owner = started(responses, "declined", evidence::add)
        val recovered = InvocationResponses(InvocationFence(file))
        assertEquals(rejected(InvocationFenceFailure.OUTCOME_UNCERTAIN), begin(recovered, "declined"))
        owner.complete(reply("APPROVAL_DECLINED"))
        assertEquals(listOf(InvocationCertainty.KNOWN), evidence)
        assertEquals(rejected(InvocationFenceFailure.ALREADY_COMPLETED), begin(responses, "declined"))
        assertEquals(rejected(InvocationFenceFailure.ALREADY_COMPLETED), begin(recovered, "declined"))
    }

    @Test
    fun `settlement persistence failure is uncertain before the workspace permit can retire`(@TempDir root: Path) =
        runTest {
            val file = root.toRealPath().resolve("invocations.json")
            val responses = InvocationResponses(InvocationFence(file))
            val evidence = mutableListOf<InvocationCertainty>()
            val owner = started(responses, "call", evidence::add)
            FileChannel.open(file.resolveSibling("invocations.json.d").resolve(".lock"), WRITE).use { channel ->
                channel.lock().use {
                    assertEquals(
                        failure(InvocationFenceFailure.OUTCOME_UNCERTAIN),
                        owner.settle(reply("provider completed")),
                    )
                }
            }
            assertEquals(failure(InvocationFenceFailure.OUTCOME_UNCERTAIN), owner.complete(reply("known")))
            assertEquals(listOf(InvocationCertainty.UNCERTAIN), evidence)
            assertEquals(
                rejected(InvocationFenceFailure.OUTCOME_UNCERTAIN),
                begin(InvocationResponses(InvocationFence(file)), "call"),
            )
        }

    private fun started(
        responses: InvocationResponses,
        call: String,
        settled: (InvocationCertainty) -> Unit = {},
    ): AdmittedInvocation =
        assertInstanceOf(
                InvocationResponseAdmission.Started::class.java,
                responses.begin(identity(call), fingerprint, ::failure, settled),
            )
            .invocation

    private fun begin(responses: InvocationResponses, call: String, input: InvocationFingerprint = fingerprint) =
        responses.begin(identity(call), input, ::failure) {}

    private fun identity(call: String) =
        InvocationIdentity(
            checkNotNull(BrokerThreadId.admit("thread")),
            checkNotNull(BrokerTurnId.admit("turn")),
            checkNotNull(BrokerCallId.admit(call)),
        )

    private fun reply(value: String, certainty: InvocationCertainty = InvocationCertainty.KNOWN) =
        ProtocolRouting.ReplyUpstream(Json.encodeToString(TestReply(1, value)), certainty)

    private fun failure(failure: InvocationFenceFailure) =
        reply(
            failure.name,
            if (failure == InvocationFenceFailure.OUTCOME_UNCERTAIN) InvocationCertainty.UNCERTAIN
            else InvocationCertainty.KNOWN,
        )

    private fun rejected(failure: InvocationFenceFailure) = InvocationResponseAdmission.Rejected(failure)

    @Serializable private data class TestReply(val id: Int, val result: String)

    private fun limit(value: Int) =
        (InvocationCapacityLimit.admit(value) as io.github.amichne.kast.kernel.Refinement.Refined).value

    companion object {
        private val fingerprint = InvocationFingerprint.of("input")
    }
}
