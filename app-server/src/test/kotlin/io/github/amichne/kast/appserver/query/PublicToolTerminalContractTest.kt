@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** Independent ingress fixtures: these do not obtain expectations from the schema or lowering. */
class PublicToolTerminalContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `one explicit terminal lowers after all nonterminal steps`() {
        val steps = listOf(PublicToolDistinctSymbols, PublicToolDistinctSymbols)
        for (terminal in listOf(TerminalFixture("EMIT"), TerminalFixture("TRACE"))) {
            val run = admit(TerminalRunFixture(terminal, steps)).run()
            val prefix = listOf(QueryStepDocument.Distinct, QueryStepDocument.Distinct)
            assertEquals(
                if (terminal.type == "TRACE") prefix + QueryStepDocument.Trace(QueryExpansionScopeDocument.Workspace)
                else prefix,
                run.steps.values,
            )
            assertEquals(
                QueryOutputDocument.Symbols(
                    bounded(listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION))
                ),
                run.output,
            )
        }
    }

    @Test
    fun `omitted null and empty symbol output keep their distinct defaults`() {
        for (type in listOf("EMIT", "TRACE")) {
            for (encoder in listOf(Json.Default, json)) {
                val raw = encoder.encodeToJsonElement(TerminalEnvelope(TerminalRunFixture(TerminalFixture(type))))
                val run = admitted(raw).run()
                assertEquals(
                    listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION),
                    (run.output as QueryOutputDocument.Symbols).fields.values,
                )
            }
            val empty = PublicToolSymbolsOutput(bounded(emptyList()))
            assertEquals(
                emptyList<QuerySymbolFieldDocument>(),
                (admit(TerminalRunFixture(TerminalFixture(type, empty))).run().output as QueryOutputDocument.Symbols)
                    .fields
                    .values,
            )
        }
    }

    @Test
    fun `emit accepts every established output and trace only symbols`() {
        val outputs =
            listOf(
                PublicToolSymbolsOutput(bounded(listOf(PublicToolFields.SIGNATURE))),
                PublicToolOccurrencesOutput,
                PublicToolTraversalRecordsOutput,
                PublicToolBindingRowsOutput,
                PublicToolValuePathsOutput,
                PublicToolImpactWitnessOutput(ImpactWitnessSectionDocument.FINDINGS),
            )
        val expected =
            listOf(
                QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.SIGNATURE))),
                QueryOutputDocument.Occurrences,
                QueryOutputDocument.TraversalRecords,
                QueryOutputDocument.BindingRows,
                QueryOutputDocument.ValuePaths,
                QueryOutputDocument.ImpactWitness(ImpactWitnessSectionDocument.FINDINGS),
            )
        outputs.zip(expected).forEach { (output, projection) ->
            val source =
                if (output is PublicToolImpactWitnessOutput)
                    PublicToolImpactSource(
                        seeds =
                            bounded(
                                listOf(
                                    QueryImpactProducerDocument(
                                        text("ENCLOSING_SCHEMA_ONLY"),
                                        text("CALLABLE_SCHEMA_ONLY"),
                                        ImpactSourceRangeDocument(offset(20), offset(35)),
                                    )
                                )
                            ),
                        declarations = bounded(emptyList()),
                        models = bounded(emptyList()),
                        domain = PublicToolImpactWorkspaceDomain,
                        flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                    )
                else PublicToolAllSource()
            assertEquals(
                projection,
                admit(TerminalRunFixture(TerminalFixture("EMIT", output), source = source)).run().output,
            )
            if (output !is PublicToolSymbolsOutput) {
                rejected(
                    Json.encodeToJsonElement(TerminalEnvelope(TerminalRunFixture(TerminalFixture("TRACE", output))))
                )
            }
        }
    }

    @Test
    fun `canonical allowance includes the trace suffix without executing semantic operations`() {
        for ((type, count) in listOf("EMIT" to 1000, "TRACE" to 999)) {
            val run = admit(TerminalRunFixture(TerminalFixture(type), List(count) { PublicToolDistinctSymbols })).run()
            assertEquals(1000, run.steps.values.size)
            assertEquals(type == "TRACE", run.steps.values.last() is QueryStepDocument.Trace)
            val invalid =
                Json.encodeToJsonElement(
                    TerminalEnvelope(
                        TerminalRunFixture(TerminalFixture(type), List(count + 1) { PublicToolDistinctSymbols })
                    )
                )
            val failure =
                assertInstanceOf(
                        Refinement.Rejected::class.java,
                        PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, invalid),
                    )
                    .failure
            assertEquals(
                if (type == "EMIT") PublicToolInputFailure.SchemaRejected
                else
                    PublicToolInputFailure.Parameter(
                        PublicToolParameter.TERMINAL_STEP,
                        PublicToolRule.CANONICAL_STEP_LIMIT,
                    ),
                failure,
            )
        }
    }

    @Test
    fun `terminal selection is required nonnullable tagged and closed`() {
        terminalRejectionFixtures().forEach(::rejected)
    }

    @Test
    fun `generated serializers retain both terminal and nested output discriminators`() {
        val empty = PublicToolSymbolsOutput(bounded(emptyList()))
        val terminals =
            listOf(PublicToolEmit(), PublicToolTrace(), PublicToolTrace(PublicToolImpactWorkspaceDomain, empty))
        val expected =
            listOf(
                Json.encodeToJsonElement(EmitShape("EMIT")),
                Json.encodeToJsonElement(TraceEncodedShape("TRACE")),
                Json.encodeToJsonElement(
                    TraceEncodedShape(
                        "TRACE",
                        PublicToolImpactWorkspaceDomain,
                        SymbolOutputShape("SYMBOLS", emptyList()),
                    )
                ),
            )
        terminals.zip(expected).forEach { (terminal, shape) ->
            assertEquals(shape, Json.encodeToJsonElement(PublicToolTerminalStep.serializer(), terminal))
            val input =
                PublicToolQuerySymbols(
                    PublicToolRunAction(
                        PublicToolLocationSource(text("src/Order.kt"), 17),
                        terminalStep = terminal,
                    )
                )
            val admitted = admitted(Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), input))
            val encoded = PublicToolContract.encode(admitted).jsonObject
            val run = encoded.getValue("request").jsonObject
            assertEquals(
                setOf("type", "source", "steps", "terminalStep", "retention", "executionBudget", "executionMode"),
                run.keys,
            )
            assertEquals(
                json.encodeToJsonElement(PublicToolTerminalStep.serializer(), terminal),
                run.getValue("terminalStep"),
            )
            assertEquals(JsonNull, run.getValue("steps"))
            assertEquals(JsonNull, run.getValue("retention"))
            assertEquals(JsonNull, run.getValue("executionBudget"))
            assertEquals(JsonNull, run.getValue("executionMode"))
        }
        assertEquals(
            json.encodeToJsonElement(TraceEncodedShape("TRACE")),
            json.encodeToJsonElement(PublicToolTerminalStep.serializer(), PublicToolTrace()),
        )
        assertEquals(
            json.encodeToJsonElement(EmitShape("EMIT")),
            json.encodeToJsonElement(PublicToolTerminalStep.serializer(), PublicToolEmit()),
        )
    }

    private inline fun <reified T> admit(run: T): AdmittedPublicTool =
        admitted(Json.encodeToJsonElement(TerminalEnvelope(run)))

    private fun admitted(raw: JsonElement): AdmittedPublicTool =
        when (val result = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> fail("Expected admission: $result; $raw")
        }

    private fun rejected(raw: JsonElement) {
        assertInstanceOf(
            Refinement.Rejected::class.java,
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw),
            raw.toString(),
        )
    }

    private fun AdmittedPublicTool.run() = (canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run

    private fun <T> bounded(values: List<T>) = (BoundedProtocolList.create(values) as Refinement.Refined).value

    private fun text(value: String) = (ProtocolText.parse(value) as Refinement.Refined).value

    private fun offset(value: Int) = (ProtocolOffset.parse(value) as Refinement.Refined).value
}

/** Shared rejected syntax lets the broker prove the same cases have no dispatch effect. */
internal fun terminalRejectionFixtures(): List<JsonElement> = buildList {
    addAll(terminalShapeRejections())
    addAll(terminalPlacementRejections())
    addAll(terminalOptionRejections())
    addAll(nonRunTerminalRejections())
    for ((type, count) in listOf("EMIT" to 1001, "TRACE" to 1000)) {
        add(
            Json.encodeToJsonElement(
                TerminalEnvelope(
                    TerminalRunFixture(
                        TerminalFixture(type),
                        List(count) { PublicToolDistinctSymbols },
                    )
                )
            )
        )
    }
}

private fun terminalShapeRejections(): List<JsonElement> = buildList {
    val explicit = Json { encodeDefaults = true }
    add(explicit.encodeToJsonElement(MissingTerminalEnvelope()))
    for (terminal in
        listOf(
            JsonNull,
            JsonPrimitive("TRACE"),
            JsonPrimitive(1),
            JsonPrimitive(false),
            Json.encodeToJsonElement(emptyList<String>()),
            Json.encodeToJsonElement(InvalidTerminalFixture()),
            explicit.encodeToJsonElement(InvalidTerminalFixture()),
            Json.encodeToJsonElement(InvalidTerminalFixture("UNKNOWN")),
            Json.encodeToJsonElement(InvalidTerminalFixture("EMIT", expansionScope = PublicToolImpactWorkspaceDomain)),
            Json.encodeToJsonElement(InvalidTerminalFixture("TRACE", options = emptyList())),
            Json.encodeToJsonElement(InvalidTerminalFixture("TRACE", output = InvalidOutputFixture())),
            Json.encodeToJsonElement(
                InvalidTerminalFixture("TRACE", output = InvalidOutputFixture(fields = emptyList()))
            ),
            Json.encodeToJsonElement(InvalidTerminalFixture("TRACE", output = InvalidOutputFixture("SYMBOLS"))),
            explicit.encodeToJsonElement(InvalidTerminalFixture("TRACE", output = InvalidOutputFixture("SYMBOLS"))),
        )) add(Json.encodeToJsonElement(TerminalEnvelope(TerminalRunFixture(terminal))))
    for (output in
        listOf(
            PublicToolOccurrencesOutput,
            PublicToolTraversalRecordsOutput,
            PublicToolBindingRowsOutput,
            PublicToolValuePathsOutput,
            PublicToolImpactWitnessOutput(ImpactWitnessSectionDocument.FINDINGS),
        )) add(Json.encodeToJsonElement(TerminalEnvelope(TerminalRunFixture(TerminalFixture("TRACE", output)))))
}

private fun terminalPlacementRejections(): List<JsonElement> = buildList {
    val trace = LegacyTraceFixture()
    val distinct = LegacyTraceFixture("DISTINCT_SYMBOLS")
    for (steps in
        listOf(
            listOf(trace),
            listOf(trace, trace),
            listOf(trace, distinct),
            listOf(distinct, trace),
            listOf(distinct, trace, distinct),
        )) {
        for (type in listOf("EMIT", "TRACE")) {
            add(Json.encodeToJsonElement(LegacyEnvelope(LegacyRunFixture(TerminalFixture(type), steps = steps))))
        }
    }
}

private fun terminalOptionRejections(): List<JsonElement> = buildList {
    // Corrupt one property at a time in otherwise admitted typed requests; do not mask applicability failures.
    val emit = Json.encodeToString(TerminalEnvelope(TerminalRunFixture(TerminalFixture("EMIT"))))
    for ((original, replacement) in
        listOf(
            "\"EMIT\"" to "\"EMIT\",\"expansionScope\":null",
            "\"EMIT\"" to "\"EMIT\",\"options\":null",
            "\"RUN\"" to "\"RUN\",\"output\":null",
            "\"RUN\"" to "\"RUN\",\"traceOptions\":null",
        )) add(Json.parseToJsonElement(emit.replace(original, replacement)))
    for (type in listOf("EMIT", "TRACE")) {
        val symbols =
            Json.encodeToString(
                TerminalEnvelope(
                    TerminalRunFixture(TraceEncodedShape(type, output = SymbolOutputShape("SYMBOLS", emptyList())))
                )
            )
        addAll(terminalOutputRejections(type, symbols))
        for ((field, value) in
            listOf(
                "options" to JsonNull,
                "options" to Json.encodeToJsonElement(EmptyOptions()),
                "fields" to JsonNull,
                "fields" to Json.encodeToJsonElement(emptyList<String>()),
                "unknownOption" to JsonNull,
            )) {
            add(Json.parseToJsonElement(symbols.replace("\"$type\"", "\"$type\",\"$field\":$value")))
        }
    }
}

private fun terminalOutputRejections(type: String, symbols: String): List<JsonElement> = buildList {
    for (replacement in
        listOf(
            JsonNull,
            JsonPrimitive(false),
            Json.encodeToJsonElement(listOf("NAME", "NAME")),
            Json.encodeToJsonElement(listOf("UNKNOWN")),
        )) {
        add(Json.parseToJsonElement(symbols.replace("\"fields\":[]", "\"fields\":$replacement")))
    }
    for (output in
        listOf(
            InvalidOutputFixture(),
            InvalidOutputFixture("SYMBOLS"),
            InvalidOutputFixture(fields = emptyList()),
        )) {
        add(
            Json.encodeToJsonElement(
                TerminalEnvelope(TerminalRunFixture(InvalidTerminalFixture(type, output = output)))
            )
        )
    }
}

private fun nonRunTerminalRejections(): List<JsonElement> = buildList {
    for (example in PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS).examples.values) {
        val action = example.value.jsonObject.getValue("request").jsonObject.getValue("type").jsonPrimitive.content
        if (action == "RUN") continue
        check(PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, example.value) is Refinement.Refined)
        for (field in listOf("terminalStep", "traceOptions")) {
            add(
                Json.parseToJsonElement(
                    example.value.toString().replace("\"type\":\"$action\"", "\"type\":\"$action\",\"$field\":null")
                )
            )
        }
    }
}

@Serializable private data class EmitShape(val type: String, val output: SymbolOutputShape? = null)

@Serializable
private data class TraceEncodedShape(
    val type: String,
    val expansionScope: PublicToolExpansionScope? = null,
    val output: SymbolOutputShape? = null,
)

@Serializable private data class SymbolOutputShape(val type: String, val fields: List<String>)

@Serializable private data class TerminalEnvelope<T>(val request: T)

@Serializable
private data class TerminalRunFixture<T>(
    val terminalStep: T,
    val steps: List<PublicToolStep>? = null,
    @EncodeDefault val source: PublicToolSource = PublicToolAllSource(),
    @EncodeDefault val type: String = "RUN",
)

@Serializable private data class TerminalFixture(val type: String, val output: PublicToolOutput? = null)

@Serializable private data class MissingTerminalEnvelope(val request: MissingTerminalRun = MissingTerminalRun())

@Serializable
private data class MissingTerminalRun(
    val type: String = "RUN",
    val source: PublicToolSource = PublicToolAllSource(),
)

/** These intentionally incompatible shapes isolate schema rejection, not semantic authority. */
@Serializable
private data class InvalidTerminalFixture(
    val type: String? = null,
    val expansionScope: PublicToolExpansionScope? = null,
    val output: InvalidOutputFixture? = null,
    val options: List<String>? = null,
)

@Serializable private data class InvalidOutputFixture(val type: String? = null, val fields: List<String>? = null)

@Serializable private class EmptyOptions

@Serializable private data class LegacyEnvelope(val request: LegacyRunFixture)

@Serializable
private data class LegacyRunFixture(
    val terminalStep: TerminalFixture,
    @EncodeDefault val type: String = "RUN",
    @EncodeDefault val source: PublicToolSource = PublicToolAllSource(),
    val steps: List<LegacyTraceFixture> = emptyList(),
    val output: PublicToolOutput? = null,
    val traceOptions: List<String>? = null,
)

@Serializable private data class LegacyTraceFixture(@EncodeDefault val type: String = "TRACE")
