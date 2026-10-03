package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ImpactPeerShapeTest {
    private val fixture = ImpactPeerFixture()
    private val json = Json { encodeDefaults = false }

    @Test
    fun `path and compact finding preserve full independent peer receipt and required unresolved reason`() {
        val expected = expected("impact-peer-terminal.expected.json")
        assertEquals(expected, json.encodeToJsonElement(ImpactPathTerminalDocument.serializer(), fixture.terminal))
        assertEquals(
            expected,
            json.encodeToJsonElement(ImpactFindingTerminalDocument.serializer(), fixture.findingTerminal),
        )
    }

    @Test
    fun `unused reviewed peer model retains its completed native target receipt in models witness`() {
        assertEquals(
            expected("impact-peer-model.expected.json"),
            json.encodeToJsonElement(ImpactWitnessDocument.serializer(), fixture.witness),
        )
    }

    @Test
    fun `every finite peer proof failure preserves cause position and explicit recovery`() {
        val cases =
            ImpactPeerProofFailureDocument.entries.map {
                QueryImpactSourceFailureDocument.PeerProof(it, fixture.values.offset(1))
            }
        assertEquals(
            expected("impact-peer-failures.expected.json"),
            json.encodeToJsonElement(ListSerializer(QueryImpactSourceFailureDocument.serializer()), cases),
        )
        val expectedActions =
            listOf(
                ReadRecoveryAction.REPORT_FAILURE,
                ReadRecoveryAction.REPORT_FAILURE,
                ReadRecoveryAction.REPORT_FAILURE,
                ReadRecoveryAction.REPORT_FAILURE,
                ReadRecoveryAction.REACQUIRE_AUTHORITY,
                ReadRecoveryAction.REACQUIRE_AUTHORITY,
                ReadRecoveryAction.REACQUIRE_AUTHORITY,
                ReadRecoveryAction.CORRECT_REQUEST,
                ReadRecoveryAction.REPORT_FAILURE,
            )
        assertEquals(expectedActions, cases.map { QueryRunRejection.ImpactSourceRejected(it).recoveryAction() })
    }

    @Test
    fun `missing peer receipt reason or unknown finite cause fails raw decoding`() {
        val terminal = json.encodeToString(ImpactPathTerminalDocument.serializer(), fixture.terminal)
        val malformed =
            listOf(
                terminal.replace(",\"reason\":\"PEER_FLOW_NOT_INVESTIGATED\"", ""),
                terminal.replace("\"completedBasis\"", "\"unprovenBasis\""),
                terminal.replace("PEER_FLOW_NOT_INVESTIGATED", "UNKNOWN"),
            )
        malformed.forEach { raw ->
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(ImpactPathTerminalDocument.serializer(), raw)
            }
        }
    }

    private fun expected(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/$name")).readText())
}
