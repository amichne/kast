package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.query.*
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicToolCommandTest {
    @Test
    fun `all public tools share CLI admission and canonical wire lowering`() {
        val examples =
            mapOf(
                PublicToolIdentity.SEARCH_CLASSES to """{"class_name":"Order","name_match":null,"scope":null}""",
                PublicToolIdentity.SEARCH_FUNCTIONS to """{"function_name":"order","name_match":null,"scope":null}""",
                PublicToolIdentity.SEARCH_DECLARATIONS to
                    """{"declaration_name":"order","name_match":null,"scope":null,"declaration_kinds":["property","type_alias"]}""",
                PublicToolIdentity.CHECK_DIAGNOSTICS to """{"relative_path":".","max_diagnostics":null}""",
                PublicToolIdentity.QUERY_SYMBOLS to
                    """{"source":{"type":"all_declarations","declaration_kinds":null,"scope":null},"steps":null,"return_fields":[]}""",
            )
        val graph =
            (CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created)
                .factory
        examples.forEach { (identity, raw) ->
            val expected =
                (PublicToolContract.admit(identity, Json.parseToJsonElement(raw)) as Refinement.Refined).value.canonical
            val parsing = graph.parse(listOf("tool", identity.toolName), CliRequestDocumentInput.Provided(raw))
            assertTrue(parsing is CliCommandParsing.Parsed, parsing.toString())
            val prepared = ((parsing as CliCommandParsing.Parsed).action as CliAction.Semantic).request
            val request = (WireRequestEnvelope.admit(prepared.document) as WireRequestAdmission.Admitted).request
            when (expected) {
                is PublicToolCanonical.Query ->
                    assertEquals(
                        WireDecoding.Decoded(expected.request),
                        CanonicalOperationWireBindings.queryRun.decodeRequest(request),
                    )
                is PublicToolCanonical.Diagnostics ->
                    assertEquals(
                        WireDecoding.Decoded(expected.request),
                        CanonicalOperationWireBindings.diagnosticCheck.decodeRequest(request),
                    )
            }
        }
        assertTrue(
            graph.parse(
                listOf("tool", "search_classes"),
                CliRequestDocumentInput.Provided(examples.getValue(PublicToolIdentity.SEARCH_FUNCTIONS)),
            ) is CliCommandParsing.Rejected
        )
    }
}
