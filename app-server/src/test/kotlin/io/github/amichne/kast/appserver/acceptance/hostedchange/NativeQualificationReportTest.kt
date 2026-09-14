package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.provider.KastProviderQualification
import io.github.amichne.kast.appserver.provider.KastQualificationFailure
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class NativeQualificationReportTest {
    @Test
    fun `provider rejection reaches durable change report before startup exits`(@TempDir root: Path) {
        for (cause in KastQualificationFailure.entries) {
            val path = root.resolve("report.json")
            val evidence = NativeChangeEvidence(path)
            val rejected =
                assertThrows<NativeProviderQualificationRejected> {
                    KastProviderQualification.Rejected(cause).nativeQualified(evidence::providerQualification)
                }
            assertEquals(cause, rejected.failure)
            val report = Json.parseToJsonElement(Files.readString(path)).jsonObject
            assertEquals(setOf("schemaVersion", "metadata", "cases", "providerQualification"), report.keys)
            assertEquals("1", report.getValue("schemaVersion").jsonPrimitive.content)
            val observation = report.getValue("providerQualification").jsonObject
            assertEquals(setOf("outcome", "stage", "cause"), observation.keys)
            assertEquals("rejected", observation.getValue("outcome").jsonPrimitive.content)
            assertEquals("PROVIDER_QUALIFICATION", observation.getValue("stage").jsonPrimitive.content)
            assertEquals(cause.name, observation.getValue("cause").jsonPrimitive.content)
        }
    }

    @Test
    fun `admitted qualification survives later report writes`(@TempDir root: Path) {
        val path = root.resolve("report.json")
        val evidence = NativeChangeEvidence(path)
        evidence.providerQualification(NativeProviderQualificationObservation.Admitted())
        evidence.terminal(null)
        val observation =
            Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("providerQualification").jsonObject
        assertEquals(setOf("outcome", "stage"), observation.keys)
        assertEquals("admitted", observation.getValue("outcome").jsonPrimitive.content)
        assertEquals("PROVIDER_QUALIFICATION", observation.getValue("stage").jsonPrimitive.content)
    }
}
