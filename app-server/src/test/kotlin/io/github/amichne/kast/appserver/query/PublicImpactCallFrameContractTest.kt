package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactCallTargetDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPredicateSubjectDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
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

class PublicImpactCallFrameContractTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `direct requested frame preserves intent and rejects an unknown target discriminator`() {
        val host = declaration()
        val frame =
            ImpactCallFrameDocument(
                host,
                ImpactInvocationReferenceDocument(range(20, 80), host),
                ImpactExecutionReferenceDocument(host),
            )
        val target =
            ImpactValueSiteReferenceDocument(
                host,
                range(30, 40),
                ImpactValueRoleDocument.LocalRead,
                callStack = bounded(listOf(frame)),
            )
        val input = input(target)
        val encoded = json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded).refined()
        val run = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val actual = (run.from as QueryFromDocument.Impact).investigation.requestedSites.values.single()
        assertEquals(listOf(frame), actual.callStack.values)
        assertEquals(ImpactCallTargetDocument.Direct, actual.callStack.values.single().target)
        val malformed =
            json.encodeToString(PublicToolQuerySymbols.serializer(), input).replace("DIRECT", "UNKNOWN_TARGET")
        assertEquals(
            Refinement.Rejected(PublicToolInputFailure.SchemaRejected),
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(malformed)),
        )
    }

    @Test
    fun `caller supplied predicates reject even when the complete proof document encodes`() {
        val host = declaration()
        val subject = ImpactPredicateSubjectDocument(host, range(30, 40), ImpactValueRoleDocument.LocalRead)
        val target =
            ImpactValueSiteReferenceDocument(
                host,
                range(30, 40),
                ImpactValueRoleDocument.LocalRead,
                predicates = bounded(listOf(ImpactValuePredicateDocument.NonNull(subject))),
            )
        val encoded = json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input(target))
        assertEquals(
            Refinement.Rejected(PublicToolInputFailure.SchemaRejected),
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded),
        )
    }

    private fun input(target: ImpactValueSiteReferenceDocument): PublicToolQuerySymbols {
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
            ImpactSemanticBasisDocument.Published(
                text("/workspace"),
                ImpactEvidenceRevisionDocument.parse(7).refined(),
            ),
            text("/workspace/File.kt"),
            range(0, 200),
            text("canonical-signature-sha256-v1|" + "a".repeat(64)),
        )

    private fun range(start: Int, end: Int) =
        ImpactSourceRangeDocument(ProtocolOffset.parse(start).refined(), ProtocolOffset.parse(end).refined())

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
