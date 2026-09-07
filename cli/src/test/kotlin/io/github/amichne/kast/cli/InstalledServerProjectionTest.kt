package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    fun `installed schema separates hosted bootstrap from cli invocation bindings`() {
        val projection = installedProjection()
        val bootstrap = projection.getValue("hostedBootstrap").jsonObject
        val tools = bootstrap.getValue("tools").jsonArray.map(JsonElement::jsonObject)
        val cliBindings = projection.getValue("cliInvocationBindings")
            .jsonObject
            .getValue("bindings")
            .jsonArray
            .map(JsonElement::jsonObject)

        assertEquals(5, projection.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertTrue(
            bootstrap.getValue("policy").jsonPrimitive.content
                .contains("compiler-grounded Kotlin source intelligence"),
        )
        assertEquals(
            tools.map { it.getValue("operationId").jsonPrimitive.content },
            cliBindings.map { it.getValue("operationId").jsonPrimitive.content },
        )
        assertTrue(tools.none { "cliUsage" in it || "invocation" in it })
        assertTrue(cliBindings.all { "cliUsage" in it && "invocation" in it })
        assertTrue(cliBindings.none { "description" in it || "inputSchema" in it })
    }

    @Test
    fun `server projection publishes readiness and canonical semantic budgets`() {
        val tools = projectionTools()
        val readBudget = tools.tool("source.read").getValue("executionBudget").jsonObject
        val traversalBudget = tools.tool("traversal.run").getValue("executionBudget").jsonObject
        assertEquals("1020000", readBudget.getValue("readinessMillis").jsonPrimitive.content)
        assertEquals("60000", readBudget.getValue("operationMillis").jsonPrimitive.content)
        assertEquals(
            OperationExecutionBudget.forOperation(CanonicalOperation.TRAVERSAL_RUN).operation.value.toString(),
            traversalBudget.getValue("operationMillis").jsonPrimitive.content,
        )
        assertEquals(240_000L, OperationExecutionBudget.forOperation(CanonicalOperation.TOPOLOGY_BUILD).operation.value)
    }

    @Test
    fun `installed broker exposes workflow facade names and explicit change approval`() {
        val tools = projectionTools()
        val bindings = projectionBindings()

        assertEquals(
            listOf(
                "symbol_lookup",
                "symbol_inspect",
                "source_read",
                "semantic_query",
                "impact_analyze",
                "diagnostic_check",
                "change_plan",
                "change_apply",
                "change_recover",
            ),
            tools.map { it.getValue("name").jsonPrimitive.content },
        )
        assertEquals(listOf("symbol", "inspect"), bindings.binding("symbol.inspect").cliCommand())
        assertTrue(
            tools.filter { it.getValue("name").jsonPrimitive.content.startsWith("change_") }
                .all {
                    it.getValue("approvalPolicy").jsonPrimitive.content ==
                        HostedApprovalPolicy.EXPLICIT.name.lowercase()
                },
        )
        assertTrue(
            tools.filterNot { it.getValue("name").jsonPrimitive.content.startsWith("change_") }
                .all {
                    it.getValue("approvalPolicy").jsonPrimitive.content ==
                        HostedApprovalPolicy.NONE.name.lowercase()
                },
        )
    }

    @Test
    fun `installed broker publishes executable read operations`() {
        val tools = projectionTools()
        val bindings = projectionBindings()

        assertEquals(
            listOf(
                "symbol.discover",
                "symbol.inspect",
                "source.read",
                "relation.read",
                "traversal.run",
                "diagnostic.check",
                "change.plan",
                "change.apply",
                "change.recover",
            ),
            tools.map { it.getValue("operationId").jsonPrimitive.content },
        )
        assertEquals(
            listOf("selector", "relation", "limit", "continuation"),
            bindings.binding("relation.read").cliOptionFields(),
        )
        assertEquals(
            listOf("scope", "limit"),
            bindings.binding("diagnostic.check").cliOptionFields(),
        )
        tools.tool("relation.read").outputSchema().assertAdmits(
            """{"status":"completed","document":{"operation":"relation.read","status":"complete","relations":[]}}""",
        )
        tools.tool("diagnostic.check").outputSchema().assertAdmits(
            """{"status":"completed","document":{"operation":"diagnostic.check","status":"complete","diagnostics":[]}}""",
        )
    }

    @Test
    fun `installed schema owns broker tool shapes and exact cli bindings`() {
        val schema = installedSchema(
            operationRegistry = "{}",
            wireSchema = "{}",
            commandSurface = commandGraphFactory().surface,
        ).constructedDocument()
        val projection = Json.parseToJsonElement(schema.value)
            .jsonObject
            .getValue("serverProjection")
            .jsonObject
        val bootstrap = projection.getValue("hostedBootstrap").jsonObject
        val tools = bootstrap.getValue("tools").jsonArray.map { it.jsonObject }
        val bindings = projection.getValue("cliInvocationBindings")
            .jsonObject.getValue("bindings").jsonArray.map { it.jsonObject }
        val expectedPublicOperations = HostedOperationProjection.publicDefinitions
            .map { it.operation.id.value }
        val internalOperations = HostedOperationProjection.internalDefinitions
            .map { it.operation.id.value }

        assertEquals(9, tools.size)
        assertEquals(5, projection.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("kast", projection.getValue("namespace").jsonPrimitive.content)
        assertEquals(
            expectedPublicOperations,
            tools.map { it.getValue("operationId").jsonPrimitive.content },
        )
        assertEquals(
            listOf(
                "symbol_lookup",
                "symbol_inspect",
                "source_read",
                "semantic_query",
                "impact_analyze",
                "diagnostic_check",
                "change_plan",
                "change_apply",
                "change_recover",
            ),
            tools.map { it.getValue("name").jsonPrimitive.content },
        )
        assertTrue(tools.all { it.getValue("deferLoading").jsonPrimitive.content.toBoolean() })
        assertFalse(
            tools.any { it.getValue("operationId").jsonPrimitive.content in internalOperations },
        )

        val discover = tools.tool("symbol.discover")
        val variants = discover.getValue("inputSchema")
            .jsonObject
            .getValue("anyOf")
            .jsonArray
            .map { it.jsonObject }
        assertEquals(4, variants.size)
        assertEquals(
            listOf("name", "location", "text", "text"),
            variants.map { variant ->
                variant.getValue("properties")
                    .jsonObject
                    .getValue("mode")
                    .jsonObject
                    .getValue("const")
                    .jsonPrimitive
                    .content
            },
        )
        assertEquals(
            listOf("mode", "query", "kind", "match", "file", "offset", "scope", "limit"),
            bindings.binding("symbol.discover").cliOptionFields(),
        )
        assertEquals(
            linkedMapOf(
                "symbol.discover" to listOf("symbol", "discover"),
                "symbol.inspect" to listOf("symbol", "inspect"),
                "source.read" to listOf("source", "read"),
                "relation.read" to listOf("relation", "read"),
                "traversal.run" to listOf("traversal", "run"),
                "diagnostic.check" to listOf("diagnostic", "check"),
                "change.plan" to listOf("change", "plan"),
                "change.apply" to listOf("change", "apply"),
                "change.recover" to listOf("change", "recover"),
            ),
            bindings.associate { binding ->
                binding.getValue("operationId").jsonPrimitive.content to binding.cliCommand()
            },
        )
        assertEquals(
            linkedMapOf(
                "symbol.discover" to
                    listOf("mode", "query", "kind", "match", "file", "offset", "scope", "limit"),
                "symbol.inspect" to listOf("candidate", "selector"),
                "source.read" to listOf(
                    "anchor",
                    "region",
                    "declarationKinds",
                    "visibility",
                    "includeParameters",
                    "includeCalls",
                    "includeReferences",
                    "containment",
                    "text",
                    "beforeLines",
                    "afterLines",
                    "entityLimit",
                    "textByteLimit",
                    "continuation",
                ),
                "relation.read" to listOf("selector", "relation", "limit", "continuation"),
                "traversal.run" to
                    listOf(
                        "selector",
                        "relation",
                        "maximumDepth",
                        "maximumResults",
                        "continuation",
                    ),
                "diagnostic.check" to listOf("scope", "limit"),
                "change.plan" to listOf("intent", "target", "declaration"),
                "change.apply" to listOf("plan"),
                "change.recover" to listOf("plan"),
            ),
            bindings.associate { binding ->
                binding.getValue("operationId").jsonPrimitive.content to binding.cliOptionFields()
            },
        )
        assertEquals(tools.size, tools.map { it.getValue("outputSchema") }.distinct().size)

        assertTrue(
            tools.tool("symbol.inspect").completedDocumentRequiredProperties()
                .containsAll(listOf("operation", "status", "symbol")),
        )
        assertTrue(
            tools.tool("traversal.run").completedDocumentProperty("graph") != null,
        )

        val changePlanProperties = tools.tool("change.plan")
            .getValue("inputSchema")
            .jsonObject
            .getValue("properties")
            .jsonObject
        assertEquals(
            "add-declaration",
            changePlanProperties.getValue("intent")
                .jsonObject
                .getValue("const")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `canonical output schemas retain internal topology and public diagnostic proof`() {
        val coverage = """{"status":"completed","document":{"operation":"topology.build","status":"rejected","reason":"coverage-incomplete","missing":["src/Missing.kt"],"unexpected":[],"duplicateCandidates":[],"duplicateCompletions":[],"workspaceMismatches":[],"candidateEvidenceMismatches":[],"duplicateSymbols":[],"missingEdgeTargets":[],"mismatchedEdgeEndpoints":[]}}"""
        val longMessage = "x".repeat(20_000)
        val diagnostic = """{"status":"completed","document":{"operation":"diagnostic.check","status":"complete","diagnostics":[{"severity":"warning","code":"LONG_MESSAGE","message":"$longMessage","location":{"candidateSelector":"candidate:diagnostic","file":"src/A.kt","range":{"startInclusive":0,"endExclusive":0}}}]}}"""

        assertAll(
            { installedServerOutputSchema(CanonicalOperation.TOPOLOGY_BUILD).assertAdmits(coverage) },
            {
                installedServerOutputSchema(CanonicalOperation.DIAGNOSTIC_CHECK)
                    .assertAdmits(diagnostic)
            },
        )
    }

    @Test
    fun `hosted output schema admits typed cold runtime rejection evidence`() {
        val runtimeRejection =
            """{"status":"rejected","diagnostic":{"status":"rejected","boundary":"runtime","reason":"gradle-import-failed","bootstrap":{"state":"rejected","attemptId":"728b343f-b2ca-4c67-b5cb-8abd9fc6886e","phase":"importing-gradle-model","completedPhases":2,"totalPhases":7,"cause":"gradle-import-failed","correctiveAction":"Run the repository Gradle wrapper successfully with the admitted import inputs, then run kast start again.","gradleJvm":{"type":"io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionObservation.Observed","report":{"distribution":{"type":"io.github.amichne.kast.distribution.contract.gradle.GradleDistributionEvidence.Observed","version":"9.4.1"},"requiredJava":[17,21,25],"candidates":[{"java":25,"homeIdentity":"d3bb48e3f4a12b8eafcd37372767714786c6efe55d2683b57822d8d5a69b8923","authority":"AMBIENT_JAVA_HOME","decision":"SELECTED"}],"outcome":{"type":"io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionOutcome.Selected","candidate":{"java":25,"homeIdentity":"d3bb48e3f4a12b8eafcd37372767714786c6efe55d2683b57822d8d5a69b8923","authority":"AMBIENT_JAVA_HOME","decision":"SELECTED"}}}}}}}"""

        installedServerOutputSchema(CanonicalOperation.SYMBOL_DISCOVER)
            .assertAdmits(runtimeRejection)
    }

    @Test
    fun `symbol output schema rejects proof contradictions`() {
        val schema = projectionTools().tool("symbol.inspect").outputSchema()
        val valid = symbolInspectProcessDocument(
            kind = "classlike",
            qualifiedIdentity = "\"sample.Controller\"",
            signature = """{"type":"class-like","qualifiedIdentity":"sample.Controller"}""",
        )
        val unavailableIdentity = symbolInspectProcessDocument(
            kind = "classlike",
            qualifiedIdentity = "null",
            signature = """{"type":"class-like","qualifiedIdentity":"sample.Controller"}""",
        )
        val incompatibleKind = symbolInspectProcessDocument(
            kind = "function",
            qualifiedIdentity = "\"sample.Controller\"",
            signature = """{"type":"class-like","qualifiedIdentity":"sample.Controller"}""",
        )
        val property = symbolInspectProcessDocument(
            kind = "property",
            qualifiedIdentity = "\"sample.Controller\"",
            signature = """{"type":"property","qualifiedIdentity":"sample.Controller","receiver":{"type":"present","compilerType":"kotlin.String"},"contextReceivers":["sample.Context"],"returnType":"kotlin.Int"}""",
        )
        val propertyWithoutReceiverProof = symbolInspectProcessDocument(
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
        val fileEvidence = """{"workspace":{"root":"/workspace","generation":3,"sourceState":"state"},"sourceRoot":{"module":"main","buildRoot":".","projectPath":":","sourceSet":"main","location":"src/main/kotlin","provenance":"authored"},"path":"src/Alpha.kt","contentHash":"${"b".repeat(64)}"}"""
        val endpoint = """{"node":{"compilerIdentity":"$compilerIdentity","file":"src/Alpha.kt","range":{"startInclusive":0,"endExclusive":5}},"fileEvidence":$fileEvidence,"name":"Alpha","qualifiedIdentity":{"state":"available","value":"sample.Alpha"},"kind":"classlike","compilerEvidence":{"identity":"$compilerIdentity","signature":{"type":"class-like","qualifiedIdentity":"sample.Alpha"}}}"""
        val valid = """{"status":"completed","document":{"operation":"topology.build","status":"rejected","reason":"coverage-incomplete","missing":[],"unexpected":[],"duplicateCandidates":[],"duplicateCompletions":[],"workspaceMismatches":[],"candidateEvidenceMismatches":[],"duplicateSymbols":[],"missingEdgeTargets":[],"mismatchedEdgeEndpoints":[$endpoint]}}"""
        val proofDropped = valid.replace(
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
    fun `source read projection binds repeatable filters flags and proof rich outcomes`() {
        val tool = projectionTools().tool("source.read")
        val bindingTypes = projectionBindings().binding("source.read").getValue("invocation")
            .jsonObject
            .getValue("bindings")
            .jsonArray
            .associate { binding ->
                binding.jsonObject.getValue("inputField").jsonPrimitive.content to
                    binding.jsonObject.getValue("type").jsonPrimitive.content
            }
        assertEquals("REPEATED_OPTION", bindingTypes.getValue("declarationKinds"))
        assertEquals("REPEATED_OPTION", bindingTypes.getValue("visibility"))
        assertEquals("FLAG", bindingTypes.getValue("includeCalls"))

        val complete = """{"status":"completed","document":{"operation":"source.read","status":"complete","snapshot":{"canonicalRoot":"/workspace","generation":17,"sourceState":"state","file":"src/Empty.kt","textIdentity":"identity","coordinateUnit":"utf16-code-unit","length":0},"region":{"kind":"file","selection":{"selector":"source-selector-v1:payload:digest","range":{"startInclusive":0,"endExclusive":0}}},"entities":[],"text":{"type":"returned","lines":{"startInclusive":1,"endInclusive":1},"selection":{"selector":"source-selector-v1:payload:digest","range":{"startInclusive":0,"endExclusive":0}},"text":""}}} """
        val qualified = """{"status":"completed","document":{"operation":"source.read","status":"qualified","snapshot":{"canonicalRoot":"/workspace","generation":17,"sourceState":"state","file":"src/Target.kt","textIdentity":"identity","coordinateUnit":"utf16-code-unit","length":10},"region":{"kind":"declaration","selection":{"selector":"source-selector-v1:payload:digest","range":{"startInclusive":0,"endExclusive":10}}},"entities":[],"text":{"type":"withheld","reason":"byte-limit-reached"},"qualification":{"knownMinimumEntityCount":0,"limitations":["text-byte-limit-reached"],"continuation":{"type":"unavailable"}}}}"""
        val missingRegionSelector = complete.replace(
            "\"selection\":{\"selector\":\"source-selector-v1:payload:digest\",\"range\":{\"startInclusive\":0,\"endExclusive\":0}},",
            "",
        )

        assertAll(
            { tool.outputSchema().assertAdmits(complete) },
            { tool.outputSchema().assertAdmits(qualified) },
            { tool.outputSchema().assertRejects(missingRegionSelector) },
        )
    }

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error(construction.failures)
    }

    private fun projectionTools(): List<JsonObject> {
        return installedProjection()
            .getValue("hostedBootstrap")
            .jsonObject
            .getValue("tools")
            .jsonArray
            .map(JsonElement::jsonObject)
    }

    private fun installedProjection(): JsonObject {
        val schema = installedSchema(
            operationRegistry = "{}",
            wireSchema = "{}",
            commandSurface = commandGraphFactory().surface,
        ).constructedDocument()
        return Json.parseToJsonElement(schema.value)
            .jsonObject
            .getValue("serverProjection")
            .jsonObject
    }

    private fun projectionBindings(): List<JsonObject> = installedProjection()
        .getValue("cliInvocationBindings")
        .jsonObject
        .getValue("bindings")
        .jsonArray
        .map(JsonElement::jsonObject)

    private fun InstalledSchemaConstruction.constructedDocument(): CliJsonDocument = when (this) {
        is InstalledSchemaConstruction.Constructed -> document
        is InstalledSchemaConstruction.Rejected -> error(failure)
    }

    private fun JsonObject.cliCommand(): List<String> =
        getValue("invocation")
            .jsonObject
            .getValue("command")
            .jsonArray
            .map { it.jsonPrimitive.content }

    private fun JsonObject.cliOptionFields(): List<String> =
        getValue("invocation")
            .jsonObject
            .getValue("bindings")
            .jsonArray
            .map { binding ->
                binding.jsonObject.getValue("inputField").jsonPrimitive.content
            }

    private fun JsonObject.outputSchema(): JsonObject = getValue("outputSchema").jsonObject

    private fun JsonObject.assertAdmits(document: String) {
        val messages = validate(document)
        assertTrue(messages.isEmpty(), "schema rejected emitted document: $messages")
    }

    private fun JsonObject.assertRejects(document: String) {
        val messages = validate(document)
        assertTrue(messages.isNotEmpty(), "schema admitted contradictory document")
    }

    private fun JsonObject.validate(document: String): Set<String> =
        schemaRegistry.getSchema(toString())
            .validate(document, InputFormat.JSON)
            .mapTo(linkedSetOf()) { it.message }

    private fun symbolInspectProcessDocument(
        kind: String,
        qualifiedIdentity: String,
        signature: String,
    ): String = """{"status":"completed","document":{"operation":"symbol.inspect","status":"complete","symbol":{"selector":"exact:v1:3:1","kind":"$kind","name":"Controller","qualifiedIdentity":$qualifiedIdentity,"file":"src/Controller.kt","range":{"startInclusive":0,"endExclusive":10},"compilerEvidence":{"identity":"canonical-signature-sha256-v1|${"a".repeat(64)}","signature":$signature}}}}"""

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
            .getValue("anyOf")
            .jsonArray
            .first()
            .jsonObject

    private fun JsonObject.completedDocumentRequiredProperties(): List<String> =
        completedDocumentSchema().getValue("required").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.completedDocumentProperty(name: String) =
        completedDocumentSchema().getValue("properties").jsonObject[name]

    private fun List<JsonObject>.tool(
        operationId: String,
    ): JsonObject = single {
        it.getValue("operationId").jsonPrimitive.content == operationId
    }

    private fun List<JsonObject>.binding(
        operationId: String,
    ): JsonObject = single {
        it.getValue("operationId").jsonPrimitive.content == operationId
    }

    companion object {
        private val schemaRegistry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
        )
    }
}
