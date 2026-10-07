package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
import io.github.amichne.kast.protocol.contract.QueryCompletionUnsupportedReason
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CompleteOnlyQueryTest : AutomaticSymbolQueryCase() {
    private val completeOnly
        get() =
            request.copy(
                completion =
                    QueryCompletionPolicyDocument.CompleteOnly(QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1)
            )

    @Test
    fun `complete only succeeds after automatic exhaustion and retains original question policy`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(completeOnly, fixture.authority, budget, policy())
                as OperationOutcome.Complete
        assertEquals(completeOnly.completion, result.evidence.payload.question.completion)
        script.assertDrained()
    }

    @Test
    fun `incomplete execution rejects with retrievable original evidence and qualification`() = runTest {
        val script = Script(listOf(listOf(row)), terminal = true)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(completeOnly, fixture.authority, budget, policy())
                as OperationOutcome.Rejected
        val rejection = assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, result.reason)
        assertEquals(QueryCompletionUnprovenReason.INCOMPLETE_EXECUTION, rejection.reason)
        val original =
            assertInstanceOf(QueryCompletionCoverageDocument.Qualified::class.java, rejection.originalCoverage)
        assertTrue(QueryLimitationDocument.RELATION_INCOMPLETE in original.limitations.values)
        val retained = assertInstanceOf(QueryCompletionEvidenceDocument.Retained::class.java, rejection.evidence)
        val read =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(retained.result, output = output),
                fixture.authority,
                budget,
            )
        val qualified = read as OperationOutcome.Qualified
        assertEquals(1, qualified.evidence.payload.items.values.size)
        assertEquals(completeOnly.completion, qualified.evidence.payload.question.completion)
        script.assertDrained()
    }

    @Test
    fun `original resumability is evidence and does not advertise a policy resume`() = runTest {
        var interrupted = false
        val script = Script(listOf(listOf(row), listOf(row)), afterPage = { interrupted = true })
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val outcome =
            protocol.executeAutomatically(completeOnly, fixture.authority, budget, policy(cancelled = { interrupted }))
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
        val resume =
            protocol.execute(QueryRunRequest.Resume(progress.checkpoint.token), fixture.authority, budget)
                as OperationOutcome.Rejected
        assertEquals(
            QueryCompletionUnsupportedReason.AUTOMATIC_EXECUTION_REQUIRED,
            (resume.reason as QueryRunRejection.CompletionUnsupported).reason,
        )
        val handle = (rejection.evidence as QueryCompletionEvidenceDocument.Retained).result
        val read =
            protocol.execute(QueryRunRequest.ReadResult.symbols(handle, output = output), fixture.authority, budget)
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

    @Test
    fun `direct page execution cannot bypass automatic completion policy`() = runTest {
        val script = Script(listOf(listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val outcome = protocol.execute(completeOnly, fixture.authority, budget) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.CompletionUnsupported(
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                QueryCompletionUnsupportedReason.AUTOMATIC_EXECUTION_REQUIRED,
            ),
            outcome.reason,
        )
        assertEquals(0, script.calls)
    }
}
