package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicImpactReceiverKindContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `public receiver intent preserves both explicit kinds`() {
        for (kind in ImpactReceiverKindDocument.entries) {
            val input = input(kind)
            val encoded = json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded).value()
            val run = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
            val site = (run.from as QueryFromDocument.Impact).investigation.requestedSites.values.single()
            assertEquals(kind, (site.role as ImpactValueRoleDocument.Receiver).kind)
        }
    }

    @Test
    fun `public receiver intent rejects omitted and unknown kinds in the schema`() {
        val raw = json.encodeToString(PublicToolQuerySymbols.serializer(), input(ImpactReceiverKindDocument.DISPATCH))
        val malformed = listOf(raw.replace(",\"kind\":\"DISPATCH\"", ""), raw.replace("DISPATCH", "UNKNOWN_SLOT"))
        for (value in malformed) {
            assertEquals(
                Refinement.Rejected(PublicToolInputFailure.SchemaRejected),
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(value)),
            )
        }
    }

    private fun input(kind: ImpactReceiverKindDocument): PublicToolQuerySymbols {
        val host = declaration()
        val target =
            ImpactValueSiteReferenceDocument(
                host,
                range(30, 40),
                ImpactValueRoleDocument.Receiver(ImpactInvocationReferenceDocument(range(20, 80), host), kind),
            )
        val source =
            PublicToolImpactSource(
                seeds =
                    bounded(
                        listOf(
                            QueryImpactProducerDocument(text("UNISSUED_OWNER"), text("UNISSUED_CALLEE"), range(20, 35))
                        )
                    ),
                declarations = bounded(emptyList()),
                models = bounded(emptyList()),
                domain = PublicToolImpactWorkspaceDomain,
                flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                requestedSites = bounded(listOf(target)),
            )
        return PublicToolQuerySymbols(PublicToolRunAction(source, output = PublicToolValuePathsOutput))
    }

    private fun declaration() =
        ImpactDeclarationReferenceDocument(
            ImpactSemanticBasisDocument.Published(text("/workspace"), ImpactEvidenceRevisionDocument.parse(7).value()),
            text("/workspace/File.kt"),
            range(0, 200),
            text("canonical-signature-sha256-v1|" + "a".repeat(64)),
        )
}

private fun text(value: String) = ProtocolText.parse(value).value()

private fun range(start: Int, end: Int) =
    ImpactSourceRangeDocument(ProtocolOffset.parse(start).value(), ProtocolOffset.parse(end).value())

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
