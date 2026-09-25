package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerDispatch
import io.github.amichne.kast.appserver.core.BrokerDispatchRequest
import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.appserver.publicNameQuery
import io.github.amichne.kast.appserver.publicToolCase
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastPublicQueryProviderTest {
    @Test
    fun `provider preserves schema-bound facade syntax without preparing lifecycle`(@TempDir root: Path) = runBlocking {
        val executor = RecordingExecutor(capability())
        val broker = broker(root, executor)
        val raw = publicNameQuery()
        assertTrue(broker.dispatch(request(root, PublicToolIdentity.QUERY_SYMBOLS, raw)) is BrokerDispatch.Completed)
        val read = assertInstanceOf(ExistingIdeOperation.Read::class.java, executor.operations.single())
        val parsed = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw).refined()
        val expected =
            canonicalCliRequestPreparers().queryRun.prepare((parsed.canonical as PublicToolCanonical.Query).request)
                as OperationPreparation.Prepared
        assertEquals(expected.request.document, read.request.document)
    }

    @Test
    fun `invalid controls and lexical paths fail before semantic invocation`(@TempDir root: Path) = runBlocking {
        val executor = RecordingExecutor(capability())
        val broker = broker(root, executor)
        listOf("provider-unknown-execution", "invalid-directory-1", "provider-missing-controls").forEach { id ->
            assertTrue(
                broker.dispatch(request(root, PublicToolIdentity.QUERY_SYMBOLS, publicToolCase(id)))
                    is BrokerDispatch.Rejected
            )
        }
        val pathFailure =
            broker.dispatch(
                request(
                    root,
                    PublicToolIdentity.QUERY_SYMBOLS,
                    publicToolCase("invalid-directory-8"),
                )
            ) as BrokerDispatch.Rejected
        val guidance = (pathFailure.failure as BrokerFailure.InvalidArguments).guidance.single().value
        assertTrue(guidance.contains("scope.relative_directory_path"))
        assertTrue(guidance.contains("workspace-relative"))
        assertFalse(guidance.contains("PRIVATE_SECRET_PATH"))
    }

    @Test
    fun `qualification rejects drifted schema and old catalog`(@TempDir root: Path) = runBlocking {
        val schema = capability()
        val invalid =
            listOf(
                capability(driftSchema = true),
                schema.replace("\"schemaVersion\":15", "\"schemaVersion\":10"),
            )
        invalid.forEach { input ->
            val executor = RecordingExecutor(input)
            assertEquals(
                KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE),
                KastProviderQualifier.qualify(options(root, executor)),
            )
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
        return KastProviderOptions(
            catalogSource = executor,
            roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(CanonicalRoot(root.toRealPath())) },
            ideClient =
                ExistingIdeClient { _, operation ->
                    executor.operations += operation
                    ExistingIdeExchange.HostRejected(
                        CanonicalJsonDocument.generated(HostRejection.serializer()).create(HostRejection())
                    )
                },
        )
    }

    private fun request(root: Path, identity: PublicToolIdentity, input: String) =
        request(root, identity, Json.parseToJsonElement(input))

    private fun request(root: Path, identity: PublicToolIdentity, input: kotlinx.serialization.json.JsonElement) =
        BrokerDispatchRequest(
            ToolAddress(ProviderNamespace.admit("kast").refined(), ToolName.admit(identity.toolName).refined()),
            input,
            BrokerInvocationContext.admit("query-thread", "query-turn", "query-call", root.toRealPath()).refined(),
        )

    @Serializable
    private data class HostRejection(val type: String = "HOST_REJECTED", val failure: String = "DIRTY_DOCUMENTS")

    private class RecordingExecutor(private val schema: String) : KastCatalogSource {
        val operations = mutableListOf<ExistingIdeOperation>()
        private var reads = 0

        override fun read(): Refinement<String, KastQualificationFailure> {
            check(++reads <= 2)
            return Refinement.Refined(schema)
        }
    }

    private fun capability(driftSchema: Boolean = false): String {
        val json = Json { encodeDefaults = true }
        val base = json.decodeFromString<KastCapabilityBoundary>(installedKastCatalogFixture())
        val projection = base.serverProjection
        val tools =
            projection.hostedBootstrap.tools.map { tool ->
                tool.copy(
                    inputSchema =
                        if (driftSchema)
                            json.encodeToJsonElement(
                                tool.inputSchema.jsonObject.toMutableMap().apply {
                                    put("description", JsonPrimitive("drift"))
                                }
                            )
                        else tool.inputSchema,
                    outputSchema = tool.outputSchema,
                )
            }
        return json.encodeToString(
            base.copy(
                serverProjection = projection.copy(hostedBootstrap = projection.hostedBootstrap.copy(tools = tools))
            )
        )
    }

    private fun <T, E> Refinement<T, E>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Rejected test fixture: $failure")
        }
}
