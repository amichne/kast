package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryJoinModeDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolBindingContractTest {
    @Test
    fun `file offset source lowers to containing declaration intent`() {
        val file = (ProtocolText.parse("src/main/kotlin/Subject.kt") as Refinement.Refined).value
        val query = PublicToolQuerySymbols(PublicToolRunAction(PublicToolLocationSource(file, 42), null))
        val admitted = admit(query) as Refinement.Refined
        val request = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val source = request.from as io.github.amichne.kast.protocol.contract.QueryFromDocument.Location
        assertEquals(file, source.file)
        assertEquals(42, source.offset.value)
        assertTrue(
            admit(PublicToolQuerySymbols(PublicToolRunAction(PublicToolLocationSource(file, -1), null)))
                is Refinement.Rejected
        )
        val traversal = (ProtocolText.parse("../outside.kt") as Refinement.Refined).value
        assertTrue(
            admit(PublicToolQuerySymbols(PublicToolRunAction(PublicToolLocationSource(traversal, 42), null)))
                is Refinement.Rejected
        )
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
    fun `public binding projection lowers to the canonical typed stage`() {
        val step = PublicToolProjectBinding(name("caller"))
        val admitted = admit(run(listOf(step, PublicToolDistinctSymbols), null))
        val request =
            (((admitted as Refinement.Refined).value.canonical) as PublicToolCanonical.Query).request
                as QueryRunRequest.Run
        assertEquals(QueryStepDocument.ProjectBinding(name("caller")), request.steps.values.first())
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
