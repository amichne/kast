package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicToolOutputDetailTest {
    @Test
    fun `every public tool admits compact by default and explicit verbose without changing execution`() {
        val target = text("exact:v5:AAAAAAAAAAAAAAAAAAAAAQ")
        val cases =
            listOf(
                PublicToolIdentity.QUERY_SYMBOLS to PublicToolQuerySymbols(PublicToolRunAction(PublicToolAllSource())),
                PublicToolIdentity.CHECK_DIAGNOSTICS to PublicToolCheckDiagnostics(text(".")),
                PublicToolIdentity.ADD_DECLARATION to PublicToolAddDeclaration(target, text("fun added() = Unit")),
                PublicToolIdentity.REPLACE_BODY to PublicToolReplaceBody(target, text("{ return Unit }")),
            )
        cases.forEach { (identity, compact) ->
            val verbose =
                when (compact) {
                    is PublicToolQuerySymbols -> compact.copy(verbose = true)
                    is PublicToolCheckDiagnostics -> compact.copy(verbose = true)
                    is PublicToolAddDeclaration -> compact.copy(verbose = true)
                    is PublicToolReplaceBody -> compact.copy(verbose = true)
                }
            val admittedCompact = admit(identity, compact)
            val admittedVerbose = admit(identity, verbose)
            assertEquals(ToolOutputDetail.COMPACT, admittedCompact.outputDetail)
            assertEquals(ToolOutputDetail.VERBOSE, admittedVerbose.outputDetail)
            assertEquals(admittedCompact.canonical, admittedVerbose.canonical)
            val transported = PublicToolContract.admit(identity, PublicToolContract.encode(admittedVerbose))
            assertEquals(ToolOutputDetail.VERBOSE, (transported as Refinement.Refined).value.outputDetail)
        }
    }

    private fun admit(identity: PublicToolIdentity, value: PublicToolDocument): AdmittedPublicTool =
        (PublicToolContract.admit(identity, encodePublicTool(value, Json)) as Refinement.Refined).value

    private fun text(value: String): ProtocolText = (ProtocolText.parse(value) as Refinement.Refined).value
}
