package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionEpochFailure
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionEpochRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionModelIdentity
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionNextAction
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionObstruction
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionReadOperation
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionReadinessReason
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshAttempt
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshFailure
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshStatus
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshWaiter
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshWaiterIdentity
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRetainedModel
import io.github.amichne.kast.protocol.contract.WorkspaceReadinessInspectionDocument
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshInspectionDocument
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import io.github.amichne.kast.protocol.wire.presentation.CanonicalCallbackSchemaDocuments
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceInspectionSchemaReuseTest {
    private val json = Json { encodeDefaults = true }
    private val schema by lazy { installedServerOutputSchema(CanonicalOperation.WORKSPACE_LIFECYCLE) }
    private val validator by lazy {
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema.toString())
    }
    private val identity = WorkspaceInspectionModelIdentity("/workspace", "native-incarnation")
    private val obstruction =
        WorkspaceInspectionObstruction(
            WorkspaceInspectionReadinessReason.NATIVE_WORK,
            WorkspaceInspectionNextAction.OBSERVE_SETTLEMENT,
            WorkspaceInspectionRetainedModel.Observed(identity),
            listOf(WorkspaceInspectionReadOperation.Traced("native-read")),
            WorkspaceInspectionEpochRejection.Rejected(WorkspaceInspectionEpochFailure.GRADLE_MODEL_INCOMPLETE),
        )
    private val waiter =
        WorkspaceInspectionRefreshWaiter(
            WorkspaceInspectionRefreshWaiterIdentity.Request("refresh-request"),
            WorkspaceInspectionRefreshStatus.Failed(WorkspaceInspectionRefreshFailure.DEADLINE_EXCEEDED),
        )
    private val attempt =
        WorkspaceInspectionRefreshAttempt(
            7,
            WorkspaceInspectionRefreshEffect.MODEL_RELOAD,
            3,
            waiter.identity,
            listOf(waiter),
        )

    @Test
    fun `expanding local reuse preserves the entire canonical lifecycle result schema`() {
        LocalSchemaAssertions(installedSemanticResultSchema(CanonicalOperation.WORKSPACE_LIFECYCLE))
            .assertEquivalent(generatedOutputSchema(IdeLifecycleResult.serializer(), CanonicalSchemaReferences.None))
        val assertions = LocalSchemaAssertions(schema)
        assertions.assertLocalReferences()
        assertions.assertDefinitionEquivalent(
            "workspaceInspectionObstruction",
            generatedOutputSchema(WorkspaceInspectionObstruction.serializer(), CanonicalSchemaReferences.None),
        )
        assertions.assertDefinitionEquivalent(
            "workspaceInspectionRefreshAttempt",
            generatedOutputSchema(WorkspaceInspectionRefreshAttempt.serializer(), CanonicalSchemaReferences.None),
        )
    }

    @Test
    fun `compiler evidence reuse preserves all canonical callback schemas and references stay local`() {
        val querySchema = installedServerOutputSchema(CanonicalOperation.QUERY_RUN)
        val assertions = LocalSchemaAssertions(querySchema)
        assertions.assertLocalReferences()
        for ((name, serializer) in CanonicalCallbackSchemaDocuments.serializers) {
            assertions.assertDefinitionEquivalent(
                name,
                generatedOutputSchema(serializer, CanonicalSchemaReferences.None),
            )
        }
        assertTrue(referenceCount(querySchema, "compilerSymbolEvidence") > 1)
    }

    @Test
    fun `all decoded inspection variants retain model evidence and unresolved attempt facts`() {
        for (readiness in readinessVariants()) {
            for (refresh in refreshVariants()) {
                val document = inspectedDocument(readiness, refresh)
                val encoded = json.encodeToJsonElement<IdeLifecycleResult>(document)
                assertEquals(document, json.decodeFromString<IdeLifecycleResult>(encoded.toString()))
                val envelope = json.encodeToJsonElement(Completed(document))
                assertTrue(validator.validate(envelope.toString(), InputFormat.JSON).isEmpty(), envelope.toString())
            }
        }
    }

    @Test
    fun `factoring retains closed reasons actions and required native attempt identity`() {
        val envelope =
            json.encodeToString(
                Completed(
                    inspectedDocument(
                        WorkspaceReadinessInspectionDocument.Pending(identity, obstruction),
                        WorkspaceRefreshInspectionDocument.Running(attempt, emptyList()),
                    )
                )
            )
        for (invalid in
            listOf(
                envelope.replace("\"NATIVE_WORK\"", "\"UNKNOWN_REASON\""),
                envelope.replace("\"OBSERVE_SETTLEMENT\"", "\"UNKNOWN_ACTION\""),
                envelope.replace("\"id\":7,", ""),
                envelope.replace("\"stamp\":3", "\"stamp\":3,\"inferredReady\":true"),
            )) assertTrue(validator.validate(invalid, InputFormat.JSON).isNotEmpty(), invalid)
    }

    private fun readinessVariants(): List<WorkspaceReadinessInspectionDocument> =
        listOf(
            WorkspaceReadinessInspectionDocument.Unknown,
            WorkspaceReadinessInspectionDocument.Ready(identity),
            WorkspaceReadinessInspectionDocument.Unavailable(identity, obstruction),
            WorkspaceReadinessInspectionDocument.Pending(identity, obstruction),
            WorkspaceReadinessInspectionDocument.Blocked(identity, obstruction),
        )

    private fun refreshVariants(): List<WorkspaceRefreshInspectionDocument> =
        listOf(
            WorkspaceRefreshInspectionDocument.Unknown,
            WorkspaceRefreshInspectionDocument.Idle,
            WorkspaceRefreshInspectionDocument.Retired(listOf(attempt)),
            WorkspaceRefreshInspectionDocument.Running(attempt, listOf(attempt.copy(id = 8))),
            WorkspaceRefreshInspectionDocument.AwaitingAdmission(listOf(waiter)),
        )

    private fun inspectedDocument(
        readiness: WorkspaceReadinessInspectionDocument,
        refresh: WorkspaceRefreshInspectionDocument,
    ): IdeLifecycleResult.Inspected =
        IdeLifecycleResult.Inspected(
            host = "host",
            home = "/idea",
            build = "IU-262.99.1",
            projects =
                listOf(
                    IdeProjectDescription(
                        IdeProjectTarget("host", identity.incarnation, identity.root),
                        IdeProjectOwnership.BORROWED,
                        1,
                        readiness,
                        refresh,
                    )
                ),
            compatibility =
                HostedCompatibilityDocument(
                    "262.1.1",
                    "262.1.1-IJ",
                    "0.49.0",
                    CanonicalHostedContract.document,
                ),
        )

    @Serializable private data class Completed(val document: IdeLifecycleResult, val status: String = "completed")
}

/** Resolve local references while comparing exact shapes; never allocate an expanded schema tree. */
private class LocalSchemaAssertions(private val schema: JsonObject) {
    private val definitions = schema["\$defs"]?.jsonObject.orEmpty()

    fun assertEquivalent(expected: JsonElement) {
        assertLocalReferences()
        compare(expected, schema, "root", allowDefinitions = true)
    }

    fun assertDefinitionEquivalent(name: String, expected: JsonElement) {
        compare(expected, definitions.getValue(name), name)
    }

    fun assertLocalReferences() {
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach(::visit)
                is JsonObject -> {
                    val name = referenceName(element)
                    if (name == null) element.values.forEach(::visit)
                    else if (name !in visited) {
                        assertTrue(visiting.add(name), "Cyclic local definition: $name")
                        visit(definitions.getValue(name))
                        visiting.remove(name)
                        visited.add(name)
                    }
                }
                else -> Unit
            }
        }
        visit(schema)
    }

    private fun compare(expected: JsonElement, actual: JsonElement, path: String, allowDefinitions: Boolean = false) {
        val name = (actual as? JsonObject)?.let(::referenceName)
        if (name != null) {
            compare(expected, definitions.getValue(name), path)
            return
        }
        when (expected) {
            is JsonObject -> compareObject(expected, actual, path, allowDefinitions)
            is JsonArray -> {
                assertTrue(actual is JsonArray, "Expected array at $path")
                actual as JsonArray
                assertEquals(expected.size, actual.size, "Array length at $path")
                expected.indices.forEach { index -> compare(expected[index], actual[index], "$path/$index") }
            }
            else -> assertEquals(expected, actual, "Primitive at $path")
        }
    }

    private fun compareObject(expected: JsonObject, actual: JsonElement, path: String, allowDefinitions: Boolean) {
        assertTrue(actual is JsonObject, "Expected object at $path")
        actual as JsonObject
        val keys = if (allowDefinitions) actual.keys - "\$defs" else actual.keys
        assertEquals(expected.keys, keys, "Object fields at $path")
        expected.forEach { (key, child) -> compare(child, actual.getValue(key), "$path/$key") }
    }

    private fun referenceName(element: JsonObject): String? {
        val reference = element["\$ref"]?.jsonPrimitive?.content ?: return null
        assertTrue(reference.startsWith("#/\$defs/"), reference)
        assertEquals(setOf("\$ref"), element.keys)
        val name = reference.removePrefix("#/\$defs/")
        assertTrue(name in definitions, "Missing local definition: $name")
        return name
    }
}

private fun referenceCount(value: JsonElement, name: String): Int =
    when (value) {
        is JsonArray -> value.sumOf { referenceCount(it, name) }
        is JsonObject -> {
            val matches = if (value["\$ref"]?.jsonPrimitive?.content == "#/\$defs/$name") 1 else 0
            matches + value.values.sumOf { referenceCount(it, name) }
        }
        else -> 0
    }
