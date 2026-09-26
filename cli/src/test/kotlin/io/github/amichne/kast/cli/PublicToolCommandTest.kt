package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.query.*
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.*
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicToolCommandTest {
    @Test
    fun `all public tools share CLI admission and canonical wire lowering`() {
        val examples =
            mapOf(
                PublicToolIdentity.CHECK_DIAGNOSTICS to Json.encodeToString(DiagnosticFixture(".", null)),
                PublicToolIdentity.QUERY_SYMBOLS to
                    PublicQueryInputFixture.search(
                        "order",
                        kinds = listOf("property", "type_alias"),
                        fields = emptyList(),
                    ),
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
                CliRequestDocumentInput.Provided(examples.getValue(PublicToolIdentity.QUERY_SYMBOLS)),
            ) is CliCommandParsing.Rejected
        )
    }
}

@Serializable
private data class DiagnosticFixture(
    @SerialName("relative_path") val path: String,
    @SerialName("max_diagnostics") val maximum: Int?,
)
