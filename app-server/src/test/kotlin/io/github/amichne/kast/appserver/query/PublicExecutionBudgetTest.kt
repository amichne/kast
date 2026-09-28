package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PublicExecutionBudgetTest {
    private val json = Json { encodeDefaults = true }
    private val name = (ProtocolText.parse("Service") as Refinement.Refined).value
    private val budget =
        ExecutionBudgetDocument(
            maxElapsedMillis = (ElapsedTimeLimitMillis.parse(3000) as Refinement.Refined).value,
            maxWorkUnits = (WorkUnitLimit.parse(200000) as Refinement.Refined).value,
            maxResults = (ResultLimit.parse(3) as Refinement.Refined).value,
            maxReturnedBytes = (ReturnedByteLimit.parse(40000) as Refinement.Refined).value,
        )
    private val publicBudget = PublicToolExecutionBudget(3000, 200000, 3, 40000)

    @Test
    fun `query facade retains all four requested allowances`() {
        val refs = (BoundedProtocolList.create(listOf(name)) as Refinement.Refined).value
        val cases =
            listOf(
                PublicToolIdentity.QUERY_SYMBOLS to
                    json.encodeToJsonElement(
                        PublicToolQuerySymbols(
                            PublicToolRunAction(
                                PublicToolReferenceSource(refs),
                                null,
                                null,
                                executionBudget = publicBudget,
                            )
                        )
                    )
            )
        for ((identity, document) in cases) {
            val admitted = PublicToolContract.admit(identity, document) as Refinement.Refined
            assertEquals(budget, (admitted.value.canonical as PublicToolCanonical.Query).request.executionBudget)
        }
    }

    @Test
    fun `resume and read result retain caller allowances without a run plan`() {
        val continuation =
            (QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000000")
                    as Refinement.Refined)
                .value
        val result =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000000") as Refinement.Refined).value
        val actions =
            listOf(
                PublicToolResumeAction(continuation, publicBudget),
                PublicToolReadResultAction(result, null, null, publicBudget),
            )
        actions.forEach { action ->
            val admitted =
                PublicToolContract.admit(
                    PublicToolIdentity.QUERY_SYMBOLS,
                    json.encodeToJsonElement(PublicToolQuerySymbols(action)),
                ) as Refinement.Refined
            assertEquals(budget, (admitted.value.canonical as PublicToolCanonical.Query).request.executionBudget)
        }
    }

    @Test
    fun `quoted and nonpositive caller allowances fail public admission`() {
        val refs = (BoundedProtocolList.create(listOf(name)) as Refinement.Refined).value
        val invalid =
            listOf(
                json.encodeToJsonElement(
                    InvalidQuery(
                        InvalidRunQuery(
                            source = PublicToolReferenceSource(refs),
                            executionBudget = InvalidBudget("100"),
                        )
                    )
                ),
                json.encodeToJsonElement(
                    InvalidQuery(
                        InvalidRunQuery(source = PublicToolReferenceSource(refs), executionBudget = InvalidBudget(0))
                    )
                ),
                json.encodeToJsonElement(
                    InvalidQuery(
                        InvalidRunQuery(source = PublicToolReferenceSource(refs), executionBudget = InvalidBudget(-1))
                    )
                ),
                json.encodeToJsonElement(
                    InvalidQuery(
                        InvalidRunQuery(source = PublicToolReferenceSource(refs), executionBudget = InvalidBudget(1.5))
                    )
                ),
            )
        for (document in invalid) assertInstanceOf(
            Refinement.Rejected::class.java,
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, document),
        )
    }

    @Test
    fun `query facade rejects every malformed budget dimension before canonical lowering`() {
        val refs = (BoundedProtocolList.create(listOf(name)) as Refinement.Refined).value
        val inputs =
            listOf(
                PublicToolIdentity.QUERY_SYMBOLS to
                    json.encodeToJsonElement(
                        PublicToolQuerySymbols(
                            PublicToolRunAction(
                                PublicToolReferenceSource(refs),
                                null,
                                null,
                                executionBudget = publicBudget,
                            )
                        )
                    )
            )
        val encodedBudget = json.encodeToString(PublicToolExecutionBudget.serializer(), publicBudget)
        for ((identity, input) in inputs) {
            assertInstanceOf(Refinement.Refined::class.java, PublicToolContract.admit(identity, input))
            val oneByte = budget.copy(maxReturnedBytes = (ReturnedByteLimit.parse(1) as Refinement.Refined).value)
            val small =
                Json.parseToJsonElement(
                    input
                        .toString()
                        .replace(encodedBudget, json.encodeToString(publicBudget.copy(maxReturnedBytes = 1)))
                )
            val lowered = PublicToolContract.admit(identity, small) as Refinement.Refined
            assertEquals(oneByte, (lowered.value.canonical as PublicToolCanonical.Query).request.executionBudget)
            assertMalformedBudgetDimensions(identity, input, encodedBudget)
            val unsupported =
                Json.parseToJsonElement(
                    input.toString().replace(encodedBudget, Json.encodeToString(UnsupportedPublicBudget(1)))
                )
            assertInstanceOf(Refinement.Rejected::class.java, PublicToolContract.admit(identity, unsupported))
        }
    }

    private fun assertMalformedBudgetDimensions(
        identity: PublicToolIdentity,
        input: JsonElement,
        encodedBudget: String,
    ) {
        for (invalid in listOf("0", "-1", "9223372036854775808", "1.5", "\"10\"")) {
            val scalar = Json.parseToJsonElement(invalid)
            for (invalidBudget in
                listOf(
                    InvalidPublicBudget(elapsed = scalar),
                    InvalidPublicBudget(work = scalar),
                    InvalidPublicBudget(results = scalar),
                    InvalidPublicBudget(bytes = scalar),
                )) {
                val malformed =
                    Json.parseToJsonElement(input.toString().replace(encodedBudget, Json.encodeToString(invalidBudget)))
                assertInstanceOf(Refinement.Rejected::class.java, PublicToolContract.admit(identity, malformed))
            }
        }
    }
}

@Serializable private data class InvalidQuery<T>(val request: InvalidRunQuery<T>)

@Serializable
private data class InvalidRunQuery<T>(
    val type: String = "RUN",
    val source: PublicToolSource,
    val executionBudget: InvalidBudget<T>,
    val steps: String? = null,
    val output: QueryOutputDocument? = null,
)

@Serializable private data class InvalidBudget<T>(val maxWorkUnits: T)

/** The malformed scalar values exercise the public schema and typed numeric decoder. */
@Serializable
private data class InvalidPublicBudget(
    @SerialName("maxElapsedMs") val elapsed: JsonElement? = null,
    @SerialName("maxWorkUnits") val work: JsonElement? = null,
    @SerialName("maxResults") val results: JsonElement? = null,
    @SerialName("maxReturnedBytes") val bytes: JsonElement? = null,
)

@Serializable private data class UnsupportedPublicBudget(val unsupported: Int)
