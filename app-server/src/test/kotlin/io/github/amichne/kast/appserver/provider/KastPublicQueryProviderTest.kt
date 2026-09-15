package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.*
import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.registry.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastPublicQueryProviderTest {
    @Test
    fun `provider preserves schema-bound facade syntax without preparing lifecycle`(@TempDir root: Path) = runTest {
        val executor = RecordingExecutor(capability())
        val broker = broker(root, executor)
        val raw = """{"class_name":"Order","name_match":null,"scope":null}"""
        assertTrue(broker.dispatch(request(root, PublicToolIdentity.SEARCH_CLASSES, raw)) is BrokerDispatch.Completed)
        val calls = executor.requests.filter { it.arguments == listOf("tool", "search_classes") }
        assertEquals(1, calls.size)
        val sent = Json.parseToJsonElement((calls.single().input as BrokerProcessInput.Document).value)
        val parsed = PublicToolContract.admit(PublicToolIdentity.SEARCH_CLASSES, sent).refined()
        assertEquals(PublicToolContract.encode(parsed), sent)
        assertEquals(Json.parseToJsonElement(raw), sent)
        assertTrue(executor.requests.none { it.arguments.first() in setOf("start", "index", "topology") })
    }

    @Test
    fun `multiple search presentations bind distinct schemas to one operation`(@TempDir root: Path) = runTest {
        val executor = RecordingExecutor(capability())
        val broker = broker(root, executor)
        assertTrue(
            broker.dispatch(
                request(
                    root,
                    PublicToolIdentity.SEARCH_CLASSES,
                    """{"class_name":"Order","name_match":null,"scope":null}""",
                )
            ) is BrokerDispatch.Completed
        )
        assertTrue(
            broker.dispatch(
                request(
                    root,
                    PublicToolIdentity.SEARCH_FUNCTIONS,
                    """{"function_name":"createOrder","name_match":null,"scope":null}""",
                )
            ) is BrokerDispatch.Completed
        )
        assertTrue(
            broker.dispatch(
                request(
                    root,
                    PublicToolIdentity.SEARCH_FUNCTIONS,
                    """{"class_name":"Order","name_match":null,"scope":null}""",
                )
            ) is BrokerDispatch.Rejected
        )
        assertEquals(
            listOf(listOf("tool", "search_classes"), listOf("tool", "search_functions")),
            executor.requests.filter { it.arguments.first() == "tool" }.map { it.arguments },
        )
    }

    @Test
    fun `invalid controls and lexical paths fail before semantic invocation`(@TempDir root: Path) = runTest {
        val executor = RecordingExecutor(capability())
        val broker = broker(root, executor)
        listOf(
                """{"class_name":"Order","name_match":null,"scope":null,"execution":{"kind":"exhaustive"}}""",
                """{"class_name":"Order","name_match":null,"scope":{"relative_directory_path":"../src","include_subdirectories":true,"source_set_names":null}}""",
                """{"class_name":"Order","name_match":null}""",
            )
            .forEach {
                assertTrue(
                    broker.dispatch(request(root, PublicToolIdentity.SEARCH_CLASSES, it)) is BrokerDispatch.Rejected
                )
            }
        val pathFailure =
            broker.dispatch(
                request(
                    root,
                    PublicToolIdentity.SEARCH_CLASSES,
                    """{"class_name":"Order","name_match":null,"scope":{"relative_directory_path":"/PRIVATE_SECRET_PATH","include_subdirectories":true,"source_set_names":null}}""",
                )
            ) as BrokerDispatch.Rejected
        val guidance = (pathFailure.failure as BrokerFailure.InvalidArguments).guidance.single().value
        assertTrue(guidance.contains("scope.relative_directory_path"))
        assertTrue(guidance.contains("workspace-relative"))
        assertFalse(guidance.contains("PRIVATE_SECRET_PATH"))
        assertEquals(0, executor.requests.count { it.arguments.first() == "tool" })
    }

    @Test
    fun `qualification rejects drifted schema old catalog and cross-tool invocation`(@TempDir root: Path) = runTest {
        val schema = capability()
        val invalid =
            listOf(
                capability(driftSchema = true),
                schema.replace("\"schemaVersion\":13", "\"schemaVersion\":10"),
                schema.replace(
                    "\"command\":[\"tool\",\"search_classes\"]",
                    "\"command\":[\"tool\",\"search_functions\"]",
                ),
            )
        invalid.forEach { input ->
            val executor = RecordingExecutor(input)
            assertEquals(
                KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE),
                KastProviderQualifier.qualify(options(root, executor)),
            )
            assertEquals(0, executor.requests.count { it.arguments.first() == "tool" })
        }
    }

    private suspend fun broker(root: Path, executor: RecordingExecutor): Broker {
        val qualified = KastProviderQualifier.qualify(options(root, executor))
        assertTrue(qualified is KastProviderQualification.Qualified, qualified.toString())
        return when (
            val result =
                Broker.create(
                    listOf((qualified as KastProviderQualification.Qualified).registration),
                    BrokerLimits.defaults(),
                )
        ) {
            is Validation.Validated -> result.value
            is Validation.Rejected -> error("Rejected test broker")
        }
    }

    private fun options(root: Path, executor: RecordingExecutor): KastProviderOptions {
        val executable = root.resolve("kast")
        Files.writeString(executable, "#!/bin/sh\nexit 0\n")
        check(executable.toFile().setExecutable(true))
        return KastProviderOptions.admit(executable, root.toRealPath(), executor).refined()
    }

    private fun request(root: Path, identity: PublicToolIdentity, input: String) =
        BrokerDispatchRequest(
            ToolAddress(ProviderNamespace.admit("kast").refined(), ToolName.admit(identity.toolName).refined()),
            Json.parseToJsonElement(input),
            BrokerInvocationContext.admit("query-thread", "query-turn", "query-call", root.toRealPath()).refined(),
        )

    private class RecordingExecutor(private val schema: String) : BrokerProcessExecutor {
        val requests = mutableListOf<BrokerProcessRequest>()

        override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution {
            requests += request
            return when {
                request.arguments == listOf("--version") -> BrokerProcessExecution.Completed(0, "kast 9.9.9\n", "")
                request.arguments == listOf("--schema") -> BrokerProcessExecution.Completed(0, schema, "")
                request.arguments.first() == "tool" ->
                    BrokerProcessExecution.Completed(
                        0,
                        """{"operation":"query.run","status":"complete","items":[],"failures":[]}""",
                        "",
                    )
                else -> error("Unexpected subprocess: ${request.arguments}")
            }
        }
    }

    private fun capability(driftSchema: Boolean = false): String {
        val json = Json { encodeDefaults = true }
        val base = json.decodeFromString<KastCapabilityBoundary>(installedKastCatalogFixture())
        val projection = base.serverProjection
        val facades = PublicToolIdentity.entries.map { it.toolName }.toSet()
        val tools =
            projection.hostedBootstrap.tools
                .filter { it.name in facades }
                .map { tool ->
                    tool.copy(
                        inputSchema =
                            if (driftSchema)
                                json.encodeToJsonElement(
                                    tool.inputSchema.jsonObject.toMutableMap().apply {
                                        put("description", JsonPrimitive("drift"))
                                    }
                                )
                            else tool.inputSchema,
                        outputSchema = json.encodeToJsonElement(FacadeOutputSchema()),
                    )
                }
        return json.encodeToString(
            base.copy(
                serverProjection =
                    projection.copy(
                        hostedBootstrap = projection.hostedBootstrap.copy(tools = tools),
                        cliInvocations =
                            projection.cliInvocations.copy(
                                operations = projection.cliInvocations.operations.filter { it.toolName in facades }
                            ),
                    )
            )
        )
    }

    private fun <T, E> Refinement<T, E>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Rejected test fixture: $failure")
        }
}

@Serializable
private data class FacadeOutputSchema(
    val type: String = "object",
    val properties: FacadeOutputProperties = FacadeOutputProperties(),
    val required: List<String> = listOf("status", "document"),
    val additionalProperties: Boolean = false,
)

@Serializable
private data class FacadeOutputProperties(
    val status: FacadeOutputStatus = FacadeOutputStatus(),
    val document: FacadeOutputDocument = FacadeOutputDocument(),
)

@Serializable private data class FacadeOutputStatus(@SerialName("enum") val values: List<String> = listOf("completed"))

@Serializable private data class FacadeOutputDocument(val type: String = "object")
