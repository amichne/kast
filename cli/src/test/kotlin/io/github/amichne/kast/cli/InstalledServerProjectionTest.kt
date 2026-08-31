package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
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
        val tools = projection.getValue("tools").jsonArray.map { it.jsonObject }
        val expectedPublicOperations = HostedOperationProjection.publicDefinitions
            .map { it.operation.id.value }
        val internalOperations = HostedOperationProjection.internalDefinitions
            .map { it.operation.id.value }

        assertEquals(2, projection.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("kast", projection.getValue("namespace").jsonPrimitive.content)
        assertEquals(
            expectedPublicOperations,
            tools.map { it.getValue("operationId").jsonPrimitive.content },
        )
        assertEquals(
            expectedPublicOperations.map { it.replace('.', '_') },
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
        assertEquals(5, variants.size)
        assertEquals(
            listOf("name", "location", "structure", "text", "text"),
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
            discover.cliOptionFields(),
        )
        assertEquals(
            linkedMapOf(
                "workspace.inspect" to listOf("workspace", "inspect"),
                "topology.build" to listOf("topology", "build"),
                "symbol.discover" to listOf("symbol", "discover"),
                "symbol.resolve" to listOf("symbol", "resolve"),
                "symbol.describe" to listOf("symbol", "describe"),
                "traversal.run" to listOf("traversal", "run"),
                "change.plan" to listOf("change", "plan"),
                "change.apply" to listOf("change", "apply"),
                "change.verify" to listOf("change", "verify"),
                "change.recover" to listOf("change", "recover"),
            ),
            tools.associate { tool ->
                tool.getValue("operationId").jsonPrimitive.content to tool.cliCommand()
            },
        )
        assertEquals(
            linkedMapOf(
                "workspace.inspect" to emptyList(),
                "topology.build" to emptyList(),
                "symbol.discover" to
                    listOf("mode", "query", "kind", "match", "file", "offset", "scope", "limit"),
                "symbol.resolve" to listOf("candidate"),
                "symbol.describe" to listOf("selector"),
                "traversal.run" to
                    listOf("selector", "relation", "maximumDepth", "maximumResults"),
                "change.plan" to listOf("intent", "target", "declaration"),
                "change.apply" to listOf("plan"),
                "change.verify" to listOf("application"),
                "change.recover" to listOf("plan"),
            ),
            tools.associate { tool ->
                tool.getValue("operationId").jsonPrimitive.content to tool.cliOptionFields()
            },
        )
        assertEquals(tools.size, tools.map { it.getValue("outputSchema") }.distinct().size)

        assertTrue(
            tools.tool("symbol.describe").completedDocumentRequiredProperties()
                .containsAll(listOf("operation", "status", "symbol")),
        )
        assertTrue(
            tools.tool("traversal.run").completedDocumentProperty("records") != null,
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
    fun `advertised output schemas admit emitted proof rich documents`() {
        val tools = projectionTools()
        val coverage = """{"status":"completed","document":{"operation":"topology.build","status":"rejected","reason":"coverage-incomplete","missing":["src/Missing.kt"],"unexpected":[],"duplicateCandidates":[],"duplicateCompletions":[],"workspaceMismatches":[],"candidateEvidenceMismatches":[],"duplicateSymbols":[],"missingEdgeTargets":[],"mismatchedEdgeEndpoints":[]}}"""
        val compatibility = """{"status":"rejected","diagnostic":{"status":"rejected","boundary":"runtime","reason":"ide-descriptor-rejected","details":{"type":"compatibility-rejected","failure":{"type":"mismatch","field":"kast-plugin-version","expected":"1.2.3","observed":"1.2.4"}}}}"""
        val longMessage = "x".repeat(20_000)
        val diagnostic = """{"status":"completed","document":{"operation":"diagnostic.check","status":"complete","diagnostics":[{"severity":"warning","code":"LONG_MESSAGE","message":"$longMessage","location":{"file":"src/A.kt","range":{"startInclusive":0,"endExclusive":0}}}]}}"""

        assertAll(
            { tools.tool("topology.build").outputSchema().assertAdmits(coverage) },
            { tools.tool("workspace.inspect").outputSchema().assertAdmits(compatibility) },
            {
                installedServerOutputSchema(CanonicalOperation.DIAGNOSTIC_CHECK)
                    .assertAdmits(diagnostic)
            },
        )
    }

    @Test
    fun `symbol output schema rejects proof contradictions`() {
        val schema = projectionTools().tool("symbol.describe").outputSchema()
        val valid = symbolDescribeProcessDocument(
            kind = "classlike",
            qualifiedIdentity = "\"sample.Controller\"",
            signature = """{"type":"class-like","qualifiedIdentity":"sample.Controller"}""",
        )
        val unavailableIdentity = symbolDescribeProcessDocument(
            kind = "classlike",
            qualifiedIdentity = "null",
            signature = """{"type":"class-like","qualifiedIdentity":"sample.Controller"}""",
        )
        val incompatibleKind = symbolDescribeProcessDocument(
            kind = "function",
            qualifiedIdentity = "\"sample.Controller\"",
            signature = """{"type":"class-like","qualifiedIdentity":"sample.Controller"}""",
        )
        val property = symbolDescribeProcessDocument(
            kind = "property",
            qualifiedIdentity = "\"sample.Controller\"",
            signature = """{"type":"property","qualifiedIdentity":"sample.Controller","receiver":{"type":"present","compilerType":"kotlin.String"},"contextReceivers":["sample.Context"],"returnType":"kotlin.Int"}""",
        )
        val propertyWithoutReceiverProof = symbolDescribeProcessDocument(
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
        val schema = projectionTools().tool("topology.build").outputSchema()
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

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error(construction.failures)
    }

    private fun projectionTools(): List<JsonObject> {
        val schema = installedSchema(
            operationRegistry = "{}",
            wireSchema = "{}",
            commandSurface = commandGraphFactory().surface,
        ).constructedDocument()
        return Json.parseToJsonElement(schema.value)
            .jsonObject
            .getValue("serverProjection")
            .jsonObject
            .getValue("tools")
            .jsonArray
            .map(JsonElement::jsonObject)
    }

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

    private fun symbolDescribeProcessDocument(
        kind: String,
        qualifiedIdentity: String,
        signature: String,
    ): String = """{"status":"completed","document":{"operation":"symbol.describe","status":"complete","symbol":{"selector":"exact:v1:3:1","kind":"$kind","name":"Controller","qualifiedIdentity":$qualifiedIdentity,"file":"src/Controller.kt","range":{"startInclusive":0,"endExclusive":10},"compilerEvidence":{"identity":"canonical-signature-sha256-v1|${"a".repeat(64)}","signature":$signature}}}}"""

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

    companion object {
        private val schemaRegistry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
        )
    }
}
