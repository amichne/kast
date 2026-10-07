package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackSupplierObservationTest {
    @Test
    fun `supplier resource and semantic refusals remain distinct bounded outcomes`() {
        assertEquals(
            IntellijReadTermination.CALLBACK_SUPPLIER_WORK_LIMIT_REACHED,
            CallbackInvocationFlowCause.WORK_LIMIT_REACHED.supplierTermination(),
        )
        assertEquals(
            IntellijReadTermination.CALLBACK_SUPPLIER_TIME_LIMIT_REACHED,
            CallbackInvocationFlowCause.TIME_LIMIT_REACHED.supplierTermination(),
        )
        assertEquals(
            IntellijReadTermination.CALLBACK_SUPPLIER_UNRESOLVED_ARGUMENT_MAPPING,
            CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING.supplierTermination(),
        )
        assertEquals(
            IntellijReadTermination.CALLBACK_SUPPLIER_OUTSIDE_DOMAIN,
            CallbackInvocationFlowCause.OUTSIDE_DOMAIN.supplierTermination(),
        )
        assertEquals(
            CallbackInvocationFlowCause.entries.size,
            CallbackInvocationFlowCause.entries.map { it.supplierTermination() }.toSet().size,
        )
    }
}
