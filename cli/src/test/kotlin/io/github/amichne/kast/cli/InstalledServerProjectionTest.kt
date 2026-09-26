package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.query.PublicSourceAnchor
import io.github.amichne.kast.appserver.query.PublicSourceEntities
import io.github.amichne.kast.appserver.query.PublicSourceReadIntent
import io.github.amichne.kast.appserver.query.PublicSourceReadRequestSerializer
import io.github.amichne.kast.appserver.query.PublicSourceText
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledServerProjectionTest {
    @Test
    fun `source read public intent admits entity free declaration without unused controls`() {
        val selector =
            (ProtocolText.parse("exact:v2:e30:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
                    as io.github.amichne.kast.kernel.Refinement.Refined)
                .value
        val request =
            PublicSourceReadIntent(
                anchor = PublicSourceAnchor(selector),
                text = PublicSourceText.Complete(6000),
                entities = PublicSourceEntities.None,
            )
        val encoded = Json { classDiscriminator = "type" }.encodeToString(PublicSourceReadIntent.serializer(), request)
        val schema = projectionTools().tool("source_read").getValue("inputSchema").jsonObject
        schema.assertAdmits(encoded)
        val lowered = Json { classDiscriminator = "type" }.decodeFromString(PublicSourceReadRequestSerializer, encoded)
        assertEquals(SourceEntitySelectionDocument.None, lowered.entities)
        val contradictory = Json {
            encodeDefaults = true
        }
            .encodeToString(
                InvalidSourceIntent.serializer(),
                InvalidSourceIntent(PublicSourceAnchor(selector)),
            )
        schema.assertRejects(contradictory)
    }

    @Test
    fun `traversal resume input admits both supported checkpoint versions and rejects unknown versions`() {
        val schema =
            schemaRegistry.getSchema(projectionTools().tool("traverse_relations").getValue("inputSchema").toString())
        for (version in listOf("v1", "v2", "v3")) {
            val request =
                requireNotNull(javaClass.getResource("/projection/traversal-resume-schema.json"))
                    .readText()
                    .replace("traversal-continuation:v1:", "traversal-continuation:$version:")
            val admitted = schema.validate(request, InputFormat.JSON).isEmpty()
            assertEquals(version != "v3", admitted, version)
        }
    }

    @Test
    fun `full generated capability document fits the production provider schema byte budget`() {
        val document =
            installedSchema(
                    operationRegistry = CanonicalOperationWireBindings.operationRegistryDocument,
                    // Exact v1 metadata emitted by the build-owned CanonicalWireSchema.
                    wireSchema = """{"schemaVersion":1,"wireSchemaId":"kast-wire-v1"}""",
                    commandSurface = commandGraphFactory().surface,
                )
                .constructedDocument()
        val emittedBytes = (document.value + "\n").toByteArray(Charsets.UTF_8).size
        assertTrue(
            emittedBytes <= BrokerOperationalLimits.maximumKastSchemaBytes - QUALIFICATION_OUTPUT_HEADROOM_BYTES,
            "Schema output $emittedBytes bytes leaves insufficient qualification process headroom",
        )
    }

    @Test
    fun `installed schema separates hosted bootstrap from whole document cli invocations`() {
        val projection = installedProjection()
        val bootstrap = projection.getValue("hostedBootstrap").jsonObject
        val tools = bootstrap.getValue("tools").jsonArray.map(JsonElement::jsonObject)
        val cliInvocations =
            projection
                .getValue("cliInvocations")
                .jsonObject
                .getValue("operations")
                .jsonArray
                .map(JsonElement::jsonObject)

        assertEquals(15, projection.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals(
            4,
            projection.getValue("cliInvocations").jsonObject.getValue("schemaVersion").jsonPrimitive.content.toInt(),
        )
        assertTrue(bootstrap.getValue("policy").jsonPrimitive.content.contains("Use kast.query_symbols"))
        assertEquals(
            tools
                .filterNot {
                    it.getValue("operationId").jsonPrimitive.content in setOf("workspace.lifecycle", "change.run")
                }
                .map { it.getValue("operationId").jsonPrimitive.content },
            cliInvocations.map { it.getValue("operationId").jsonPrimitive.content },
        )
        assertTrue(tools.none { "cliUsage" in it || "invocation" in it })
        assertTrue(cliInvocations.all { "cliUsage" in it && "invocation" in it })
        assertTrue(cliInvocations.none { "description" in it || "inputSchema" in it })
        assertTrue(cliInvocations.all { "bindings" !in it.getValue("invocation").jsonObject })
    }

    @Test
    fun `server projection publishes readiness and canonical semantic budgets`() {
        val tools = projectionTools()
        val readBudget = tools.tool("source_read").getValue("executionBudget").jsonObject
        val traversalBudget = tools.tool("traverse_relations").getValue("executionBudget").jsonObject
        assertEquals("1020000", readBudget.getValue("readinessMillis").jsonPrimitive.content)
        assertEquals("60000", readBudget.getValue("operationMillis").jsonPrimitive.content)
        assertEquals(
            OperationExecutionBudget.forOperation(CanonicalOperation.TRAVERSAL_RUN).operation.value.toString(),
            traversalBudget.getValue("operationMillis").jsonPrimitive.content,
        )
        assertEquals(240_000L, OperationExecutionBudget.forOperation(CanonicalOperation.TOPOLOGY_BUILD).operation.value)
    }

    @Test
    fun `installed broker publishes executable read operations`() {
        val tools = projectionTools()
        val invocations = projectionInvocations()

        assertEquals(
            listOf(
                "workspace.lifecycle",
                "query.run",
                "symbol.discover",
                "symbol.inspect",
                "source.read",
                "traversal.run",
                "diagnostic.check",
                "change.run",
            ),
            tools.map { it.getValue("operationId").jsonPrimitive.content },
        )
        assertEquals(listOf("tool", "check_diagnostics"), invocations.invocation("check_diagnostics").cliCommand())
        tools
            .tool("check_diagnostics")
            .outputSchema()
            .assertAdmits(LiveReadOutputSchemaTest().completeEnvelope(CanonicalOperation.DIAGNOSTIC_CHECK))
    }

    @Test
    fun `diagnostic input names its filesystem path and rejects the former scope field`() {
        val input = projectionTools().tool("check_diagnostics").getValue("inputSchema").jsonObject

        input.assertAdmits("""{"relative_path":".","max_diagnostics":null}""")
        input.assertRejects("""{"scope":"workspace","limit":100}""")
    }

    @Test
    fun `query schema exposes intent and defaults but not evaluator states`() {
        val query = projectionTools().tool("query_symbols")
        val input = query.getValue("inputSchema").jsonObject
        input.assertAdmits(PublicQueryInputFixture.search("OrderService"))
        input.assertAdmits(PublicQueryInputFixture.all(fields = emptyList()))
        input.assertAdmits(PublicQueryInputFixture.references(listOf("NON_ISSUED_SCHEMA_TEST_ONLY")))
        input.assertRejects("""{"type":"QUERY","from":{"type":"ALL"}}""")
        input.assertRejects(PublicQueryInputFixture.unsupportedStep())
        assertEquals(
            io.github.amichne.kast.appserver.query.PublicToolContract.parameters(
                io.github.amichne.kast.protocol.registry.PublicToolIdentity.QUERY_SYMBOLS
            ),
            input,
        )
        query.outputSchema().assertAdmits(LiveReadOutputSchemaTest().completeEnvelope(CanonicalOperation.QUERY_RUN))
        query.outputSchema().assertAdmits(LiveReadOutputSchemaTest().qualifiedEnvelope(CanonicalOperation.QUERY_RUN))
    }

    @Test
    fun `installed schema owns broker tool shapes and exact whole document cli invocations`() {
        val schema =
            installedSchema(
                    operationRegistry = "{}",
                    wireSchema = "{}",
                    commandSurface = commandGraphFactory().surface,
                )
                .constructedDocument()
        val projection = Json.parseToJsonElement(schema.value).jsonObject.getValue("serverProjection").jsonObject
        val bootstrap = projection.getValue("hostedBootstrap").jsonObject
        val tools = bootstrap.getValue("tools").jsonArray.map { it.jsonObject }
        val invocations =
            projection.getValue("cliInvocations").jsonObject.getValue("operations").jsonArray.map { it.jsonObject }
        val expectedPublicOperations =
            io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions.all.map {
                it.operation.operation.id.value
            }
        val internalOperations = HostedOperationProjection.internalDefinitions.map { it.operation.id.value }

        assertEquals(8, tools.size)
        assertEquals(15, projection.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("kast", projection.getValue("namespace").jsonPrimitive.content)
        assertEquals(
            expectedPublicOperations.toSet(),
            tools.map { it.getValue("operationId").jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            listOf(
                "workspace_lifecycle",
                "query_symbols",
                "symbol_lookup",
                "symbol_inspect",
                "source_read",
                "traverse_relations",
                "check_diagnostics",
                "change",
            ),
            tools.map { it.getValue("name").jsonPrimitive.content },
        )
        assertFalse(tools.tool("query_symbols").getValue("deferLoading").jsonPrimitive.content.toBoolean())
        assertEquals(
            setOf("query_symbols", "check_diagnostics"),
            tools
                .filterNot { it.getValue("deferLoading").jsonPrimitive.boolean }
                .map { it.getValue("name").jsonPrimitive.content }
                .toSet(),
        )
        assertFalse(tools.any { it.getValue("operationId").jsonPrimitive.content in internalOperations })

        val discover = tools.tool("symbol_lookup")
        val targetVariants =
            discover
                .getValue("inputSchema")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("target")
                .jsonObject
                .getValue("anyOf")
                .jsonArray
                .map { it.jsonObject }
        assertEquals(3, targetVariants.size)
        assertEquals(
            listOf("location", "name", "text"),
            targetVariants.map { variant ->
                variant
                    .getValue("properties")
                    .jsonObject
                    .getValue("type")
                    .jsonObject
                    .getValue("const")
                    .jsonPrimitive
                    .content
            },
        )
        assertEquals(
            linkedMapOf(
                "query_symbols" to listOf("tool", "query_symbols"),
                "symbol_lookup" to listOf("symbol", "discover"),
                "symbol_inspect" to listOf("symbol", "inspect"),
                "source_read" to listOf("source", "read"),
                "traverse_relations" to listOf("traversal", "run"),
                "check_diagnostics" to listOf("tool", "check_diagnostics"),
            ),
            invocations.associate { invocation ->
                invocation.getValue("toolName").jsonPrimitive.content to invocation.cliCommand()
            },
        )
        assertTrue(invocations.all { "bindings" !in it.getValue("invocation").jsonObject })
        assertEquals(8, tools.map { it.getValue("outputSchema") }.distinct().size)

        assertTrue(
            tools
                .tool("symbol_inspect")
                .completedDocumentRequiredProperties()
                .containsAll(listOf("operation", "status", "symbol"))
        )
        assertTrue(tools.tool("traverse_relations").completedDocumentProperty("graph") != null)

        val changeIntentVariants =
            tools
                .tool("change")
                .getValue("inputSchema")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("intent")
                .jsonObject
                .getValue("anyOf")
                .jsonArray
        assertEquals(1, changeIntentVariants.size)
    }

    @Test
    fun `canonical output schemas retain internal topology and public diagnostic proof`() {
        val coverage =
            """{"status":"completed","document":{"operation":"topology.build","status":"rejected",""" +
                """"reason":"coverage-incomplete","missing":["src/Missing.kt"],"unexpected":[],""" +
                """"duplicateCandidates":[],"duplicateCompletions":[],"workspaceMismatches":[],""" +
                """"candidateEvidenceMismatches":[],"duplicateSymbols":[],"missingEdgeTargets":[],""" +
                """"mismatchedEdgeEndpoints":[]}}"""
        val longMessage = "x".repeat(20_000)
        val diagnostic = LiveReadOutputSchemaTest().diagnosticEnvelopeWithMessage(longMessage)

        assertAll(
            { installedServerOutputSchema(CanonicalOperation.TOPOLOGY_BUILD).assertAdmits(coverage) },
            {
                installedServerOutputSchema(CanonicalOperation.DIAGNOSTIC_CHECK).assertAdmits(diagnostic)
            },
        )
    }

    @Test
    fun `hosted output schema admits typed cold runtime rejection evidence`() {
        val runtimeRejection =
            """{"status":"rejected","diagnostic":{"status":"rejected","boundary":"runtime",""" +
                """"reason":"gradle-import-failed","bootstrap":{"state":"rejected",""" +
                """"attemptId":"728b343f-b2ca-4c67-b5cb-8abd9fc6886e","phase":"importing-gradle-model",""" +
                """"completedPhases":2,"totalPhases":7,"cause":"gradle-import-failed",""" +
                """"correctiveAction":"Run the repository Gradle wrapper successfully with the admitted import inputs, then run kast start again.","gradleJvm":{"type":"io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionObservation.Observed","report":{"distribution":{"type":"io.github.amichne.kast.distribution.contract.gradle.GradleDistributionEvidence.Observed","version":"9.4.1"},"requiredJava":[17,21,25],"candidates":[{"java":25,"homeIdentity":"d3bb48e3f4a12b8eafcd37372767714786c6efe55d2683b57822d8d5a69b8923","authority":"AMBIENT_JAVA_HOME","decision":"SELECTED"}],"outcome":{"type":"io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionOutcome.Selected","candidate":{"java":25,"homeIdentity":"d3bb48e3f4a12b8eafcd37372767714786c6efe55d2683b57822d8d5a69b8923","authority":"AMBIENT_JAVA_HOME","decision":"SELECTED"}}}}}}}"""

        installedServerOutputSchema(CanonicalOperation.SYMBOL_DISCOVER).assertAdmits(runtimeRejection)
    }

    @Test
    fun `symbol output schema rejects proof contradictions`() {
        val schema = projectionTools().tool("symbol_inspect").outputSchema()
        val valid =
            symbolInspectProcessDocument(
                kind = "classlike",
                qualifiedIdentity = "\"sample.Controller\"",
                signature = """{"type":"class-like","qualifiedIdentity":"sample.Controller"}""",
            )
        val unavailableIdentity =
            symbolInspectProcessDocument(
                kind = "classlike",
                qualifiedIdentity = "null",
                signature = """{"type":"class-like","qualifiedIdentity":"sample.Controller"}""",
            )
        val incompatibleKind =
            symbolInspectProcessDocument(
                kind = "function",
                qualifiedIdentity = "\"sample.Controller\"",
                signature = """{"type":"class-like","qualifiedIdentity":"sample.Controller"}""",
            )
        val property =
            symbolInspectProcessDocument(
                kind = "property",
                qualifiedIdentity = "\"sample.Controller\"",
                signature =
                    """{"type":"property","qualifiedIdentity":"sample.Controller","receiver":{"type":"present",""" +
                        """"compilerType":"kotlin.String"},"contextReceivers":["sample.Context"],""" +
                        """"returnType":"kotlin.Int"}""",
            )
        val propertyWithoutReceiverProof =
            symbolInspectProcessDocument(
                kind = "property",
                qualifiedIdentity = "\"sample.Controller\"",
                signature = """{"type":"property","qualifiedIdentity":"sample.Controller","returnType":"kotlin.Int"}""",
            )

        assertAll(
            { schema.assertAdmits(valid) },
            { schema.assertAdmits(property) },
            { schema.assertRejects(unavailableIdentity) },
            { schema.assertRejects(incompatibleKind) },
            { schema.assertRejects(propertyWithoutReceiverProof) },
        )
    }

    @Test
    fun `topology coverage schema requires compiler proof on mismatched endpoints`() {
        val schema = installedServerOutputSchema(CanonicalOperation.TOPOLOGY_BUILD)
        val compilerIdentity = "canonical-signature-sha256-v1|${"a".repeat(64)}"
        val fileEvidence =
            """{"workspace":{"root":"/workspace","generation":3,"sourceState":"state"},""" +
                """"sourceRoot":{"module":"main","buildRoot":".","projectPath":":","sourceSet":"main",""" +
                """"location":"src/main/kotlin","provenance":"authored"},"path":"src/Alpha.kt",""" +
                """"contentHash":"${"b".repeat(64)}"}"""
        val endpoint =
            """{"node":{"compilerIdentity":"$compilerIdentity","file":"src/Alpha.kt",""" +
                """"range":{"startInclusive":0,"endExclusive":5}},"fileEvidence":$fileEvidence,""" +
                """"name":"Alpha","qualifiedIdentity":{"state":"available","value":"sample.Alpha"},""" +
                """"kind":"classlike","compilerEvidence":{"identity":"$compilerIdentity",""" +
                """"signature":{"type":"class-like","qualifiedIdentity":"sample.Alpha"}}}"""
        val valid =
            """{"status":"completed","document":{"operation":"topology.build","status":"rejected",""" +
                """"reason":"coverage-incomplete","missing":[],"unexpected":[],"duplicateCandidates":[],""" +
                """"duplicateCompletions":[],"workspaceMismatches":[],"candidateEvidenceMismatches":[],""" +
                """"duplicateSymbols":[],"missingEdgeTargets":[],"mismatchedEdgeEndpoints":[$endpoint]}}"""
        val proofDropped =
            valid.replace(
                ",\"compilerEvidence\":{\"identity\":\"$compilerIdentity\",\"signature\":{\"type\":\"class-like\",\"qualifiedIdentity\":\"sample.Alpha\"}}",
                "",
            )
        val incompatibleKind = valid.replace("\"kind\":\"classlike\"", "\"kind\":\"function\"")

        assertAll(
            { schema.assertAdmits(valid) },
            { schema.assertRejects(proofDropped) },
            { schema.assertRejects(incompatibleKind) },
        )
    }

    @Test
    fun `source read projection accepts one canonical request document and proof rich outcomes`() {
        val tool = projectionTools().tool("source_read")
        assertEquals(
            listOf("source", "read"),
            projectionInvocations().invocation("source_read").cliCommand(),
        )

        val complete =
            """{"status":"completed","document":{"operation":"source.read","status":"complete",""" +
                """"snapshot":{"canonicalRoot":"/workspace","generation":17,"sourceState":"state",""" +
                """"file":"src/Empty.kt","textIdentity":"identity","coordinateUnit":"utf16-code-unit",""" +
                """"length":0},"region":{"kind":"file",""" +
                """"selection":{"selector":"source-selector-v1:payload:digest","range":{"startInclusive":0,""" +
                """"endExclusive":0}}},"entities":[],"text":{"type":"returned","lines":{"startInclusive":1,""" +
                """"endInclusive":1},"selection":{"selector":"source-selector-v1:payload:digest",""" +
                """"range":{"startInclusive":0,"endExclusive":0}},"text":""}}} """
        val qualified = LiveReadOutputSchemaTest().qualifiedEnvelope(CanonicalOperation.SOURCE_READ)
        val missingRegionSelector =
            complete.replace(
                "\"selection\":{\"selector\":\"source-selector-v1:payload:digest\",\"range\":{\"startInclusive\":0,\"endExclusive\":0}},",
                "",
            )

        assertAll(
            { tool.outputSchema().assertAdmits(complete) },
            { tool.outputSchema().assertAdmits(qualified) },
            { tool.outputSchema().assertRejects(missingRegionSelector) },
        )
    }

    private fun commandGraphFactory(): CliCommandGraphFactory =
        when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error(construction.failures)
        }

    internal fun projectionTools(): List<JsonObject> {
        return installedProjection()
            .getValue("hostedBootstrap")
            .jsonObject
            .getValue("tools")
            .jsonArray
            .map(JsonElement::jsonObject)
    }

    private fun installedProjection(): JsonObject {
        val schema =
            installedSchema(
                    operationRegistry = "{}",
                    wireSchema = "{}",
                    commandSurface = commandGraphFactory().surface,
                )
                .constructedDocument()
        return Json.parseToJsonElement(schema.value).jsonObject.getValue("serverProjection").jsonObject
    }

    private fun projectionInvocations(): List<JsonObject> =
        installedProjection()
            .getValue("cliInvocations")
            .jsonObject
            .getValue("operations")
            .jsonArray
            .map(JsonElement::jsonObject)

    private fun InstalledSchemaConstruction.constructedDocument(): CanonicalJsonDocument =
        when (this) {
            is InstalledSchemaConstruction.Constructed -> document
            is InstalledSchemaConstruction.Rejected -> error(failure)
        }

    private fun JsonObject.cliCommand(): List<String> =
        getValue("invocation").jsonObject.getValue("command").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.outputSchema(): JsonObject = getValue("outputSchema").jsonObject

    private fun JsonObject.assertAdmits(document: String) {
        val messages = validate(document)
        assertTrue(messages.isEmpty(), "schema rejected emitted document: $messages")
    }

    private fun JsonObject.assertRejects(document: String) {
        val messages = validate(document)
        assertTrue(messages.isNotEmpty(), "schema admitted contradictory document")
    }

    @Serializable
    private data class InvalidSourceIntent(
        val anchor: PublicSourceAnchor,
        val entities: InvalidEntityNone = InvalidEntityNone(),
    )

    @Serializable private data class InvalidEntityNone(val mode: String = "none", val limit: Int = 0)

    private fun JsonObject.validate(document: String): Set<String> =
        schemaRegistry.getSchema(toString()).validate(document, InputFormat.JSON).mapTo(linkedSetOf()) { it.message }

    private fun symbolInspectProcessDocument(kind: String, qualifiedIdentity: String, signature: String): String =
        SymbolInspectionFixture.process(kind, qualifiedIdentity, signature)

    private fun JsonObject.completedDocumentSchema(): JsonObject =
        getValue("outputSchema")
            .jsonObject
            .getValue("anyOf")
            .jsonArray
            .first()
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("document")
            .jsonObject
            .firstOutcomeVariant()

    private fun JsonObject.firstOutcomeVariant(): JsonObject =
        when {
            containsKey("anyOf") -> getValue("anyOf").jsonArray.first().jsonObject.firstOutcomeVariant()
            containsKey("oneOf") -> getValue("oneOf").jsonArray.first().jsonObject.firstOutcomeVariant()
            else -> this
        }

    private fun JsonObject.completedDocumentRequiredProperties(): List<String> =
        completedDocumentSchema().getValue("required").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.completedDocumentProperty(name: String) =
        completedDocumentSchema().getValue("properties").jsonObject[name]

    private fun List<JsonObject>.tool(name: String): JsonObject = single {
        it.getValue("name").jsonPrimitive.content == name
    }

    private fun List<JsonObject>.invocation(name: String): JsonObject = single {
        it.getValue("toolName").jsonPrimitive.content == name
    }

    companion object {
        private const val QUALIFICATION_OUTPUT_HEADROOM_BYTES = 4096
        private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    }
}
