package io.github.amichne.kast.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KernelPrimitivesTest {
    @Test
    fun `permanent identities refine canonical raw values`() {
        val operation = OperationId.parse("symbol.discover")
        val capability = CapabilityId.parse("symbol.read")

        assertEquals("symbol.discover", operation.refinedValue().value)
        assertEquals("symbol.read", capability.refinedValue().value)
        assertEquals(
            PermanentIdentityFailure.BLANK,
            OperationId.parse("  ").rejectedFailure(),
        )
        assertEquals(
            PermanentIdentityFailure.INVALID_FORMAT,
            CapabilityId.parse("Symbol Read").rejectedFailure(),
        )
    }

    @Test
    fun `resource budget excludes non-positive and unbounded limits`() {
        val resultLimit = ResultLimit.parse(250).refinedValue()
        val workUnitLimit = WorkUnitLimit.parse(10_000).refinedValue()
        val elapsedTimeLimit = ElapsedTimeLimitMillis.parse(5_000).refinedValue()

        assertEquals(
            ResourceBudget(resultLimit, workUnitLimit, elapsedTimeLimit),
            ResourceBudget(
                resultLimit = resultLimit,
                workUnitLimit = workUnitLimit,
                elapsedTimeLimit = elapsedTimeLimit,
            ),
        )
        assertEquals(
            PositiveLimitFailure.NOT_POSITIVE,
            ResultLimit.parse(0).rejectedFailure(),
        )
        assertEquals(
            PositiveLimitFailure.NOT_POSITIVE,
            WorkUnitLimit.parse(-1).rejectedFailure(),
        )
        assertEquals(
            PositiveLimitFailure.NOT_POSITIVE,
            ElapsedTimeLimitMillis.parse(0).rejectedFailure(),
        )
    }

    @Test
    fun `semantic outcomes retain operation and generation evidence`() {
        val evidence = EvidenceEnvelope(
            operation = OperationId.parse("symbol.discover").refinedValue(),
            generation = EvidenceGeneration.parse(7).refinedValue(),
            payload = listOf("io.github.Example"),
        )

        val complete: OperationOutcome<List<String>, String, String> =
            OperationOutcome.Complete(evidence)
        val qualified: OperationOutcome<List<String>, String, String> =
            OperationOutcome.Qualified(evidence, "bounded")
        val rejected: OperationOutcome<List<String>, String, String> =
            OperationOutcome.Rejected("not-ready")

        assertEquals("symbol.discover", complete.evidence().operation.value)
        assertEquals(7, qualified.evidence().generation.value)
        assertEquals("not-ready", rejected.rejectionReason())
        assertEquals(
            EvidenceGenerationFailure.NEGATIVE,
            EvidenceGeneration.parse(-1).rejectedFailure(),
        )
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejectedFailure(): Failure = when (this) {
        is Refinement.Refined -> error("Expected rejection, got $value")
        is Refinement.Rejected -> failure
    }

    private fun <Payload, Qualification, Rejection> OperationOutcome<Payload, Qualification, Rejection>.evidence(): EvidenceEnvelope<Payload> =
        when (this) {
            is OperationOutcome.Complete -> evidence
            is OperationOutcome.Qualified -> evidence
            is OperationOutcome.Rejected -> error("Rejected outcomes have no evidence: $reason")
        }

    private fun <Payload, Qualification, Rejection> OperationOutcome<Payload, Qualification, Rejection>.rejectionReason(): Rejection =
        when (this) {
            is OperationOutcome.Complete -> error("Complete outcomes are not rejected")
            is OperationOutcome.Qualified -> error("Qualified outcomes are not rejected")
            is OperationOutcome.Rejected -> reason
        }
}
