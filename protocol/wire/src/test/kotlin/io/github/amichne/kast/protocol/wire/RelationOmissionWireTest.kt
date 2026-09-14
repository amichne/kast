package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.RelationRemediationDocument
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
                samples = listOf(RelationOmissionLocationWireDocument("src/Catalog.kt", SourceRangeWireDocument(4, 8))),
                remediation = RelationRemediationDocument.USE_SUPPORTED_DECLARATIONS,
            )
        assertEquals(
            javaClass.getResource("/relation-omission-observed.json")!!.readText().trim(),
            Json.encodeToString(RelationOmissionWireDocument.serializer(), observed),
        )
        assertTrue(observed.toContract() is WireDocumentConversion.Converted)
        val unmeasured =
            observed.copy(measurement = RelationOmissionMeasurementWireDocument.UnmeasuredOnPage, samples = emptyList())
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
            observed.copy(samples = List(4) { observed.samples.single() }).toContract()
                is WireDocumentConversion.Rejected
        )
        assertTrue(
            observed.copy(remediation = RelationRemediationDocument.INCREASE_READ_LIMIT).toContract()
                is WireDocumentConversion.Rejected
        )
    }
}
