package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CliBoundaryDocumentsTest {
    @Test
    fun `generated boundary documents preserve distinct rejection schemas`() {
        assertEquals(
            "{\"status\":\"rejected\",\"boundary\":\"protocol\"," + "\"reason\":\"response-decoding-rejected\"}",
            CliBoundaryDocuments.boundaryRejected(
                    CliBoundaryExitStatus.PROTOCOL,
                    "response-decoding-rejected",
                )
                .value,
        )
        assertEquals(
            "{\"status\":\"rejected\",\"boundary\":\"usage\"," +
                "\"reason\":\"arguments-rejected\",\"diagnostic\":\"invalid option\"}",
            CliBoundaryDocuments.usageRejected(
                    CliCommandFailure.ARGUMENTS_REJECTED,
                    textDocument("invalid option"),
                )
                .value,
        )
    }

    private fun textDocument(raw: String): CliTextDocument =
        when (val admission = CliTextDocument.admit(raw)) {
            is CliTextDocumentAdmission.Admitted -> admission.document
            is CliTextDocumentAdmission.Rejected -> error(admission.failure)
        }
}
