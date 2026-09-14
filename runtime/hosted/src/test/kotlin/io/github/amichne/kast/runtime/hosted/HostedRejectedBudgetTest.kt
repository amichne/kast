package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class HostedRejectedBudgetTest {
    @Test
    fun `source rejection after budget admission retains finite reason and effective grant in wire envelope`() {
        val outcome = OperationOutcome.Rejected(SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE)
        val reported = outcome.withSourceBudget(report())
        val encoded = CanonicalOperationWireBindings.sourceRead.encodeOutcome(reported) as WireEncoding.Encoded
        val body = Json.parseToJsonElement(encoded.document).jsonObject.getValue("body").jsonObject
        assertEquals("rejected", body.getValue("type").jsonPrimitive.content)
        assertEquals("compiler-analysis-unavailable", body.getValue("rejection").jsonPrimitive.content)
        assertNotNull(body["execution_budget"], "Admitted source rejection must retain its effective grant")
    }

    private fun report(): ExecutionBudgetReport {
        val resources = ResourceBudget(
            ResultLimit.parse(8).proven(),
            WorkUnitLimit.parse(7).proven(),
            ElapsedTimeLimitMillis.parse(100).proven(),
        )
        val bytes = ReturnedByteLimit.parse(4096).proven()
        return ExecutionBudgetReport.from(AdmittedExecutionBudget.admit(
            RequestedExecutionBudget(), resources, bytes, resources, bytes,
            ExecutionBudgetCapacity(resources.elapsedTimeLimit, resources.resultLimit, bytes),
        ))
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
