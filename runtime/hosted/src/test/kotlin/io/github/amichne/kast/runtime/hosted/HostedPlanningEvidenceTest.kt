package io.github.amichne.kast.runtime.hosted

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticIncompleteCoverage
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitation
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticReadRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedPlanningEvidenceTest {
    @Test
    fun `planning admits a complete current relation proof without a history of public reads`() {
        val fixture = RelationPagingFixture.live()
        val request = RelationRequest.start(fixture.selector, RelationMeaning.References, fixture.budget)
        val batch = batch(request)
        val complete = RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        assertSame(complete, complete.planningEvidence().proven())
    }

    @Test
    fun `native rejections retain every finite reason through the host response`() {
        for (cause in RelationReadRejection.entries) {
            val failure = RelationReadResult.Rejected(cause).planningEvidence().failure()
            assertEquals(HostedPlanningEvidenceFailure.RelationRejected(cause), failure)
            assertEncodedCause(failure, "RELATION_REJECTED", cause.name)
            val traversal =
                TraversalResult.Rejected(TraversalRejection.OneHopRejected(cause)).planningEvidence().failure()
            assertEncodedCause(traversal, "TRAVERSAL_ONE_HOP_REJECTED", cause.name)
        }
        for (cause in DiagnosticReadRejection.entries) {
            val failure = DiagnosticCheckResult.Rejected(cause).planningEvidence().failure()
            assertEquals(HostedPlanningEvidenceFailure.DiagnosticRejected(cause), failure)
            assertEncodedCause(failure, "DIAGNOSTIC_REJECTED", cause.name)
        }
    }

    @Test
    fun `incomplete relation retains all limitations and never mistakes a page checkpoint for a planning proof`() {
        val fixture = RelationPagingFixture.live()
        val request = RelationRequest.start(fixture.selector, RelationMeaning.References, fixture.budget)
        val batch = batch(request)
        val limitations = setOf(RelationLimitation.TIME_LIMIT_REACHED, RelationLimitation.PROVIDER_INCOMPLETE)
        val cursor =
            request.providerCursor.advance(RelationProviderItemDescriptor.parse("bounded-provider-item").proven())
        val resumed = RelationCompilation.qualifiedResumable(batch, limitations, cursor).proven()
        val result = RelationReadResult.Qualified(batch, resumed.coverage).planningEvidence().failure()
        assertEquals(
            HostedPlanningEvidenceFailure.RelationIncomplete(
                limitations,
                HostedPlanningContinuation.COMPLETE_EVIDENCE_ACCUMULATION_UNAVAILABLE,
            ),
            result,
        )
        assertSchema(result)
        val terminal = RelationCompilation.qualifiedTerminal(batch, limitations).proven()
        val terminalResult = RelationReadResult.Qualified(batch, terminal.coverage).planningEvidence().failure()
        assertEquals(
            HostedPlanningEvidenceFailure.RelationIncomplete(
                limitations,
                HostedPlanningContinuation.TERMINAL_INCOMPLETE,
            ),
            terminalResult,
        )
        assertSchema(terminalResult)
    }

    @Test
    fun `incomplete diagnostic retains the actual limitation without inventing resumability`() {
        val fixture = RelationPagingFixture.live()
        val scope =
            DiagnosticScope.fromCanonicalPaths(fixture.authority, listOf(Path.of("/workspace/Subject.kt"))).proven()
        for (reason in DiagnosticLimitationReason.entries) {
            val coverage =
                DiagnosticIncompleteCoverage.create(
                        scope,
                        emptyList(),
                        setOf(DiagnosticLimitation(scope.files.single(), reason)),
                    )
                    .proven()
            val failure =
                DiagnosticCheckResult.Qualified(DiagnosticBatch.empty(scope), coverage).planningEvidence().failure()
            assertEquals(HostedPlanningEvidenceFailure.DiagnosticIncomplete(setOf(reason)), failure)
            assertSchema(failure)
        }
    }

    @Test
    fun `only complete traversal and diagnostic proofs advance planning`() {
        val fixture = RelationPagingFixture.live()
        val plan =
            TraversalPlan.start(
                    fixture.selector,
                    RelationMeaning.References,
                    TraversalBudget(
                        fixture.budget.resources.resultLimit,
                        TraversalByteLimit.parse(fixture.budget.returnedBytes.value).proven(),
                        fixture.budget.resources.workUnitLimit,
                        fixture.budget.resources.elapsedTimeLimit,
                        TraversalDepthLimit.parse(1).proven(),
                        TraversalFrontierLimit.parse(1).proven(),
                        fixture.budget,
                    ),
                )
                .proven()
        val page = TraversalPage.fromBoundary(plan, emptyList(), 0, 0, 0, 0).proven()
        val complete = TraversalResult.complete(page)
        assertSame(complete, complete.planningEvidence().proven())
        val partial =
            TraversalResult.qualifiedTerminal(
                    page,
                    setOf(TraversalLimitation.ONE_HOP_INCOMPLETE),
                    setOf(RelationLimitation.UNRESOLVED_TARGET, RelationLimitation.UNSUPPORTED_ITEM),
                )
                .proven()
        val failure = partial.planningEvidence().failure()
        assertEquals(
            HostedPlanningEvidenceFailure.TraversalIncomplete(
                setOf(TraversalLimitation.ONE_HOP_INCOMPLETE),
                setOf(RelationLimitation.UNRESOLVED_TARGET, RelationLimitation.UNSUPPORTED_ITEM),
                HostedPlanningContinuation.TERMINAL_INCOMPLETE,
            ),
            failure,
        )
        assertSchema(failure)
        val scope =
            DiagnosticScope.fromCanonicalPaths(fixture.authority, listOf(Path.of("/workspace/Subject.kt"))).proven()
        val compiled = DiagnosticCompilation.complete(DiagnosticBatch.empty(scope))
        val diagnostic = DiagnosticCheckResult.Complete(compiled.batch, compiled.coverage)
        assertSame(diagnostic, diagnostic.planningEvidence().proven())
    }

    private fun batch(request: RelationRequest) =
        RelationBatch.create(
                request,
                emptyList(),
                RelationByteCount.parse(0).proven(),
                RelationWorkCount.parse(0).proven(),
                RelationResultCount.parse(0).proven(),
            )
            .proven()

    private fun assertEncodedCause(failure: HostedPlanningEvidenceFailure, type: String, cause: String) {
        val document = assertSchema(failure)
        val detail =
            Json.parseToJsonElement(document).jsonObject.getValue("detail").jsonObject.getValue("cause").jsonObject
        assertEquals(type, detail.getValue("type").jsonPrimitive.content)
        assertEquals(cause, detail.getValue("cause").jsonPrimitive.content)
    }

    private fun assertSchema(failure: HostedPlanningEvidenceFailure): String {
        val document = HostedResponse.ChangeRejected(HostedChangeFailure.PlanningEvidence(failure)).document
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(checkNotNull(javaClass.getResource("/ide-hosted/hosted-endpoint.schema.json")).readText())
        assertTrue(schema.validate(document, InputFormat.JSON).isEmpty(), document)
        assertEquals(
            "CHANGE_PLANNING_EVIDENCE_REJECTED",
            Json.parseToJsonElement(document).jsonObject.getValue("failure").jsonPrimitive.content,
        )
        return document
    }

    private fun <T> Refinement<T, *>.proven(): T = (this as Refinement.Refined).value

    private fun <T> Refinement<T, HostedPlanningEvidenceFailure>.failure() = (this as Refinement.Rejected).failure
}
