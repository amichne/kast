package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.cli.HostedRequestEffect
import io.github.amichne.kast.cli.PreparedCliRequest
import io.github.amichne.kast.cli.hostedSchemaBudgetGrant
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedReportAdmissionTest {
    @Test
    fun `host failures preserve valid reports but reject dimensionally impossible admitted evidence`() {
        val report = ExecutionBudgetReport.from(hostedSchemaBudgetGrant(ExecutionBudgetDocument()))
        val json = Json { encodeDefaults = true }
        val raw = json.encodeToString(ReportedFailure.serializer(), ReportedFailure(report))
        val root = CanonicalRoot(Path.of("/workspace"))
        val descriptor = ExistingIdeDescriptor(123, UUID.fromString("00000000-0000-0000-0000-000000000001"))
        for (kind in
            listOf(
                ExistingIdeReadOperation.QUERY_RUN,
                ExistingIdeReadOperation.SOURCE_READ,
                ExistingIdeReadOperation.RELATION_READ,
                ExistingIdeReadOperation.TRAVERSAL_RUN,
            )) {
            val operation =
                (ExistingIdeOperation.Read.admit(
                        PreparedCliRequest(
                            kind.canonical,
                            HostedRequestEffect.Operation(kind.canonical),
                            json.encodeToString(EmptyRequest.serializer(), EmptyRequest),
                        ) {
                            error("Hosted failure must not project semantic success")
                        }
                    ) as Refinement.Refined)
                    .value
            val admitted =
                assertInstanceOf(
                    ExistingIdeExchange.HostRejected::class.java,
                    ExistingIdeDocuments.response(raw.toByteArray(), root, operation, descriptor),
                )
            assertEquals(raw, admitted.document.value)
            // deadline_remaining is a known enum, but cannot justify a work or output clamp.
            val impossible = raw.replace("\"clamping\":[]", "\"clamping\":[\"deadline_remaining\"]")
            assertInstanceOf(
                ExistingIdeExchange.Rejected::class.java,
                ExistingIdeDocuments.response(impossible.toByteArray(), root, operation, descriptor),
            )
        }
    }

    @Serializable private data object EmptyRequest

    @Serializable
    private data class ReportedFailure(
        @SerialName("execution_budget") val executionBudget: ExecutionBudgetReport,
        val failure: String = "RESULT_TOO_LARGE",
        val type: String = "HOST_REJECTED",
    )
}
