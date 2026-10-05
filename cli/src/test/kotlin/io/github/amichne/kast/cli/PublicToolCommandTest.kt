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
    fun `public read source command uses exact canonical candidate anchor`() {
        val raw =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .examples
                .getValue("readCallbackSource")
                .value
                .toString()
        val graph =
            (CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created)
                .factory
        val parsing =
            graph.parse(listOf("tool", "query_symbols"), CliRequestDocumentInput.Provided(raw))
                as CliCommandParsing.Parsed
        val prepared = (parsing.action as CliAction.Semantic).request
        val request = (WireRequestEnvelope.admit(prepared.document) as WireRequestAdmission.Admitted).request
        val decoded = CanonicalOperationWireBindings.sourceRead.decodeRequest(request) as WireDecoding.Decoded
        val anchor = decoded.value.anchor as io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument.Candidate
        assertEquals("candidate:v5:AAAAAAAAAAAAAAAAAAAAAA", anchor.selector.value)
    }

    @Test
    fun `all public tools share CLI admission and canonical wire lowering`() {
        val examples =
            mapOf(
                PublicToolIdentity.CHECK_DIAGNOSTICS to Json.encodeToString(DiagnosticFixture(".", null)),
                PublicToolIdentity.QUERY_SYMBOLS to
                    PublicQueryInputFixture.search(
                        "order",
                        kinds = listOf("PROPERTY", "TYPE_ALIAS"),
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
                is PublicToolCanonical.Source ->
                    assertEquals(
                        WireDecoding.Decoded(expected.request),
                        CanonicalOperationWireBindings.sourceRead.decodeRequest(request),
                    )
                is PublicToolCanonical.Diagnostics ->
                    assertEquals(
                        WireDecoding.Decoded(expected.request),
                        CanonicalOperationWireBindings.diagnosticCheck.decodeRequest(request),
                    )
                is PublicToolCanonical.Change -> error("Approval-aware mutation has no generic CLI route")
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
    @SerialName("relativePath") val path: String,
    @SerialName("maxDiagnostics") val maximum: Int?,
)
