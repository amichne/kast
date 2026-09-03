package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.change.plan.PureAddFilePlanningService
import io.github.amichne.kast.change.plan.PureRenameSymbolPlanningService
import io.github.amichne.kast.change.plan.PureReplaceDeclarationPlanningService
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangeAuthority
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangePlanHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmissionOperations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import kotlin.coroutines.startCoroutine

class CanonicalChangePlanTargetAdmissionTest {
    @Test
    fun `change plan rejects a manufactured exact target before semantic admission`() {
        var admissionInvoked = false
        val handler = CanonicalChangePlanHandler(
            ChangePlanningOperations(
                PureAddFilePlanningService(),
                PureAddDeclarationPlanningService(),
                PureReplaceDeclarationPlanningService(),
                PureRenameSymbolPlanningService(),
            ),
            ChangePlanAdmissionOperations {
                admissionInvoked = true
                error("manufactured targets must not reach semantic admission")
            },
            CanonicalProtocolAuthority(),
            CanonicalChangeAuthority(),
        )

        val outcome = runImmediateTargetAdmission {
            handler.execute(
                ChangePlanRequest(
                    ChangeIntentDocument.AddDeclaration(
                        ProtocolText.parse("manufactured-target").refinedTargetAdmission(),
                        ProtocolText.parse("fun added() = Unit").refinedTargetAdmission(),
                    ),
                ),
            )
        }

        assertEquals(OperationOutcome.Rejected(ChangePlanRejection.EXACT_SYMBOL_REQUIRED), outcome)
        assertFalse(admissionInvoked)
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedTargetAdmission(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("unexpected rejection: $failure")
    }

private fun <Value> runImmediateTargetAdmission(block: suspend () -> Value): Value {
    var completed: Result<Value>? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Value> {
            override val context = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Value>) {
                completed = result
            }
        },
    )
    return checkNotNull(completed) { "operation suspended unexpectedly" }.getOrThrow()
}
