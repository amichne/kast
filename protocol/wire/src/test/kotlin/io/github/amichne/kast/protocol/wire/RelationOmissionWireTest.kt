package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.RelationRemediationDocument
import io.github.amichne.kast.protocol.wire.presentation.RelationOmissionCliDocument
import io.github.amichne.kast.protocol.wire.presentation.toCliDocument
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationOmissionWireTest {
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
