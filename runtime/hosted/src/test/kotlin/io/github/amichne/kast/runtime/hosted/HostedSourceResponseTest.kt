package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedSourceResponseTest {
    @Test
    fun `oversized source page publishes a nonempty fitting entity prefix`() = runTest {
        val original = HostedSourcePagingFixture.create().outcome
        val encoded = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.sourceRead, original)
        assertTrue(encoded is HostedResponse.Canonical<*, *, *>)
        val maximum = ReturnedByteLimit.parse(encoded.document.toByteArray().size.toLong() - 1).sourceFixtureValue()
        val response = encodeHostedSourceResponse(original, ReadLimits.Default, maximum)
        assertTrue(response is HostedResponse.Canonical<*, *, *>, "Expected fitting source prefix, got $response")
        assertTrue(response.document.toByteArray().size <= maximum.value)
        assertTrue((response as HostedResponse.Canonical<*, *, *>).semantic is OperationOutcome.Qualified)
    }
}
