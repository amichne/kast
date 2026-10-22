package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import kotlinx.coroutines.CoroutineScope

/** Cross-module read composition tests run the real admission, transaction, and publication owner. */
class HostedReadExecutionFixture(
    scope: CoroutineScope,
    clock: () -> Long,
    afterDiagnostic: () -> Unit = {},
) {
    private val executor =
        HostedQueryExecutor(scope, clock) { limits ->
            HostedReadDiagnostics(clock, limits, publish = { afterDiagnostic() })
        }

    suspend fun <Value> read(
        budget: HostedExecutionBudgetRequest,
        deadline: HostedReadDeadline,
        onCommit: () -> Unit = {},
        evaluate: suspend (ExecutionBudgetReport) -> Value,
    ): HostedSemanticReadResult<Value> =
        executor
            .execute(
                endpoint = executor.endpoint,
                executionBudget = budget,
                deadline = deadline,
            ) { progress ->
                runHostedReadTransaction(progress, { Refinement.Refined(Unit) }) { allowance ->
                    when (
                        val prepared =
                            progress.publicationEffects.prepare(
                                object : HostedReadPublicationEffect {
                                    override fun commit(): Refinement<Unit, HostedQueryFailure> {
                                        onCommit()
                                        return Refinement.Refined(Unit)
                                    }

                                    override fun discard() = Unit
                                }
                            )
                    ) {
                        is Refinement.Rejected -> error("Publication fixture rejected: ${prepared.failure}")
                        is Refinement.Refined -> evaluate(ExecutionBudgetReport.from(allowance.executionBudget))
                    }
                }
            }
            .semanticReadResult()

    suspend fun close() {
        executor.retire()
        executor.drain()
    }
}
