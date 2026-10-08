package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DaemonChangeProtocolTest {
    private val identity = "plan:${"a".repeat(64)}"
    private val text = (ProtocolText.parse(identity) as Refinement.Refined).value

    @Test
    fun `apply and recovery encode only canonical requests and retain mutation identity`() {
        val cases =
            listOf(
                DaemonChangeAction.Apply(ChangeApplyRequest(text)) to HostedMutationOperation.CHANGE_APPLY,
                DaemonChangeAction.Recover(ChangeRecoverRequest(text)) to HostedMutationOperation.CHANGE_RECOVER,
            )
        for ((action, kind) in cases) {
            val name = if (kind == HostedMutationOperation.CHANGE_APPLY) "apply" else "recover"
            assertEquals(
                resource("$name.json"),
                DaemonOperationProtocol.json.encodeToString(DaemonChangeAction.serializer(), action),
            )
            val operation =
                (mcpOperation(DaemonOperationCall.Change(action)) as Refinement.Refined).value
                    as ExistingIdeOperation.Mutation
            assertEquals(kind, operation.kind)
            assertEquals(identity, operation.identity.value)
        }
        assertEquals(3, DaemonOperationProtocol.version)
    }

    @Test
    fun `removed preparation and signed envelopes cannot enter the canonical change protocol`() {
        for (raw in
            listOf(
                resource("obsolete-prepare.json"),
                resource("apply-obsolete-assertion.json"),
                resource("recover-obsolete-assertion.json"),
            )) assertThrows(SerializationException::class.java) {
            DaemonOperationProtocol.json.decodeFromString(DaemonChangeAction.serializer(), raw)
        }
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/keyless-change/$name")).readText().trim()
}
