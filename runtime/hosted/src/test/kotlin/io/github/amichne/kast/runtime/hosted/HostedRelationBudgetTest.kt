package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionBudgetCapacity
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedRelationBudgetTest {
    @Test
    fun `canonical relation budget enters host admission and domain projections retain the same grant`() {
        val fixture = RelationPagingFixture.live()
        val budget =
            ExecutionBudgetDocument(
                maxWorkUnits = WorkUnitLimit.parse(2).proven(),
                maxResults = ResultLimit.parse(1).proven(),
            )
        val request =
            HostedRequest.Relation(
                fixture.authority.workspaceRoot,
                fixture.request(RelationReadPositionDocument.Start).copy(executionBudget = budget),
            )
        assertEquals(budget.requested(), request.executionBudget().requested)
        val grant = grant(budget.requested())
        val projected = HostedSemanticBudgets(ReadLimits.Default, grant)
        assertSame(grant.resources, projected.hostedRelationBudget.resources)
        assertSame(grant.resources, projected.hostedSourceBudget.resources)
        assertEquals(2L, projected.hostedRelationBudget.resources.workUnitLimit.value)
        assertEquals(1, projected.hostedRelationBudget.resources.resultLimit.value)
        assertEquals(grant.returnedBytes.effective.value, projected.hostedRelationBudget.returnedBytes.value)
    }

    @Test
    fun `full encoded response including budget evidence fits caller byte allowance`() = runTest {
        val fixture = RelationPagingFixture.live()
        val original = fixture.page() as OperationOutcome.Qualified
        val report = ExecutionBudgetReport.from(grant(RequestedExecutionBudget()))
        val semantic =
            OperationOutcome.Qualified(
                original.evidence.copy(payload = original.evidence.payload.copy(executionBudget = report)),
                original.qualification,
            )
        val reference = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.relationRead, semantic)
        val maximum = ReturnedByteLimit.parse(reference.document.toByteArray().size.toLong() - 1).proven()
        val response =
            encodeHostedRelationResponse(semantic, ReadLimits.Default, maximumBytes = maximum) {
                HostedOutputRetention.Retained(
                    ProtocolText.parse("relation-output:v1:00000000-0000-0000-0000-000000000001").proven()
                )
            }
        assertTrue(response is HostedResponse.Canonical<*, *, *>)
        assertTrue(response.document.toByteArray().size <= maximum.value)
        val decoded =
            CanonicalOperationWireBindings.relationRead.decodeOutcome(response.document) as WireDecoding.Decoded
        val payload = (decoded.value as OperationOutcome.Qualified).evidence.payload
        assertEquals(report, payload.executionBudget)
        assertTrue(payload.relations.values.isNotEmpty())
    }

    private fun grant(request: RequestedExecutionBudget): AdmittedExecutionBudget {
        val resources =
            ResourceBudget(
                ResultLimit.parse(128).proven(),
                WorkUnitLimit.parse(100_000).proven(),
                ElapsedTimeLimitMillis.parse(2_000).proven(),
            )
        val bytes = ReturnedByteLimit.parse(49_152).proven()
        return AdmittedExecutionBudget.admit(
            request,
            resources,
            bytes,
            resources,
            bytes,
            ExecutionBudgetCapacity(resources.elapsedTimeLimit, resources.resultLimit, bytes),
        )
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
