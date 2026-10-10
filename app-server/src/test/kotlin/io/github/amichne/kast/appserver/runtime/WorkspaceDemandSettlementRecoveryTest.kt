@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.protocol.registry.OperationEffect
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Real production preparation, demand, lane, cancellation and inspection composition. */
class WorkspaceDemandSettlementRecoveryTest {
    @Test
    fun `composed read cancellation and lost reply recover after terminal preparation retry and provider retirement`() =
        runTest {
            val case = WorkspaceDemandSettlementRecoveryCase(this, OperationEffect.INTELLIJ_READ)
            try {
                val executing = case.begin()
                case.cancelAndInspect(executing)
                case.settle(executing, mutationFence = false)
            } finally {
                case.close()
            }
        }

    @Test
    fun `composed uncertain write remains fenced despite fresh retained preparation and reachable inspection`() =
        runTest {
            val case = WorkspaceDemandSettlementRecoveryCase(this, OperationEffect.INTELLIJ_WRITE)
            try {
                val executing = case.begin()
                case.cancelAndInspect(executing)
                case.settle(executing, mutationFence = true)
            } finally {
                case.close()
            }
        }
}
