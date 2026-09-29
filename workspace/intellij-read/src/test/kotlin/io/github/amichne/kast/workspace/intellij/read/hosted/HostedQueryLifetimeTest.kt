package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedQueryLifetimeTest {
    private val capacity = ReadLimits.Default[ReadLimitParameter.HOST_READERS].value

    @Test
    fun `detach invalidates the exact endpoint and every outstanding publication`() {
        val lifetime = HostedQueryLifetime()
        val permits = List(capacity) { (lifetime.begin(lifetime.endpoint) as HostedQueryAdmission.Admitted).permit }
        lifetime.retire()
        assertEquals(HostedQueryAdmission.Rejected(HostedQueryFailure.RETIRED), lifetime.begin(lifetime.endpoint))
        permits.forEach {
            assertEquals(HostedQueryCompletion.Rejected(HostedQueryFailure.RETIRED), lifetime.complete(it))
        }
    }

    @Test
    fun `parallel admissions are bounded and foreign endpoints cannot consume capacity`() {
        val lifetime = HostedQueryLifetime()
        assertEquals(
            HostedQueryAdmission.Rejected(HostedQueryFailure.WRONG_ENDPOINT),
            lifetime.begin(HostedQueryLifetime().endpoint),
        )
        val permits = List(capacity) { (lifetime.begin(lifetime.endpoint) as HostedQueryAdmission.Admitted).permit }
        assertEquals(HostedQueryAdmission.Rejected(HostedQueryFailure.BUSY), lifetime.begin(lifetime.endpoint))
        assertEquals(HostedQueryCompletion.Published, lifetime.complete(permits.first()))
        assertTrue(lifetime.begin(lifetime.endpoint) is HostedQueryAdmission.Admitted)
        assertEquals(HostedQueryAdmission.Rejected(HostedQueryFailure.BUSY), lifetime.begin(lifetime.endpoint))
    }

    @Test
    fun `consumed and foreign permits cannot release another request`() {
        val lifetime = HostedQueryLifetime()
        val permits = List(capacity) { (lifetime.begin(lifetime.endpoint) as HostedQueryAdmission.Admitted).permit }
        val first = permits.first()
        lifetime.complete(first)
        val replacement = (lifetime.begin(lifetime.endpoint) as HostedQueryAdmission.Admitted).permit
        assertEquals(HostedQueryCompletion.Rejected(HostedQueryFailure.STALE_REQUEST), lifetime.complete(first))
        assertEquals(
            HostedQueryCompletion.Rejected(HostedQueryFailure.STALE_REQUEST),
            lifetime.complete(HostedQueryPermit()),
        )
        assertEquals(HostedQueryAdmission.Rejected(HostedQueryFailure.BUSY), lifetime.begin(lifetime.endpoint))
        assertEquals(HostedQueryCompletion.Published, lifetime.complete(replacement))
        permits.drop(1).forEach { assertEquals(HostedQueryCompletion.Published, lifetime.complete(it)) }
    }
}
