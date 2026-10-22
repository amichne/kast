@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerDispatch
import io.github.amichne.kast.appserver.core.BrokerDispatchRequest
import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.BrokerTool
import io.github.amichne.kast.appserver.core.ProviderCall
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ProviderRegistration
import io.github.amichne.kast.appserver.core.ProviderStartup
import io.github.amichne.kast.appserver.core.ProviderVersion
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolDescription
import io.github.amichne.kast.appserver.core.ToolLoading
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.core.ToolPresentation
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolQuerySymbols
import io.github.amichne.kast.appserver.query.PublicToolReferenceSource
import io.github.amichne.kast.appserver.query.PublicToolRunAction
import io.github.amichne.kast.appserver.schema.JsonDomainDefinition
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunnerControlledBrokerExecutionTest {
    @Test
    fun `broker preserves the admitted mode through its provider timeout`(@TempDir directory: Path) = runTest {
        val json = Json { encodeDefaults = true }
        val name = (ToolName.admit("query_symbols") as Refinement.Refined).value
        val namespace = (ProviderNamespace.admit("kast") as Refinement.Refined).value
        val address = ToolAddress(namespace, name)
        val broker = broker(json, address)
        for (mode in listOf(QueryExecutionModeDocument.RUNNER_CONTROLLED, QueryExecutionModeDocument.BOUNDED)) {
            val request = request(json, address, mode, directory)
            val result = async { broker.dispatch(request) }
            runCurrent()
            advanceTimeBy(150)
            runCurrent()
            when (mode) {
                QueryExecutionModeDocument.RUNNER_CONTROLLED -> {
                    assertFalse(result.isCompleted, "The provider timer must preserve runner ownership")
                    advanceTimeBy(50)
                    runCurrent()
                    assertInstanceOf(BrokerDispatch.Completed::class.java, result.await())
                }
                QueryExecutionModeDocument.BOUNDED ->
                    assertEquals(
                        BrokerDispatch.Rejected(
                            BrokerFailure.ProviderInvocationRejected(address, ProviderFailureCode.TIMED_OUT)
                        ),
                        result.await(),
                    )
            }
        }
    }

    private fun broker(json: Json, address: ToolAddress): Broker {
        val registration =
            (ProviderRegistration.define(
                    address.namespace,
                    (ProviderVersion.admit("1") as Refinement.Refined).value,
                    listOf(tool(json, address.tool)),
                    start = { ProviderStartup.Started(Unit) },
                ) as Validation.Validated)
                .value
        return (Broker.create(listOf(registration), BrokerLimits.defaults()) as Validation.Validated).value
    }

    private fun tool(
        json: Json,
        name: ToolName,
    ): BrokerTool<Unit, KastInvocationInput, BrokerModeReply, KastToolInputFailure> {
        val outputSchema =
            (NetworkntJsonSchemaCompiler.compile(json.encodeToJsonElement(BrokerModeSchema()).jsonObject)
                    as Refinement.Refined)
                .value
        return BrokerTool(
            name = name,
            description = (ToolDescription.admit("Query compiler symbols") as Refinement.Refined).value,
            loading = ToolLoading.EAGER,
            input =
                JsonDomainDefinition(
                    PublicToolContract.schema(PublicToolIdentity.QUERY_SYMBOLS),
                    RefinementDefinition { admitted ->
                        admitKastInput(
                            CanonicalOperation.QUERY_RUN,
                            admitted,
                            AgentToolInputBinding.Facade(PublicToolIdentity.QUERY_SYMBOLS),
                        )
                    },
                ),
            outputSchema = outputSchema,
            invoke = { _, _, _ ->
                delay(200)
                ProviderCall.Completed(BrokerModeReply())
            },
            encode = { json.encodeToJsonElement(it) },
            present = { ToolPresentation.text(json.encodeToString(it), true) },
            invocationBudget = (ElapsedTimeLimitMillis.parse(100) as Refinement.Refined).value,
            effect = BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ),
            executionMode = KastInvocationInput::executionMode,
        )
    }

    private fun request(
        json: Json,
        address: ToolAddress,
        mode: QueryExecutionModeDocument,
        directory: Path,
    ): BrokerDispatchRequest {
        val reference = (ProtocolText.parse("exact:v2:opaque") as Refinement.Refined).value
        val references = (BoundedProtocolList.create(listOf(reference)) as Refinement.Refined).value
        val input =
            json.encodeToJsonElement(
                PublicToolQuerySymbols(
                    PublicToolRunAction(
                        PublicToolReferenceSource(references),
                        executionMode = mode,
                    )
                )
            )
        val context =
            (BrokerInvocationContext.admit("thread", "turn", mode.name, directory.toRealPath()) as Refinement.Refined)
                .value
        return BrokerDispatchRequest(address, input, context)
    }
}

@Serializable private data class BrokerModeSchema(val type: String = "object")

@Serializable private data class BrokerModeReply(val status: String = "complete")
