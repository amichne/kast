package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CompleteOnlyQueryTest : AutomaticSymbolQueryCase() {
    private val completeOnly
        get() = request

    @Test
    fun `complete only succeeds after automatic exhaustion and retains original question policy`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result = protocol.execute(completeOnly, fixture.authority, budget, policy()) as OperationOutcome.Complete
        assertEquals(
            QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
            result.evidence.payload.question.completion.model,
        )
        script.assertDrained()
    }

    @Test
    fun `incomplete execution rejects with retrievable original evidence and qualification`() = runTest {
        val script = Script(listOf(listOf(row)), terminal = true)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result = protocol.execute(completeOnly, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val rejection = assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, result.reason)
        assertEquals(QueryCompletionUnprovenReason.INCOMPLETE_EXECUTION, rejection.reason)
        val original =
            assertInstanceOf(QueryCompletionCoverageDocument.Qualified::class.java, rejection.originalCoverage)
        assertTrue(QueryLimitationDocument.RELATION_INCOMPLETE in original.limitations.values)
        val retained = assertInstanceOf(QueryCompletionEvidenceDocument.Retained::class.java, rejection.evidence)
        val projected =
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.project(
                OperationOutcome.Rejected(rejection)
            ) as io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Rejected
        val encoded =
            Json.parseToJsonElement(projected.document.value)
                .jsonObject
                .getValue("rejection")
                .jsonObject
                .getValue("detail")
                .jsonObject
                .getValue("evidence")
                .jsonObject
        assertTrue(encoded.containsKey("preview"), "Strict rejection must expose its bounded proven rows")
        assertEquals(1, encoded.getValue("preview").jsonArray.size)
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryQuestionDocument.from(completeOnly),
            retained.question,
        )
        val next = retained.readRequest()
        assertEquals(QueryRunRequest.ReadResult.symbols(retained.result, output = output), next)
        val read =
            protocol.executePage(
                next,
                fixture.authority,
                budget,
            )
        val qualified = read as OperationOutcome.Qualified
        assertEquals(1, qualified.evidence.payload.items.values.size)
        assertEquals(
            QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
            qualified.evidence.payload.question.completion.model,
        )
        script.assertDrained()
    }

    @Test
    fun `strict preview fits the complete public envelope without losing retained rows or coverage`() = runTest {
        fun encodedBytes(page: QueryPublishedPage): Long {
            val projected =
                io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.project(page)
                    as io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Rejected
            return projected.document.value.toByteArray(Charsets.UTF_8).size.toLong()
        }
        val baselineScript = Script(listOf(listOf(row, row)), terminal = true)
        val baseline =
            CanonicalQueryProtocol(baselineScript.operations, fixture.references)
                .execute(completeOnly, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val original = baseline.reason as QueryRunRejection.CompletionUnproven
        val originalRetained = original.evidence as QueryCompletionEvidenceDocument.Retained
        val oneRow =
            OperationOutcome.Rejected(
                original.copy(
                    evidence = originalRetained.copy(preview = bounded(originalRetained.preview.values.take(1)))
                )
            )
        val grant = encodedBytes(oneRow)
        assertTrue(encodedBytes(baseline) > grant)
        val script = Script(listOf(listOf(row, row)), terminal = true)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val fitted =
            protocol.execute(
                completeOnly,
                fixture.authority,
                budget,
                policy(
                    inlinePresentation = { page ->
                        if (encodedBytes(page) <= grant) QueryInlinePresentation.FITS
                        else QueryInlinePresentation.RETENTION_REQUIRED
                    }
                ),
            ) as OperationOutcome.Rejected
        val reason = fitted.reason as QueryRunRejection.CompletionUnproven
        val evidence = reason.evidence as QueryCompletionEvidenceDocument.Retained
        assertTrue(encodedBytes(fitted) <= grant)
        assertEquals(1, evidence.preview.values.size)
        assertEquals(original.originalCoverage, reason.originalCoverage)
        val read = protocol.executePage(evidence.readRequest(), fixture.authority, budget) as OperationOutcome.Qualified
        assertEquals(2, read.evidence.payload.items.values.size)
        baselineScript.assertDrained()
        script.assertDrained()
    }

    @Test
    fun `unfittable mandatory rejection preserves its original preview for outer typed byte rejection`() = runTest {
        val script = Script(listOf(listOf(row)), terminal = true)
        var attempts = 0
        val rejected =
            CanonicalQueryProtocol(script.operations, fixture.references)
                .execute(
                    completeOnly,
                    fixture.authority,
                    budget,
                    policy(
                        inlinePresentation = {
                            attempts++
                            QueryInlinePresentation.RETENTION_REQUIRED
                        }
                    ),
                ) as OperationOutcome.Rejected
        val reason = rejected.reason as QueryRunRejection.CompletionUnproven
        val retained = reason.evidence as QueryCompletionEvidenceDocument.Retained
        assertEquals(1, retained.preview.values.size, "An unproved empty-envelope fit cannot erase facts")
        assertTrue(attempts >= 2)
        script.assertDrained()
    }

    @Test
    fun `original resumability is evidence and does not advertise a policy resume`() = runTest {
        var interrupted = false
        val script = Script(listOf(listOf(row), listOf(row)), afterPage = { interrupted = true })
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val outcome =
            protocol.execute(completeOnly, fixture.authority, budget, policy(cancelled = { interrupted }))
                as OperationOutcome.Rejected
        val rejection = assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, outcome.reason)
        val original =
            assertInstanceOf(QueryCompletionCoverageDocument.Qualified::class.java, rejection.originalCoverage)
        val progress =
            assertInstanceOf(
                io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument.Resumable::class.java,
                original.progress,
            )
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCompletionPolicyProgressDocument.EvidenceOnly,
            rejection.policyProgress,
        )
        assertEquals(1, script.calls)
        val resume =
            protocol.execute(QueryRunRequest.Resume(progress.checkpoint.token), fixture.authority, budget, policy())
                as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(
                io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.REQUEST_REJECTED
            ),
            resume.reason,
        )
        val handle = (rejection.evidence as QueryCompletionEvidenceDocument.Retained).result
        val read =
            protocol.executePage(QueryRunRequest.ReadResult.symbols(handle, output = output), fixture.authority, budget)
                as OperationOutcome.Qualified
        assertInstanceOf(
            io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument.TerminalIncomplete::class.java,
            read.qualification.progress,
        )
        val interpretation =
            read.evidence.payload.interpretation
                as io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument.EvidenceOnly
        assertEquals(original, interpretation.originalCoverage)
        assertEquals(1, script.calls)
    }
}
