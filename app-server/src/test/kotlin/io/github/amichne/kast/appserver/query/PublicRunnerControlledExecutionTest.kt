package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.requestedExecutionBudget
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PublicRunnerControlledExecutionTest {
    @Test
    fun `a new run admits and preserves an explicit runner controlled execution mode`() {
        val reference = (ProtocolText.parse("exact:v2:opaque") as Refinement.Refined).value
        val references = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val input =
            Json.encodeToJsonElement(
                RunnerControlledEnvelope(RunnerControlledRun(PublicToolReferenceSource(references)))
            )
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input)
        assertInstanceOf(Refinement.Refined::class.java, admitted)
        val run = ((admitted as Refinement.Refined).value.canonical as PublicToolCanonical.Query).request
        val canonical = Json.encodeToJsonElement(QueryRunRequest.serializer(), run).jsonObject
        assertEquals("RUNNER_CONTROLLED", canonical.getValue("execution_mode").jsonPrimitive.content)
        assertInstanceOf(
            RequestedExecutionBudget.RunnerControlled::class.java,
            (run as QueryRunRequest.Run).requestedExecutionBudget(),
        )
    }

    @Test
    fun `runner controlled mode accepts output and timeout controls but rejects work limits`() {
        val reference = (ProtocolText.parse("exact:v2:opaque") as Refinement.Refined).value
        val references = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val run =
            PublicToolRunAction(
                source = PublicToolReferenceSource(references),
                terminalStep = PublicToolEmit(),
                executionMode = QueryExecutionModeDocument.RUNNER_CONTROLLED,
                executionBudget = PublicToolExecutionBudget(maxResults = 2, maxReturnedBytes = 2000),
            )
        assertInstanceOf(
            Refinement.Refined::class.java,
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols(run)),
            ),
        )
        val timed =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(
                    PublicToolQuerySymbols(run.copy(executionBudget = PublicToolExecutionBudget(maxElapsedMs = 60001)))
                ),
            ) as Refinement.Refined
        val timedRun = (timed.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        val budget = timedRun.requestedExecutionBudget() as RequestedExecutionBudget.RunnerControlled
        assertEquals(60001L, budget.timeout?.value)
        assertInstanceOf(
            Refinement.Rejected::class.java,
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(
                    PublicToolQuerySymbols(run.copy(executionBudget = PublicToolExecutionBudget(maxWorkUnits = 1)))
                ),
            ),
        )
    }

    @Test
    fun `omitted or null execution mode has no implicit semantic quotas`() {
        val reference = (ProtocolText.parse("exact:v2:opaque") as Refinement.Refined).value
        val references = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val source = PublicToolReferenceSource(references)
        for (mode in listOf(null, QueryExecutionModeDocument.RUNNER_CONTROLLED)) {
            val input =
                Json.encodeToJsonElement(
                    PublicToolQuerySymbols(
                        PublicToolRunAction(source, terminalStep = PublicToolEmit(), executionMode = mode)
                    )
                )
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input) as Refinement.Refined
            val run = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
            assertEquals(QueryExecutionModeDocument.RUNNER_CONTROLLED, run.executionMode)
            assertInstanceOf(RequestedExecutionBudget.RunnerControlled::class.java, run.requestedExecutionBudget())
        }
        val explicitNull = Json.encodeToJsonElement(ModeInput(ExplicitNullableModeRun(source)))
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, explicitNull) as Refinement.Refined
        val run = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(QueryExecutionModeDocument.RUNNER_CONTROLLED, run.executionMode)
    }

    @Test
    fun `unknown or nonscalar execution modes reject before canonical admission`() {
        val reference = (ProtocolText.parse("exact:v2:opaque") as Refinement.Refined).value
        val references = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val source = PublicToolReferenceSource(references)
        for (mode in
            listOf(
                JsonPrimitive("UNLIMITED"),
                JsonPrimitive(""),
                JsonPrimitive(1),
                JsonPrimitive(false),
                Json.encodeToJsonElement(emptyList<String>()),
            )) {
            assertInstanceOf(
                Refinement.Rejected::class.java,
                PublicToolContract.admit(
                    PublicToolIdentity.QUERY_SYMBOLS,
                    Json.encodeToJsonElement(ModeInput(InvalidModeRun(source, mode))),
                ),
            )
        }
    }

    @Test
    fun `runner mode cannot change output resume into semantic execution`() {
        val continuation =
            (QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000000")
                    as Refinement.Refined)
                .value
        assertInstanceOf(
            Refinement.Refined::class.java,
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(PublicToolQuerySymbols(PublicToolResumeAction(continuation))),
            ),
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                Json.encodeToJsonElement(ModeInput(InvalidModeResume(continuation))),
            ),
        )
    }
}

@Serializable private data class ModeInput<Action>(val request: Action)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class ExplicitNullableModeRun(
    val source: PublicToolSource,
    @EncodeDefault val terminalStep: PublicToolTerminalStep = PublicToolEmit(),
    @EncodeDefault val executionMode: QueryExecutionModeDocument? = null,
    @EncodeDefault val type: String = "RUN",
)

/** Deliberately invalid mode scalars exercise the authored enum and primitive-type restrictions. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class InvalidModeRun(
    val source: PublicToolSource,
    val executionMode: JsonElement,
    @EncodeDefault val terminalStep: PublicToolTerminalStep = PublicToolEmit(),
    @EncodeDefault val type: String = "RUN",
)

/** Deliberately incompatible action tests that output presentation cannot select semantic execution. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class InvalidModeResume(
    val continuation: QueryExecutionContinuation.Output,
    @EncodeDefault val executionMode: QueryExecutionModeDocument = QueryExecutionModeDocument.RUNNER_CONTROLLED,
    @EncodeDefault val type: String = "RESUME",
)

@Serializable private data class RunnerControlledEnvelope(val request: RunnerControlledRun)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class RunnerControlledRun(
    val source: PublicToolSource,
    @EncodeDefault val terminalStep: PublicToolTerminalStep = PublicToolEmit(),
    @EncodeDefault val type: String = "RUN",
    @EncodeDefault val executionMode: ProposedExecutionMode = ProposedExecutionMode.RUNNER_CONTROLLED,
)

@Serializable
private enum class ProposedExecutionMode {
    RUNNER_CONTROLLED
}
