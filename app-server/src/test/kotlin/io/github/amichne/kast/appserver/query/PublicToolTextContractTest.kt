package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolTextContractTest {
    @Test
    fun `indexed word source retains exact scope and declaration kind constraints`() {
        val input =
            PublicToolQuerySymbols(
                PublicToolRunAction(
                    PublicToolTextSource(
                        word = text("launchd"),
                        scope = PublicToolDirectoryScope(text("app-server"), false, bounded(listOf(text("main")))),
                        declarationKinds = bounded(listOf(PublicToolDeclarationKinds.FUNCTION)),
                    )
                )
            )
        val raw = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw)
        assertTrue(admitted is Refinement.Refined, admitted.toString())
        val query =
            ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
                as QueryRunRequest.Run
        val source = query.from as QueryFromDocument.TextWord
        assertEquals("launchd", source.word.value)
        assertEquals("app-server", source.scope.directory?.path?.value)
        assertEquals(QueryContainmentDocument.DIRECT, source.scope.directory?.containment)
        assertEquals(listOf("main"), source.scope.sourceSets.values.map { it.value })
        assertEquals(listOf(QueryDeclarationKindDocument.FUNCTION), source.declarationKinds.values)
    }

    @Test
    fun `indexed word admission rejects phrases literals regex and unknown fields`() {
        val examples = PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS).invalidExamples
        listOf(
                "wordPhrase",
                "wordLiteral",
                "wordRegex",
                "wordUnknownMode",
                "wordMissing",
                "wordTooLong",
                "wordEmptyKinds",
            )
            .forEach { name ->
                assertTrue(
                    PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, examples.getValue(name).value)
                        is Refinement.Rejected,
                    name,
                )
            }
    }

    @Test
    fun `indexed word source permits defaults without weakening the word grammar`() {
        val input = PublicToolQuerySymbols(PublicToolRunAction(PublicToolTextSource(text("bootstrap"))))
        val raw = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw) as Refinement.Refined
        val source =
            ((admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run).from
                as QueryFromDocument.TextWord
        assertEquals(listOf("main", "test"), source.scope.sourceSets.values.map { it.value })
        assertEquals(4, source.declarationKinds.values.size)
    }

    private fun text(raw: String) = (ProtocolText.parse(raw) as Refinement.Refined).value

    private fun <Value> bounded(values: List<Value>) = (BoundedProtocolList.create(values) as Refinement.Refined).value
}
