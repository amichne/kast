package io.github.amichne.kast.change.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CanonicalChangePlanProtocolTest {
    @Test
    fun `reference restoration belongs to the admitted host and receives the untouched request`() = runTest {
        // No decoder runs here: only the owning host can decide whether these bytes are its reference.
        val target = text("opaque-reference-kept-verbatim==")
        val request = ChangePlanRequest(ChangeIntentDocument.AddDeclaration(target, text("fun added() = Unit")))
        var calls = 0
        val protocol =
            CanonicalChangePlanProtocol(
                ChangePlanningOperations(
                    addFile = { error("rejected admission must not plan") },
                    addDeclaration = { error("rejected admission must not plan") },
                    replaceDeclaration = { error("rejected admission must not plan") },
                    renameSymbol = { error("rejected admission must not plan") },
                ),
                { received ->
                    calls++
                    assertSame(request, received)
                    assertEquals(
                        target.value,
                        (received.intent as ChangeIntentDocument.AddDeclaration).exactTarget.value,
                    )
                    ChangePlanAdmission.Rejected(ChangePlanAdmissionFailure.EXACT_SYMBOL_REQUIRED)
                },
                { error("rejected admission must not issue a durable plan") },
            )
        assertEquals(OperationOutcome.Rejected(ChangePlanRejection.EXACT_SYMBOL_REQUIRED), protocol.execute(request))
        assertEquals(1, calls)
    }

    private fun text(raw: String): ProtocolText =
        when (val parsed = ProtocolText.parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error("invalid fixture text")
        }
}
