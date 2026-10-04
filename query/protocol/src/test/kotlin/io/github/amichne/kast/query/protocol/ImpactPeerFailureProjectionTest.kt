package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryImpactLedgerFailure
import io.github.amichne.kast.query.contract.QueryImpactPeerProofFailure
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactPeerFailureProjectionTest {
    @Test
    fun `peer boundary execution and foreign ledger failures retain their exact finite public causes`() {
        val cases =
            QueryImpactPeerProofFailure.entries.map {
                QueryImpactExecutionFailure.PeerBoundary(it).executionDocument()
            } + QueryImpactExecutionFailure.Ledger(QueryImpactLedgerFailure.FOREIGN_PEER_BASIS).executionDocument()
        val expected =
            Json.parseToJsonElement(
                checkNotNull(javaClass.getResource("/query/impact-peer-execution-failures.expected.json")).readText()
            )
        assertEquals(
            expected,
            Json.encodeToJsonElement(ListSerializer(ImpactExecutionFailureDocument.serializer()), cases),
        )
    }
}
