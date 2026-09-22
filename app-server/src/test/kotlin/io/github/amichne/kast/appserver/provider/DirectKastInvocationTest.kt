package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.*
import io.github.amichne.kast.appserver.ide.*
import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DirectKastInvocationTest {
    @Test
    fun `search calls IDEA directly and retains a host rejection without a Kast child`(@TempDir directory: Path) =
        runBlocking {
            val root = directory.toRealPath()
            val executable = root.resolve("kast")
            Files.writeString(executable, "#!/bin/sh\nexit 0\n")
            check(executable.toFile().setExecutable(true))
            val processes = mutableListOf<List<String>>()
            val operations = mutableListOf<ExistingIdeOperation>()
            val options =
                KastProviderOptions.admit(
                    executable,
                    root,
                    processExecutor =
                        BrokerProcessExecutor { request ->
                            processes += request.arguments
                            when (request.arguments) {
                                listOf("--version") -> BrokerProcessExecution.Completed(0, "kast 9.9.9\n", "")
                                listOf("--schema") ->
                                    BrokerProcessExecution.Completed(0, installedKastCatalogFixture(), "")
                                else -> BrokerProcessExecution.Rejected(BrokerProcessFailure.SPAWN_FAILED)
                            }
                        },
                    roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(CanonicalRoot(root)) },
                    ideClient =
                        ExistingIdeClient { admittedRoot, operation ->
                            assertEquals(root, admittedRoot.path)
                            operations += operation
                            ExistingIdeExchange.HostRejected(
                                CanonicalJsonDocument.generated(HostRejection.serializer()).create(HostRejection())
                            )
                        },
                ) as Refinement.Refined
            val qualified =
                assertInstanceOf(
                    KastProviderQualification.Qualified::class.java,
                    KastProviderQualifier.qualify(options.value),
                )
            val broker =
                (Broker.create(listOf(qualified.registration), BrokerLimits.defaults()) as Validation.Validated).value
            val result =
                broker.dispatch(
                    BrokerDispatchRequest(
                        ToolAddress(
                            (ProviderNamespace.admit("kast") as Refinement.Refined).value,
                            (ToolName.admit("search_classes") as Refinement.Refined).value,
                        ),
                        Json.encodeToJsonElement(
                            io.github.amichne.kast.appserver.query.PublicToolSearchClasses.serializer(),
                            io.github.amichne.kast.appserver.query.PublicToolSearchClasses(
                                (io.github.amichne.kast.protocol.contract.ProtocolText.parse("Order")
                                        as Refinement.Refined)
                                    .value,
                                null,
                                null,
                            ),
                        ),
                        (BrokerInvocationContext.admit("thread", "turn", "call", root) as Refinement.Refined).value,
                    )
                )
            val completed = assertInstanceOf(BrokerDispatch.Completed::class.java, result, result.toString())
            assertFalse(completed.presentation.success)
            assertTrue(completed.presentation.content.last().text.contains("DIRTY_DOCUMENTS"))
            val read = assertInstanceOf(ExistingIdeOperation.Read::class.java, operations.single())
            assertEquals(CanonicalOperation.QUERY_RUN, read.request.operation)
            assertTrue(processes.all { it == listOf("--version") || it == listOf("--schema") })
        }

    @Serializable
    private data class HostRejection(val type: String = "HOST_REJECTED", val failure: String = "DIRTY_DOCUMENTS")
}
