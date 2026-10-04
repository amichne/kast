package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.ImpactPresentationBudgetCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationCollectionCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationCountCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactPresentationFingerprintCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationModelCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationOffsetCause
import io.github.amichne.kast.protocol.contract.ImpactPresentationTextCause
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.wire.presentation.QueryRejectionCliDocument
import io.github.amichne.kast.protocol.wire.presentation.toCliDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ImpactPresentationRejectionWireTest {
    @Test
    fun `every exact impact failure encodes identically in wire and CLI and rejects unknown causes`() {
        val expected =
            Json.parseToJsonElement(javaClass.getResource("/impact-presentation-rejections.expected.json")!!.readText())
                .jsonArray
        val cases =
            listOf(
                ImpactPresentationFailureDocument.Text(ImpactPresentationTextCause.BLANK),
                ImpactPresentationFailureDocument.Text(ImpactPresentationTextCause.TOO_LONG),
                ImpactPresentationFailureDocument.Offset(ImpactPresentationOffsetCause.NEGATIVE),
                ImpactPresentationFailureDocument.Model(ImpactPresentationModelCause.INVALID_IDENTIFIER),
                ImpactPresentationFailureDocument.Model(ImpactPresentationModelCause.NOT_POSITIVE),
                ImpactPresentationFailureDocument.Model(ImpactPresentationModelCause.UNSUPPORTED_FORMAT),
                ImpactPresentationFailureDocument.Collection(ImpactPresentationCollectionCause.TOO_LARGE),
                ImpactPresentationFailureDocument.DomainFingerprint(
                    ImpactPresentationFingerprintCause.INVALID_FINGERPRINT
                ),
                ImpactPresentationFailureDocument.Count(ImpactPresentationCountCause.NEGATIVE),
                ImpactPresentationFailureDocument.Budget(ImpactPresentationBudgetCause.NOT_POSITIVE),
                ImpactPresentationFailureDocument.DomainProjectionRejected,
                ImpactPresentationFailureDocument.Accounting(ImpactAccountingFailure.PAGE_COUNT_MISMATCH),
            )
        assertEquals(cases.size, expected.size)
        for ((index, cause) in cases.withIndex()) {
            val rejection = QueryRunRejection.ImpactPresentationRejected(cause)
            val encoded =
                CanonicalQuerySerializers.rejection.encode(rejection, WireValueRole.REJECTION)
                    as WireValueEncoding.Encoded
            assertEquals(expected[index], encoded.value)
            assertEquals(
                expected[index],
                Json.encodeToJsonElement(QueryRejectionCliDocument.serializer(), rejection.toCliDocument()),
            )
            assertEquals(
                rejection,
                (CanonicalQuerySerializers.rejection.decode(encoded.value, WireValueRole.REJECTION)
                        as WireDecoding.Decoded)
                    .value,
            )
            val invalid = encoded.value.toString().replace("IMPACT_PRESENTATION_REJECTED", "UNKNOWN_IMPACT_FAILURE")
            assertInstanceOf(
                WireDecoding.Rejected::class.java,
                CanonicalQuerySerializers.rejection.decode(Json.parseToJsonElement(invalid), WireValueRole.REJECTION),
            )
        }
        val unknownCause = expected.first().toString().replace("BLANK", "UNKNOWN_CAUSE")
        assertInstanceOf(
            WireDecoding.Rejected::class.java,
            CanonicalQuerySerializers.rejection.decode(Json.parseToJsonElement(unknownCause), WireValueRole.REJECTION),
        )
    }
}
