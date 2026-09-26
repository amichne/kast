package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryJoinModeDocument
import io.github.amichne.kast.protocol.contract.QueryJoinRightDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolBindingContractTest {
    @Test
    fun `earlier binding and inner join lower to one typed binding row output`() {
        val input =
            run(
                listOf(
                    PublicToolBind(name("saved")),
                    PublicToolJoin(
                        PublicToolInnerJoinMode(name("left"), name("right")),
                        PublicToolNamedBindingSource(name("saved")),
                    ),
                ),
                QueryOutputDocument.BindingRows,
            )
        val encoded = Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input)
        val request = encoded.jsonObject.getValue("request").jsonObject
        assertEquals("binding_rows", request.getValue("output").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "join",
            request.getValue("steps").jsonArray.last().jsonObject.getValue("type").jsonPrimitive.content,
        )
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, encoded) as Refinement.Refined
        val canonical = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(name("saved"), (canonical.steps.values.first() as QueryStepDocument.Bind).name)
        val join = canonical.steps.values.last() as QueryStepDocument.Join
        assertEquals(QueryJoinModeDocument.Inner(name("left"), name("right")), join.mode)
        assertEquals(QueryJoinRightDocument.Named(name("saved")), join.right)
        assertEquals(QueryOutputDocument.BindingRows, canonical.output)
    }

    @Test
    fun `retained right input and binding result read remain typed`() {
        val result =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined).value
        val joined =
            run(
                listOf(PublicToolJoin(PublicToolInnerJoinMode(name("l"), name("r")), PublicToolResultSource(result))),
                QueryOutputDocument.BindingRows,
            )
        val admitted = admit(joined) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(
            result,
            ((request.steps.values.single() as QueryStepDocument.Join).right
                    as io.github.amichne.kast.protocol.contract.QueryFromDocument.Result)
                .reference,
        )

        val read = PublicToolQuerySymbols(PublicToolReadResultAction(result, output = QueryOutputDocument.BindingRows))
        val readRequest =
            ((admit(read) as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
                as QueryRunRequest.ReadResult
        assertEquals(QueryOutputDocument.BindingRows, readRequest.output)
    }

    @Test
    fun `semi and anti join remain symbol stages after public lowering`() {
        val result =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined).value
        for ((publicMode, canonicalMode) in
            listOf(
                PublicToolSemiJoinMode to QueryJoinModeDocument.Semi,
                PublicToolAntiJoinMode to QueryJoinModeDocument.Anti,
            )) {
            val query =
                run(listOf(PublicToolJoin(publicMode, PublicToolResultSource(result)), PublicToolDistinctSymbols), null)
            val admitted = admit(query) as Refinement.Refined
            val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
            assertEquals(canonicalMode, (request.steps.values.first() as QueryStepDocument.Join).mode)
            assertEquals(QueryStepDocument.Distinct, request.steps.values.last())
        }
    }

    @Test
    fun `binding order output and join key violations reject at public admission`() {
        val inner =
            PublicToolJoin(PublicToolInnerJoinMode(name("l"), name("r")), PublicToolNamedBindingSource(name("saved")))
        val cases =
            listOf(
                run(listOf(inner), QueryOutputDocument.BindingRows),
                run(listOf(PublicToolBind(name("saved")), PublicToolBind(name("saved"))), null),
                run(listOf(PublicToolBind(name("saved")), inner), null),
                run(
                    listOf(PublicToolBind(name("saved")), inner, PublicToolDistinctSymbols),
                    QueryOutputDocument.BindingRows,
                ),
                run(
                    listOf(
                        PublicToolBind(name("saved")),
                        PublicToolJoin(
                            PublicToolInnerJoinMode(name("same"), name("same")),
                            PublicToolNamedBindingSource(name("saved")),
                        ),
                    ),
                    QueryOutputDocument.BindingRows,
                ),
                run(emptyList(), QueryOutputDocument.BindingRows),
            )
        cases.forEach { assertTrue(admit(it) is Refinement.Rejected) }

        val valid =
            Json.encodeToString(
                PublicToolQuerySymbols.serializer(),
                run(listOf(PublicToolBind(name("saved")), inner), QueryOutputDocument.BindingRows),
            )
        val unsupportedKey = valid.replace("\"right\":", "\"key\":\"name\",\"right\":")
        assertTrue(
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(unsupportedKey))
                is Refinement.Rejected
        )
        val malformedName = valid.replaceFirst("\"name\":\"saved\"", "\"name\":\"0saved\"")
        assertTrue(
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(malformedName))
                is Refinement.Rejected
        )
    }

    private fun run(steps: List<PublicToolStep>, output: QueryOutputDocument?): PublicToolQuerySymbols {
        val ref = (ProtocolText.parse("NON_ISSUED_SCHEMA_TEST_ONLY") as Refinement.Refined).value
        val refs = (BoundedProtocolList.create(listOf(ref)) as Refinement.Refined).value
        val stages = (BoundedProtocolList.create(steps) as Refinement.Refined).value
        return PublicToolQuerySymbols(PublicToolRunAction(PublicToolReferenceSource(refs), stages, output))
    }

    private fun admit(value: PublicToolQuerySymbols) =
        PublicToolContract.admit(
            PublicToolIdentity.QUERY_SYMBOLS,
            Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), value),
        )

    private fun name(raw: String) = (QueryBindingNameDocument.parse(raw) as Refinement.Refined).value
}
