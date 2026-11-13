package io.github.amichne.kast.workspace.intellij.read.hosted

import com.google.gson.JsonParser
import com.intellij.psi.PsiTreeChangeEvent
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HostedPsiDiagnosticListenerTest {
    @Test
    fun `fileless unloaded PSI remains observable without an inferred file or cause`() {
        val host = IdeReadHostLifetime.fromBoundary(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val published = mutableListOf<String>()
        val listener = HostedPsiDiagnosticListener(host, published::add)

        listener.observe(PsiTreeChangeEvent.PROP_UNLOADED_PSI, HostedPsiFileAttachment.ABSENT)

        val document = JsonParser.parseString(published.single()).asJsonObject
        assertEquals(setOf("host", "kind", "file", "event"), document.keySet())
        assertEquals("00000000-0000-0000-0000-000000000001", document["host"].asString)
        assertEquals("UNLOADED_PSI", document["kind"].asString)
        assertEquals("ABSENT", document["file"].asString)
        assertEquals("kast_hosted_psi_property", document["event"].asString)
    }

    @Test
    fun `each finite shape is sampled once without leaking unknown properties`() {
        val published = mutableListOf<String>()
        val listener = HostedPsiDiagnosticListener(IdeReadHostLifetime.fromBoundary(UUID.randomUUID()), published::add)

        repeat(10) {
            listener.observe(PsiTreeChangeEvent.PROP_UNLOADED_PSI, HostedPsiFileAttachment.ABSENT)
            listener.observe(PsiTreeChangeEvent.PROP_UNLOADED_PSI, HostedPsiFileAttachment.ATTACHED)
            listener.observe("private-property", HostedPsiFileAttachment.ABSENT)
            listener.observe(null, HostedPsiFileAttachment.ATTACHED)
        }
        listener.observe("another-private-property", HostedPsiFileAttachment.ABSENT)

        assertEquals(4, published.size)
        assertEquals(
            setOf("UNLOADED_PSI:ABSENT", "UNLOADED_PSI:ATTACHED", "OTHER:ABSENT", "OTHER:ATTACHED"),
            published
                .map { JsonParser.parseString(it).asJsonObject }
                .map { "${it["kind"].asString}:${it["file"].asString}" }
                .toSet(),
        )
        assertFalse(published.any { it.contains("private-property") })
    }
}
