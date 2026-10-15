package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrokerPublicEndpointModeTest {
    @Test
    fun `absent selection keeps Kast on its private socket`() {
        assertEquals(Refinement.Refined(BrokerPublicEndpointMode.PRIVATE), BrokerPublicEndpointMode.admit(null))
    }

    @Test
    fun `Codex control needs an explicit selection and unknown modes reject`() {
        assertEquals(
            Refinement.Refined(BrokerPublicEndpointMode.CODEX_CONTROL),
            BrokerPublicEndpointMode.admit("codex-control"),
        )
        assertEquals(
            Refinement.Rejected(BrokerPublicEndpointModeFailure.INVALID_PUBLIC_ENDPOINT),
            BrokerPublicEndpointMode.admit("unknown"),
        )
    }
}
