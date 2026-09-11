package io.github.amichne.kast.workspace.intellij.read.hosted

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedQueryLifetimeTest {
    @Test
    fun `detach invalidates the exact endpoint and every outstanding publication`() {
        val lifetime = HostedQueryLifetime()
        val token = lifetime.endpoint
        val admitted = lifetime.begin(token) as HostedQueryAdmission.Admitted
        lifetime.retire()
        assertEquals(HostedQueryAdmission.Rejected(HostedQueryFailure.RETIRED), lifetime.begin(token))
        assertEquals(HostedQueryCompletion.Rejected(HostedQueryFailure.RETIRED), lifetime.complete(admitted.permit))
    }

    @Test
    fun `foreign endpoint and concurrent requests cannot invoke analysis`() {
        val lifetime = HostedQueryLifetime()
        assertEquals(
            HostedQueryAdmission.Rejected(HostedQueryFailure.WRONG_ENDPOINT),
            lifetime.begin(HostedQueryLifetime().endpoint),
        )
        val admitted = lifetime.begin(lifetime.endpoint) as HostedQueryAdmission.Admitted
        assertEquals(HostedQueryAdmission.Rejected(HostedQueryFailure.BUSY), lifetime.begin(lifetime.endpoint))
        assertEquals(HostedQueryCompletion.Published, lifetime.complete(admitted.permit))
        assertTrue(lifetime.begin(lifetime.endpoint) is HostedQueryAdmission.Admitted)
    }

    @Test
    fun `consumed and foreign permits cannot release another request`() {
        val lifetime = HostedQueryLifetime()
        val first = (lifetime.begin(lifetime.endpoint) as HostedQueryAdmission.Admitted).permit
        lifetime.complete(first)
        val second = (lifetime.begin(lifetime.endpoint) as HostedQueryAdmission.Admitted).permit
        assertEquals(HostedQueryCompletion.Rejected(HostedQueryFailure.STALE_REQUEST), lifetime.complete(first))
        assertEquals(HostedQueryAdmission.Rejected(HostedQueryFailure.BUSY), lifetime.begin(lifetime.endpoint))
        assertEquals(HostedQueryCompletion.Published, lifetime.complete(second))
    }
}
