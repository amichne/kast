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
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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

    @Test
    fun `all three admitted semantic failures retain their report and reject malformed report fields`() {
        verify(
            CanonicalOperationWireBindings.sourceRead,
            OperationOutcome.Rejected(SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE).withSourceBudget(report()),
        )
        verify(
            CanonicalOperationWireBindings.traversalRun,
            OperationOutcome.Rejected(TraversalRunRejection.SELECTOR_STALE).withTraversalBudget(report()),
        )
        verify(
            CanonicalOperationWireBindings.queryRun,
            OperationOutcome.Rejected(QueryRunRejection.WorkspaceNotReady).withQueryBudget(report()),
        )
    }

    @Test
    fun `unadmitted failure has no report and admitted failure cannot lose its report while retaining output`() {
        val original = OperationOutcome.Rejected(SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE)
        val encoded = CanonicalOperationWireBindings.sourceRead.encodeOutcome(original) as WireEncoding.Encoded
        val body = Json.parseToJsonElement(encoded.document).jsonObject.getValue("body").jsonObject
        assertEquals(setOf("type", "rejection"), body.keys)
        assertEquals(
            WireDecoding.Decoded(original),
            CanonicalOperationWireBindings.sourceRead.decodeOutcome(encoded.document),
        )
        val admitted = original.withSourceBudget(report())
        assertEquals(admitted, admitted.withSourceBudget(null))
    }

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
    > verify(
        binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
        outcome: OperationOutcome<Result, Qualification, Rejection>,
    ) {
        val encoded = binding.encodeOutcome(outcome) as WireEncoding.Encoded
        val body = Json.parseToJsonElement(encoded.document).jsonObject.getValue("body").jsonObject
        val budget = body.getValue("execution_budget").jsonObject
        assertEquals(setOf("type", "rejection", "execution_budget"), body.keys)
        assertEquals("7", budget.getValue("max_work_units").jsonObject.getValue("effective").jsonPrimitive.content)
        assertEquals(
            "configured_default",
            budget.getValue("max_work_units").jsonObject.getValue("selection").jsonPrimitive.content,
        )
        assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(encoded.document))
        // Deliberately incompatible fields must fail closed, independently of the successful round trip.
        assertInstanceOf(
            WireDecoding.Rejected::class.java,
            binding.decodeOutcome(encoded.document.replace(Json.encodeToString(report()), "null")),
        )
        assertInstanceOf(
            WireDecoding.Rejected::class.java,
            binding.decodeOutcome(encoded.document.replace("max_work_units", "unknown_work_units")),
        )
    }

    private fun report(): ExecutionBudgetReport {
        val resources =
            ResourceBudget(
                ResultLimit.parse(8).proven(),
                WorkUnitLimit.parse(7).proven(),
                ElapsedTimeLimitMillis.parse(100).proven(),
            )
        val bytes = ReturnedByteLimit.parse(4096).proven()
        return ExecutionBudgetReport.from(
            AdmittedExecutionBudget.admit(
                RequestedExecutionBudget(),
                resources,
                bytes,
                resources,
                bytes,
                ExecutionBudgetCapacity(resources.elapsedTimeLimit, resources.resultLimit, bytes),
            )
        )
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
