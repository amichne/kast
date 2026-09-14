package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedRelationResponseTest {
    @Test
    fun `oversized relation page publishes fitting facts and retains every unreturned occurrence`() = runTest {
        val semantic = RelationPagingFixture.live().page() as OperationOutcome.Qualified
        val original = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.relationRead, semantic)
        val maximumBytes = original.document.toByteArray().size - 1
        val limits =
            (ReadLimits.resolve(
                    environment =
                        mapOf(
                            "KAST_READ_HOST_RESPONSE_BYTES" to maximumBytes.toString(),
                            "KAST_READ_SEMANTIC_RETURNED_BYTES" to maximumBytes.toString(),
                            "KAST_READ_SOURCE_RETURNED_BYTES" to maximumBytes.toString(),
                        )
                ) as Refinement.Refined)
                .value
        val retained = mutableListOf<HostedRelationOutcome>()
        val response =
            encodeHostedRelationResponse(semantic, limits) {
                retained.add(it)
                HostedOutputRetention.Retained(
                    (ProtocolText.parse("relation-output:v1:00000000-0000-0000-0000-000000000001")
                            as Refinement.Refined)
                        .value
                )
            }
        assertTrue(response is HostedResponse.Canonical<*, *, *>)
        assertTrue(response.document.toByteArray().size <= maximumBytes)
        val prefix = (response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified
        val published = prefix.evidence.payload as io.github.amichne.kast.protocol.contract.RelationReadResult
        val suffix = retained.single() as OperationOutcome.Qualified
        assertFalse(published.relations.values.isEmpty())
        assertFalse(suffix.evidence.payload.relations.values.isEmpty())
        assertEquals(
            semantic.evidence.payload.relations.values,
            published.relations.values + suffix.evidence.payload.relations.values,
        )
        assertEquals(semantic.evidence.payload.omissions, suffix.evidence.payload.omissions)
    }
}
