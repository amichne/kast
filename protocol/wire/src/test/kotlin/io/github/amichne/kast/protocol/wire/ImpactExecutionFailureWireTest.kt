package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactExecutionAccountingCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionBoundaryCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionLedgerCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionModelHistoryCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionPathCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionRepresentationCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionRowIdentityCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionSelectionCause
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.wire.presentation.QueryRejectionCliDocument
import io.github.amichne.kast.protocol.wire.presentation.toCliDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ImpactExecutionFailureWireTest {
    @Test
    fun `interpreter finite failures preserve independent wire and CLI shapes`() {
        val expected =
            Json.parseToJsonElement(javaClass.getResource("/impact-execution-rejections.expected.json")!!.readText())
                .jsonArray
        val cases =
            listOf(
                ImpactExecutionFailureDocument.Path(ImpactExecutionPathCause.TERMINAL_UNPROVEN),
                ImpactExecutionFailureDocument.Ledger(ImpactExecutionLedgerCause.MISSING_BRANCH),
                ImpactExecutionFailureDocument.Accounting(ImpactExecutionAccountingCause.DUPLICATE_PATH),
                ImpactExecutionFailureDocument.Representation(ImpactExecutionRepresentationCause.CALLABLE_MISMATCH),
                ImpactExecutionFailureDocument.Boundary(ImpactExecutionBoundaryCause.KIND_MISMATCH),
                ImpactExecutionFailureDocument.ModelHistory(ImpactExecutionModelHistoryCause.MISSING_APPLICATION),
                ImpactExecutionFailureDocument.Selection(ImpactExecutionSelectionCause.PRESENTATION_ONLY_ROWS),
                ImpactExecutionFailureDocument.RowIdentity(ImpactExecutionRowIdentityCause.CHANGED_RETAINED_ROWS),
                ImpactExecutionFailureDocument.PresentationOnly,
            )
        assertEquals(9, expected.size)
        for ((index, cause) in cases.withIndex()) {
            val rejection = QueryRunRejection.ImpactExecutionRejected(cause)
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
            val malformed = encoded.value.toString().replace("IMPACT_EXECUTION_REJECTED", "UNKNOWN_INTERPRETER_FAILURE")
            assertInstanceOf(
                WireDecoding.Rejected::class.java,
                CanonicalQuerySerializers.rejection.decode(Json.parseToJsonElement(malformed), WireValueRole.REJECTION),
            )
        }
        val malformed = expected[0].toString().replace("TERMINAL_UNPROVEN", "ASSUMED_TERMINAL")
        assertInstanceOf(
            WireDecoding.Rejected::class.java,
            CanonicalQuerySerializers.rejection.decode(Json.parseToJsonElement(malformed), WireValueRole.REJECTION),
        )
    }
}
