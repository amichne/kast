package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryOperations
import org.junit.jupiter.api.Assertions.assertInstanceOf

/**
 * The existing original-investigation fixture supplies three qualified paths, despite its one-row execution preview.
 */
internal class RetainedValueEvidenceOracleCase {
    val fixture = OriginalImpactRetentionTest.Fixture()
    val issued = fixture.issueOriginal(listOf(0))
    var semanticCalls = 0
        private set

    private val protocol =
        CanonicalQueryProtocol(
            QueryOperations {
                semanticCalls++
                error("Retained evidence cannot invoke the semantic provider")
            },
            fixture.symbols.references,
            fixture.store,
        )

    suspend fun witness(
        section: ImpactWitnessSectionDocument,
        cursor: Int = 0,
        maximum: Int = MAXIMUM_RECORDS,
    ): QueryRunResult =
        read(
            QueryRunRequest.ReadResult.impactWitness(
                issued.reference,
                section,
                QueryResultCursor.parse(cursor).retainedOracleValue(),
            ),
            maximum,
        )

    suspend fun paths(cursor: Int = 0, maximum: Int = MAXIMUM_RECORDS): QueryRunResult =
        read(
            QueryRunRequest.ReadResult.valuePaths(
                issued.reference,
                QueryResultCursor.parse(cursor).retainedOracleValue(),
            ),
            maximum,
        )

    private suspend fun read(request: QueryRunRequest.ReadResult, maximum: Int): QueryRunResult {
        val budget =
            QueryBudget(
                fixture.symbols.budget.resources.copy(resultLimit = ResultLimit.parse(maximum).retainedOracleValue()),
                QueryByteLimit.parse(RETURNED_BYTES).retainedOracleValue(),
            )
        val result = protocol.executePage(request, fixture.symbols.authority, budget)
        assertInstanceOf(OperationOutcome.Qualified::class.java, result)
        return (result as OperationOutcome.Qualified).evidence.payload
    }

    private companion object {
        const val MAXIMUM_RECORDS = 100
        const val RETURNED_BYTES = 1_000_000L
    }
}

internal fun <V, F> Refinement<V, F>.retainedOracleValue(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Retained evidence oracle prerequisite rejected: $failure")
    }
