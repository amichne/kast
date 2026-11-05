package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceOperationAdmissionTest {
    @Test
    fun `source and retained authority cannot select lifecycle preparation`() {
        val examples = PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS).examples
        for (name in
            listOf(
                "readCallbackSource",
                "readSourceExactSymbol",
                "enclosingOccurrenceSource",
                "readRetained",
                "resumeQuery",
                "directCallers",
                "intersectRetained",
                "representationImpact",
            )) {
            val admitted =
                (PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, examples.getValue(name).value)
                        as Refinement.Refined)
                    .value
            assertEquals(
                WorkspaceOperationAdmission.EXISTING_AUTHORITY,
                DaemonOperationCall.PublicTool(admitted).workspaceAdmission(),
                name,
            )
        }
        for (name in listOf("runByName", "containingDeclaration", "scopedTextWord")) {
            val admitted =
                (PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, examples.getValue(name).value)
                        as Refinement.Refined)
                    .value
            assertEquals(
                WorkspaceOperationAdmission.PREPARE,
                DaemonOperationCall.PublicTool(admitted).workspaceAdmission(),
                name,
            )
        }
    }
}
