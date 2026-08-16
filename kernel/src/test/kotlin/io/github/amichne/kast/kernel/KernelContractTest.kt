package io.github.amichne.kast.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KernelContractTest {
    @Test
    fun `boundary primitives refine into closed kernel proof types`() {
        val root = NamedRoot.parse("workspace.primary").refinedValue()
        val operation = OperationId.parse("symbol.discover").refinedValue()
        val capability = CapabilityId.parse("symbol.read").refinedValue()
        val typedCapability: CapabilityMarker = SymbolReadCapability(capability)
        val generation = EvidenceGeneration.parse(7).refinedValue()
        val budget = ResourceBudget(
            resultLimit = ResultLimit.parse(250).refinedValue(),
            workUnitLimit = WorkUnitLimit.parse(10_000).refinedValue(),
            elapsedTimeLimit = ElapsedTimeLimitMillis.parse(5_000).refinedValue(),
        )
        val evidence = EvidenceEnvelope(
            operation = operation,
            generation = generation,
            payload = root,
        )
        val complete: OperationOutcome<NamedRoot, Qualification, Failure> =
            OperationOutcome.Complete(evidence)
        val qualified: OperationOutcome<NamedRoot, Qualification, Failure> =
            OperationOutcome.Qualified(evidence, Qualification.BOUNDED)
        val rejected: OperationOutcome<NamedRoot, Qualification, Failure> =
            OperationOutcome.Rejected(Failure.NOT_READY)

        assertEquals("workspace.primary", root.value)
        assertEquals(capability, typedCapability.id)
        assertEquals(250, budget.resultLimit.value)
        assertEquals(10_000, budget.workUnitLimit.value)
        assertEquals(5_000, budget.elapsedTimeLimit.value)
        assertEquals(root, complete.evidence().payload)
        assertEquals(Qualification.BOUNDED, qualified.qualification())
        assertEquals(Failure.NOT_READY, rejected.rejection())
    }

    @Test
    fun `invalid boundary primitives return finite typed failures`() {
        assertEquals(NamedRootFailure.BLANK, NamedRoot.parse("  ").rejectedFailure())
        assertEquals(
            NamedRootFailure.TOO_LONG,
            NamedRoot.parse("a".repeat(129)).rejectedFailure(),
        )
        assertEquals(
            NamedRootFailure.INVALID_FORMAT,
            NamedRoot.parse("Workspace Primary").rejectedFailure(),
        )
        assertEquals(
            PermanentIdentityFailure.INVALID_FORMAT,
            OperationId.parse("symbol/discover").rejectedFailure(),
        )
        assertEquals(
            PermanentIdentityFailure.INVALID_FORMAT,
            CapabilityId.parse("Symbol.Read").rejectedFailure(),
        )
        assertEquals(
            EvidenceGenerationFailure.NEGATIVE,
            EvidenceGeneration.parse(-1).rejectedFailure(),
        )
        assertEquals(PositiveLimitFailure.NOT_POSITIVE, ResultLimit.parse(0).rejectedFailure())
        assertEquals(PositiveLimitFailure.NOT_POSITIVE, WorkUnitLimit.parse(0).rejectedFailure())
        assertEquals(
            PositiveLimitFailure.NOT_POSITIVE,
            ElapsedTimeLimitMillis.parse(0).rejectedFailure(),
        )
    }

    private data class SymbolReadCapability(
        override val id: CapabilityId,
    ) : CapabilityMarker

    private enum class Qualification {
        BOUNDED,
    }

    private enum class Failure {
        NOT_READY,
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejectedFailure(): Failure = when (this) {
        is Refinement.Refined -> error("Expected rejection, got $value")
        is Refinement.Rejected -> failure
    }

    private fun <Payload, Qualification, Rejection>
        OperationOutcome<Payload, Qualification, Rejection>.evidence(): EvidenceEnvelope<Payload> =
        when (this) {
            is OperationOutcome.Complete -> evidence
            is OperationOutcome.Qualified -> evidence
            is OperationOutcome.Rejected -> error("Rejected outcome: $reason")
        }

    private fun <Payload, Qualification, Rejection>
        OperationOutcome<Payload, Qualification, Rejection>.qualification(): Qualification =
        when (this) {
            is OperationOutcome.Complete -> error("Complete outcome")
            is OperationOutcome.Qualified -> qualification
            is OperationOutcome.Rejected -> error("Rejected outcome: $reason")
        }

    private fun <Payload, Qualification, Rejection>
        OperationOutcome<Payload, Qualification, Rejection>.rejection(): Rejection =
        when (this) {
            is OperationOutcome.Complete -> error("Complete outcome")
            is OperationOutcome.Qualified -> error("Qualified outcome: $qualification")
            is OperationOutcome.Rejected -> reason
        }
}
