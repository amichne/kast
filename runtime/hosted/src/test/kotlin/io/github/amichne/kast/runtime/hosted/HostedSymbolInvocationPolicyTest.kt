package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadPublicationFixture
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSymbolInvocationPolicyTest {
    @Test
    fun `invocation retention uses the store quota independently of each checkpoint`() {
        assertQuotas(ReadLimits.Default, checkpoint = 33_554_432L, invocation = 134_217_728L)
    }

    @Test
    fun `a configured store quota governs the invocation without enlarging checkpoints`() {
        val limits =
            ReadLimits.resolve(
                    mapOf(
                        "KAST_READ_QUERY_CHECKPOINT_BYTES" to "4096",
                        "KAST_READ_QUERY_CONTINUATION_BYTES" to "16384",
                    )
                )
                .refined()
        assertQuotas(limits, checkpoint = 4096L, invocation = 16384L)
    }

    @Test
    fun `equal operator quotas do not grant extra invocation storage`() {
        val limits =
            ReadLimits.resolve(
                    mapOf(
                        "KAST_READ_QUERY_CHECKPOINT_BYTES" to "4096",
                        "KAST_READ_QUERY_CONTINUATION_BYTES" to "4096",
                    )
                )
                .refined()
        assertQuotas(limits, checkpoint = 4096L, invocation = 4096L)
    }

    private fun assertQuotas(limits: ReadLimits, checkpoint: Long, invocation: Long) {
        val fixture = HostedReadPublicationFixture(limits)
        val context = fixture.context
        val budgets = HostedSemanticBudgets(limits, context.executionBudget)
        val policy = hostedSymbolInvocationPolicy(context, EmptyCoroutineContext)
        assertEquals(checkpoint, budgets.hostedQueryBudget.checkpointBytes.value)
        assertEquals(invocation, policy.retainedBytes.value)
        assertEquals(limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value.toLong(), policy.retainedBytes.value)
        assertEquals(minOf(100, context.executionBudget.results.effective.value), policy.previewRows.value)
        assertEquals(
            minOf(32_768L, context.executionBudget.returnedBytes.effective.value),
            policy.previewBytesLimit.value,
        )
        fixture.discard()
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid quota fixture: $failure")
        }
}
