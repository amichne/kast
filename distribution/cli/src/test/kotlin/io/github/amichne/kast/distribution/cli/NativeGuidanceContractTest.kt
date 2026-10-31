package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@Serializable private data class NativeTestCommand(val command: String)

@Serializable
private data class NativeTestInventory(
    val schema: String = "kast.cli.describe.v1",
    @SerialName("public_contract_version") val contract: Int = 18,
    val catalog: String = "COMPLETE",
    val section: String = "inventory",
    val content: List<NativeTestCommand> =
        listOf("query", "query.plan", "result.read", "result.resume", "source.read", "describe")
            .map(::NativeTestCommand),
)

class NativeGuidanceContractTest {
    @Test
    fun `native inventory admits supported contract and rejects missing commands or another schema`() {
        val accepted = NativeTestInventory()
        assertEquals(PluginObservation.Observed(Unit), admitNativeCliInventory(managementJson.encodeToString(accepted)))
        for (document in
            listOf(
                accepted.copy(schema = "different"),
                accepted.copy(contract = 17),
                accepted.copy(content = accepted.content.dropLast(1)),
                accepted.copy(section = "input"),
            )) assertEquals(
            PluginObservation.Rejected(PluginInstallFailure.PayloadRejected),
            admitNativeCliInventory(managementJson.encodeToString(document)),
        )
    }
}
