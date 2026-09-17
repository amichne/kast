package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.cli.CliJsonDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InstallationActivationContractTest {
    @Test
    fun `activation variants encode exact discriminators and pending reasons with recovery`() {
        val factory = CliJsonDocument.generated(InstallationActivation.serializer())
        for ((activation, discriminator) in
            listOf(
                InstallationActivation.Planned to "planned",
                InstallationActivation.NotRequested to "not-requested",
                InstallationActivation.Ready to "ready",
            )) {
            val document = Json.parseToJsonElement(factory.create(activation).value).jsonObject
            assertEquals(setOf("type"), document.keys)
            assertEquals(discriminator, document.getValue("type").jsonPrimitive.content)
        }
        for (reason in InstallationActivationFailure.entries) {
            val document =
                Json.parseToJsonElement(factory.create(InstallationActivation.Pending(reason)).value).jsonObject
            assertEquals(setOf("type", "reason", "resume"), document.keys)
            assertEquals("pending", document.getValue("type").jsonPrimitive.content)
            assertEquals(reason.name, document.getValue("reason").jsonPrimitive.content)
            assertEquals("kast codex", document.getValue("resume").jsonPrimitive.content)
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<InstallationActivationFailure>("\"UNKNOWN\"")
        }
    }

    @Test
    fun `every child outcome retains completion or the exact finite process failure`() {
        val expected =
            listOf(
                InstallationChildOutcome.COMPLETED to InstallationActivation.Ready,
                InstallationChildOutcome.EXIT_REJECTED to
                    InstallationActivation.Pending(InstallationActivationFailure.EXIT_REJECTED),
                InstallationChildOutcome.DEADLINE_EXCEEDED to
                    InstallationActivation.Pending(InstallationActivationFailure.DEADLINE_EXCEEDED),
                InstallationChildOutcome.IO_REJECTED to
                    InstallationActivation.Pending(InstallationActivationFailure.IO_REJECTED),
                InstallationChildOutcome.INTERRUPTED to
                    InstallationActivation.Pending(InstallationActivationFailure.INTERRUPTED),
            )
        assertEquals(InstallationChildOutcome.entries.toSet(), expected.map { it.first }.toSet())
        for ((outcome, activation) in expected) assertEquals(activation, InstallationActivation.fromChild(outcome))
    }
}
