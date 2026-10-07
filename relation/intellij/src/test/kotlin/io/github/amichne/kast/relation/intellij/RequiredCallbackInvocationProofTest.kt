package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Pure required-proof admission; actual receiver mapping is qualified by the native direct-return fixture. */
class RequiredCallbackInvocationProofTest {
    @Test
    fun `ordinary not required outcome cannot discharge a compiler confirmed invocation`() {
        assertEquals(
            SelectedCallbackSuppliesRead.Unavailable(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY),
            SelectedCallbackSuppliesRead.NotRequired.requireInvocationProof(),
        )
    }

    @Test
    fun `required invocation retains the original complete finite failure set`() {
        val failed =
            SelectedCallbackSuppliesRead.Unavailable(
                CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING,
                setOf(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
            )
        assertSame(failed, failed.requireInvocationProof())
    }
}
