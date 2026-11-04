package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.RelationRemediationDocument
import io.github.amichne.kast.protocol.wire.presentation.RelationOmissionCliDocument
import io.github.amichne.kast.protocol.wire.presentation.toCliDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationOmissionWireTest {
    @Test
    fun `CLI omission output preserves distinct budget provider failure and incomplete provider guidance`() {
        val cases =
            listOf(
                RelationLimitationDocument.TIME_LIMIT_REACHED to RelationRemediationDocument.INCREASE_READ_LIMIT,
                RelationLimitationDocument.CANDIDATE_LIMIT_REACHED to RelationRemediationDocument.INCREASE_READ_LIMIT,
                RelationLimitationDocument.RETENTION_LIMIT_REACHED to RelationRemediationDocument.INCREASE_READ_LIMIT,
                RelationLimitationDocument.PROVIDER_FAILURE to RelationRemediationDocument.RETRY_PROVIDER,
                RelationLimitationDocument.PARTITION_INVENTORY_UNAVAILABLE to
                    RelationRemediationDocument.REPAIR_PROVIDER,
            )
        for ((reason, remediation) in cases) {
            val wire =
                RelationOmissionWireDocument(
                    provider = RelationProviderDocument.INTELLIJ_REFERENCES_V2,
                    reason = reason,
                    measurement = RelationOmissionMeasurementWireDocument.UnmeasuredOnPage,
                    samples = RelationOmissionSamplesWireDocument.Complete(emptyList()),
                    remediation = remediation,
                )
            val admitted = wire.toContract() as WireDocumentConversion.Converted
            val encoded =
                Json.parseToJsonElement(
                        Json.encodeToString(RelationOmissionCliDocument.serializer(), admitted.value.toCliDocument())
                    )
                    .jsonObject
            assertEquals(reason.name, encoded.getValue("reason").jsonPrimitive.content)
            assertEquals(remediation.name, encoded.getValue("remediation").jsonPrimitive.content)
            assertEquals(
                "unmeasured_on_page",
                encoded.getValue("measurement").jsonObject.getValue("type").jsonPrimitive.content,
            )
            val incorrect =
                if (remediation == RelationRemediationDocument.INCREASE_READ_LIMIT)
                    RelationRemediationDocument.REPAIR_PROVIDER
                else RelationRemediationDocument.INCREASE_READ_LIMIT
            assertTrue(wire.copy(remediation = incorrect).toContract() is WireDocumentConversion.Rejected)
        }
    }

    @Test
    fun `wire explicitly separates observed page counts from unmeasured omissions`() {
        val observed =
            RelationOmissionWireDocument(
                provider = RelationProviderDocument.INTELLIJ_REFERENCES_V2,
                reason = RelationLimitationDocument.UNSUPPORTED_ITEM,
                measurement = RelationOmissionMeasurementWireDocument.ObservedOnPage(7),
                samples =
                    RelationOmissionSamplesWireDocument.Complete(
                        listOf(RelationOmissionLocationWireDocument("src/Catalog.kt", SourceRangeWireDocument(4, 8)))
                    ),
                remediation = RelationRemediationDocument.USE_SUPPORTED_DECLARATIONS,
            )
        assertEquals(
            javaClass.getResource("/relation-omission-observed.json")!!.readText().trim(),
            Json.encodeToString(RelationOmissionWireDocument.serializer(), observed),
        )
        assertTrue(observed.toContract() is WireDocumentConversion.Converted)
        val unmeasured =
            observed.copy(
                measurement = RelationOmissionMeasurementWireDocument.UnmeasuredOnPage,
                samples = RelationOmissionSamplesWireDocument.Complete(emptyList()),
            )
        assertEquals(
            javaClass.getResource("/relation-omission-unmeasured.json")!!.readText().trim(),
            Json.encodeToString(RelationOmissionWireDocument.serializer(), unmeasured),
        )
        assertTrue(unmeasured.toContract() is WireDocumentConversion.Converted)
        assertTrue(
            observed.copy(measurement = RelationOmissionMeasurementWireDocument.ObservedOnPage(-1)).toContract()
                is WireDocumentConversion.Rejected
        )
        assertTrue(
            observed
                .copy(
                    samples =
                        RelationOmissionSamplesWireDocument.Complete(List(4) { observed.samples.locations.single() })
                )
                .toContract() is WireDocumentConversion.Rejected
        )
        assertTrue(
            observed.copy(remediation = RelationRemediationDocument.INCREASE_READ_LIMIT).toContract()
                is WireDocumentConversion.Rejected
        )
    }

    @Test
    fun `encoded truncated samples retain proof and reject invalid or unknown states`() {
        val locations =
            listOf(4, 14, 24).map { start ->
                RelationOmissionLocationWireDocument("src/Catalog.kt", SourceRangeWireDocument(start, start + 4))
            }
        val document =
            RelationOmissionWireDocument(
                RelationProviderDocument.INTELLIJ_REFERENCES_V2,
                RelationLimitationDocument.UNSUPPORTED_ITEM,
                RelationOmissionMeasurementWireDocument.ObservedOnPage(4),
                RelationOmissionSamplesWireDocument.Truncated(locations),
                RelationRemediationDocument.USE_SUPPORTED_DECLARATIONS,
            )
        val encoded = Json.encodeToString(RelationOmissionWireDocument.serializer(), document)
        assertEquals(javaClass.getResource("/relation-omission-truncated.json")!!.readText().trim(), encoded)
        val admitted = document.toContract() as WireDocumentConversion.Converted
        assertEquals(
            encoded,
            Json.encodeToString(RelationOmissionWireDocument.serializer(), admitted.value.toWireDocument()),
        )
        assertEquals(
            encoded,
            Json.encodeToString(RelationOmissionCliDocument.serializer(), admitted.value.toCliDocument()),
        )
        assertTrue(
            document.copy(samples = RelationOmissionSamplesWireDocument.Truncated(locations.take(2))).toContract()
                is WireDocumentConversion.Rejected
        )
        assertTrue(
            document
                .copy(
                    samples = RelationOmissionSamplesWireDocument.Complete(listOf(locations.first(), locations.first()))
                )
                .toContract() is WireDocumentConversion.Rejected
        )
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString(RelationOmissionWireDocument.serializer(), encoded.replace("truncated", "unknown"))
        }
    }
}
