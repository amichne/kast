package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PredispatchRejectionCertaintyTest {
    private val address =
        ToolAddress(
            (ProviderNamespace.admit("kast") as Refinement.Refined).value,
            (ToolName.admit("query_symbols") as Refinement.Refined).value,
        )

    @Test
    fun `root discovery rejection is known while unobserved execution stays uncertain`() {
        val beforeDispatch =
            setOf(
                ProviderFailureCode.WORKSPACE_START_UNAVAILABLE,
                ProviderFailureCode.WORKSPACE_START_NOT_DIRECTORY,
                ProviderFailureCode.WORKSPACE_ROOT_MARKER_NOT_FOUND,
                ProviderFailureCode.WORKSPACE_INVALID_ROOT_MARKER,
            )
        for (code in ProviderFailureCode.entries) {
            assertEquals(
                if (code in beforeDispatch) InvocationCertainty.KNOWN else InvocationCertainty.UNCERTAIN,
                BrokerFailure.ProviderInvocationRejected(address, code).certainty(),
                code.name,
            )
        }
    }
}
