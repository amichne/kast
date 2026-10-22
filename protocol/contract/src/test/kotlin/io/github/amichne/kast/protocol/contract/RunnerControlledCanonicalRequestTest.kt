package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RunnerControlledCanonicalRequestTest {
    @Test
    fun `canonical runner mode survives encoding and rejects conflicting semantic controls`() {
        val reference = ProtocolText.parse("exact:v2:opaque").requestFixture()
        val references =
            BoundedProtocolList.create(listOf(QueryReferenceDocument.ExactSymbol(reference))).requestFixture()
        val steps = BoundedProtocolList.create<QueryStepDocument>(emptyList()).requestFixture()
        val fields = BoundedProtocolList.create<QuerySymbolFieldDocument>(emptyList()).requestFixture()
        val run =
            QueryRunRequest.Run(
                from = QueryFromDocument.References(references),
                steps = steps,
                output = QueryOutputDocument.Symbols(fields),
                execution =
                    QueryExecutionDocument(
                        QueryExecutionKindDocument.EXHAUSTIVE,
                        QueryExecutionBudgetDocument.INTERACTIVE,
                    ),
                executionMode = QueryExecutionModeDocument.RUNNER_CONTROLLED,
            )
        val encoded = Json.encodeToString(QueryRunRequest.serializer(), run)
        assertEquals(run, Json.decodeFromString(QueryRunRequest.serializer(), encoded))
        assertInstanceOf(RequestedExecutionBudget.RunnerControlled::class.java, run.requestedExecutionBudget())
        val contradictory =
            run.copy(executionBudget = ExecutionBudgetDocument(maxWorkUnits = WorkUnitLimit.parse(1).requestFixture()))
        assertThrows(SerializationException::class.java) {
            Json.encodeToString(QueryRunRequest.serializer(), contradictory)
        }
        val timed =
            run.copy(
                executionBudget =
                    ExecutionBudgetDocument(maxElapsedMillis = ElapsedTimeLimitMillis.parse(60001).requestFixture())
            )
        val timedEncoded = Json.encodeToString(QueryRunRequest.serializer(), timed)
        assertEquals(timed, Json.decodeFromString(QueryRunRequest.serializer(), timedEncoded))
        assertEquals(
            60001L,
            (timed.requestedExecutionBudget() as RequestedExecutionBudget.RunnerControlled).timeout?.value,
        )
    }
}

private fun <Value> Refinement<Value, *>.requestFixture(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid request fixture")
    }
