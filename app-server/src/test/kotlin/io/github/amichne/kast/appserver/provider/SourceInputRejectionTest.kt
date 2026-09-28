package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerDispatch
import io.github.amichne.kast.appserver.core.BrokerDispatchRequest
import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.BrokerTool
import io.github.amichne.kast.appserver.core.ProviderCall
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ProviderRegistration
import io.github.amichne.kast.appserver.core.ProviderStartup
import io.github.amichne.kast.appserver.core.ProviderVersion
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolDescription
import io.github.amichne.kast.appserver.core.ToolLoading
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.core.ToolPresentation
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.schema.JsonDomainDefinition
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceRequestField
import io.github.amichne.kast.protocol.contract.SourceRequestPath
import io.github.amichne.kast.protocol.contract.SourceRequestRule
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SourceInputRejectionTest {
    private val identity = PublicToolIdentity.READ_SOURCE
    private val exact = "exact:v5:AAAAAAAAAAAAAAAAAAAAAQ"
    private val schema =
        (NetworkntJsonSchemaCompiler.compile(PublicToolContract.parameters(identity)) as Refinement.Refined).value

    @Test
    fun `provider admits source through the shared facade binding`() {
        val raw = PublicToolContract.examples(identity).examples.getValue("exactSymbol").value
        val validated = schema.admit(raw) as Validation.Validated
        val admitted =
            admitKastInput(CanonicalOperation.SOURCE_READ, validated.value, AgentToolInputBinding.Facade(identity))
        assertTrue(admitted is Validation.Validated, admitted.toString())
        val canonical = ((admitted as Validation.Validated).value as KastInvocationInput.Facade).request.canonical
        assertEquals(
            exact,
            ((canonical as PublicToolCanonical.Source).request.anchor as SourceReadAnchorDocument.Symbol)
                .selector
                .value,
        )
    }

    @Test
    fun `retired source syntax gives finite rejection evidence`() {
        val raw = PublicToolContract.examples(identity).invalidExamples.getValue("retiredSymbol").value
        assertTrue(schema.admit(raw) is Validation.Rejected)
        assertEquals(
            SourceReadFailureDetail.RequestRejected(
                SourceRequestField(SourceRequestPath.DOCUMENT),
                SourceRequestRule.INVALID_JSON,
            ),
            sourceInputRejectionEvidence(raw).cause,
        )
    }

    @Test
    fun `source schema rejection does not start the runtime`(@TempDir directory: Path) = runTest {
        var starts = 0
        var invokes = 0
        val tool: BrokerTool<Unit, Unit, Unit, Nothing> =
            BrokerTool(
                name = (ToolName.admit("read_source") as Refinement.Refined).value,
                description = (ToolDescription.admit("Source input rejection fixture") as Refinement.Refined).value,
                loading = ToolLoading.EAGER,
                input = JsonDomainDefinition(schema, RefinementDefinition { Validation.validated(Unit) }),
                outputSchema = schema,
                invoke = { _, _, _ ->
                    invokes++
                    ProviderCall.Completed(Unit)
                },
                encode = { Json.encodeToJsonElement(EmptyOutput()) },
                present = { ToolPresentation.text("fixture", false) },
                inputRejectionEvidence = ::sourceInputRejectionEvidence,
            )
        val namespace = (ProviderNamespace.admit("kast") as Refinement.Refined).value
        val provider =
            ProviderRegistration.define(
                namespace,
                (ProviderVersion.admit("1.0.0") as Refinement.Refined).value,
                listOf(tool),
            ) {
                starts++
                ProviderStartup.Started(Unit)
            } as Validation.Validated
        val broker = (Broker.create(listOf(provider.value), BrokerLimits.defaults()) as Validation.Validated).value
        val request =
            BrokerDispatchRequest(
                ToolAddress(namespace, tool.name),
                PublicToolContract.examples(identity).invalidExamples.getValue("retiredSymbol").value,
                (BrokerInvocationContext.admit("thread", "turn", "call", directory.toRealPath()) as Refinement.Refined)
                    .value,
            )
        val result = broker.dispatch(request) as BrokerDispatch.Rejected
        assertTrue(result.failure is BrokerFailure.SourceInputRejected)
        assertEquals(0, starts)
        assertEquals(0, invokes)
    }
}

@Serializable private class EmptyOutput
