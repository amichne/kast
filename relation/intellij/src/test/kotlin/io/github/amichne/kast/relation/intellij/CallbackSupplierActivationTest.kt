package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackSupplierActivationTest {
    @Test
    fun `retention exhaustion stops supplier activation and preserves qualifications`() {
        for (cause in
            setOf(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED, CallbackInvocationFlowCause.BYTE_LIMIT_REACHED)) {
            val stopped =
                snapshot(setOf(cause)).withSupplier(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION))
            assertEquals(CallbackSupplierActivation.CAPACITY_EXHAUSTED, stopped.supplierActivation())
            assertEquals(setOf(cause, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION), stopped.obligations)
            assertEquals(CallbackInvocationScan.INCOMPLETE, stopped.scan)
            assertEquals(CallbackForwardingEvidence.InvocationRoutes, stopped.forwarding)
        }
    }

    @Test
    fun `noncapacity qualifications retain supplier activation admission`() {
        assertEquals(CallbackSupplierActivation.AVAILABLE, snapshot(emptySet()).supplierActivation())
        assertEquals(
            CallbackSupplierActivation.AVAILABLE,
            snapshot(setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)).supplierActivation(),
        )
    }

    private fun snapshot(causes: Set<CallbackInvocationFlowCause>) =
        CallbackFormalScanSnapshot(
            emptyList(),
            causes,
            emptyList(),
            CallbackInvocationScan.INCOMPLETE,
            CallbackForwardingEvidence.InvocationRoutes,
        )
}
