package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.validateImpactCompletion
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RetainedImpactWindowWireTest {
    @Test
    fun `retained complete preview window survives canonical wire and refitting`() = runTest {
        val fixture = ImpactWitnessPresentationFixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val original =
            protocol.executePage(
                QueryRunRequest.ReadResult.valuePaths(fixture.reference),
                fixture.symbols.authority,
                fixture.budget,
            ) as OperationOutcome.Complete
        val prefix = original.evidence.payload.presentationPrefix(0).value()
        val complete = OperationOutcome.Complete(original.evidence.copy(payload = prefix))
        val expected = prefix.copy(presentationOrigin = null)
        val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(complete) as WireEncoding.Encoded
        val restored =
            when (val decoded = CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded.document)) {
                is WireDecoding.Decoded -> decoded.value
                is WireDecoding.Rejected -> error("Retained preview lost its wire proof: ${decoded.failure}")
            }
        assertEquals(OperationOutcome.Complete(original.evidence.copy(payload = expected)), restored)
        val result = (restored as OperationOutcome.Complete).evidence.payload
        assertEquals(prefix.presentationWindow, result.presentationWindow)
        assertEquals(Refinement.Refined(Unit), result.validateImpactCompletion())
        assertEquals(expected, result.presentationPrefix(0).value())
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
