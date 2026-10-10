package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryDiagnosticReadIdentityTest {
    @Test
    fun `boundary UUID has an independently specified canonical scalar encoding`() {
        val identity = QueryDiagnosticReadIdentity.fromBoundary(UUID.fromString("33333333-3333-4333-8333-333333333333"))
        assertEquals("\"33333333-3333-4333-8333-333333333333\"", Json.encodeToString(identity))
        assertEquals(
            identity,
            Json.decodeFromString<QueryDiagnosticReadIdentity>("\"33333333-3333-4333-8333-333333333333\""),
        )
    }

    @Test
    fun `unknown and noncanonical diagnostic identities are rejected at the boundary`() {
        for (raw in
            listOf("", "read", "33333333-3333-4333-8333-333333333333-extra", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")) {
            assertEquals(
                Refinement.Rejected(QueryDiagnosticReadIdentityFailure.MALFORMED),
                QueryDiagnosticReadIdentity.parse(raw),
            )
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString<QueryDiagnosticReadIdentity>(Json.encodeToString(raw))
            }
        }
    }
}
