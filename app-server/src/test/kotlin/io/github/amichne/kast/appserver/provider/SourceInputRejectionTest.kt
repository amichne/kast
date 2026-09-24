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
import io.github.amichne.kast.appserver.protocol.codex.BrokerFailureDocument
import io.github.amichne.kast.appserver.query.PublicSourceAnchor
import io.github.amichne.kast.appserver.query.PublicSourceEntities
import io.github.amichne.kast.appserver.query.PublicSourceReadIntent
import io.github.amichne.kast.appserver.query.PublicSourceText
import io.github.amichne.kast.appserver.schema.JsonDomainDefinition
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceRequestField
import io.github.amichne.kast.protocol.contract.SourceRequestIngress
import io.github.amichne.kast.protocol.contract.SourceRequestPath
import io.github.amichne.kast.protocol.contract.SourceRequestRule
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SourceInputRejectionTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `public source intent lowers exact reference with no entity limit`() {
        val selector =
            ProtocolText.parse("exact:v2:e30:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
                .refined()
        val intent =
            PublicSourceReadIntent(
                anchor = PublicSourceAnchor(selector),
                text = PublicSourceText.Complete(6000),
                entities = PublicSourceEntities.None,
            )
        val raw = Json { classDiscriminator = "type" }.encodeToJsonElement(PublicSourceReadIntent.serializer(), intent)
        val schema =
            NetworkntJsonSchemaCompiler.compile(
                    json.encodeToJsonElement(ObjectSchema.serializer(), ObjectSchema()).jsonObject
                )
                .refined()
        val admitted =
            admitKastInput(CanonicalOperation.SOURCE_READ, schema.admit(raw).validated()) as Validation.Validated
        val request = (admitted.value as KastInvocationInput.Source).request
        assertEquals(SourceReadAnchorDocument.Symbol(selector), request.anchor)
        assertEquals(SourceRegionSelectionDocument.Anchor, request.region)
        assertEquals(SourceEntitySelectionDocument.None, request.entities)
        assertEquals(250, request.entityLimit.value)
        assertEquals(6000, request.textByteLimit.value)
        assertEquals(SourceReadFormatDocument.COMPACT, request.format)
    }

    @Test
    fun `entity free source request rejects an explicit entity limit at public ingress`() {
        val selector =
            (ProtocolText.parse("exact:v2:e30:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
                    as Refinement.Refined)
                .value
        val request =
            SourceReadRequest(
                anchor = SourceReadAnchorDocument.Symbol(selector),
                region = SourceRegionSelectionDocument.Anchor,
                entities = SourceEntitySelectionDocument.None,
                text = SourceTextRequestDocument.Complete,
                entityLimit = (SourceEntityLimitDocument.parse(10) as Refinement.Refined).value,
            )
        val raw = json.encodeToJsonElement(SourceReadRequest.serializer(), request)
        assertEquals(
            Refinement.Rejected(
                SourceReadFailureDetail.RequestRejected(
                    SourceRequestField(SourceRequestPath.ENTITY_LIMIT),
                    SourceRequestRule.ENTITY_LIMIT_NOT_APPLICABLE,
                )
            ),
            SourceRequestIngress.decodePublic(raw, json),
        )
    }

    @Test
    fun `source schema rejection emits finite physical field before runtime startup`(@TempDir directory: Path) =
        runTest {
            var starts = 0
            var invokes = 0
            val schema =
                NetworkntJsonSchemaCompiler.compile(
                        json.encodeToJsonElement(RequiredAnchorSchema.serializer(), RequiredAnchorSchema()).jsonObject
                    )
                    .refined()
            val tool: BrokerTool<Unit, Unit, Unit, Nothing> =
                BrokerTool(
                    name = ToolName.admit("source_read").refined(),
                    description = ToolDescription.admit("Source input rejection fixture").refined(),
                    loading = ToolLoading.EAGER,
                    input = JsonDomainDefinition(schema, RefinementDefinition { Validation.validated(Unit) }),
                    outputSchema = schema,
                    invoke = { _, _, _ ->
                        invokes++
                        ProviderCall.Completed(Unit)
                    },
                    encode = { json.encodeToJsonElement(EmptyInput.serializer(), EmptyInput) },
                    present = { ToolPresentation.text("fixture", false) },
                    inputRejectionEvidence = ::sourceInputRejectionEvidence,
                )
            val namespace = ProviderNamespace.admit("kast").refined()
            val provider =
                ProviderRegistration.define(namespace, ProviderVersion.admit("1.0.0").refined(), listOf(tool)) {
                        starts++
                        ProviderStartup.Started(Unit)
                    }
                    .validated()
            val broker = Broker.create(listOf(provider), BrokerLimits.defaults()).validated()
            val request =
                BrokerDispatchRequest(
                    ToolAddress(namespace, tool.name),
                    json.encodeToJsonElement(EmptyInput.serializer(), EmptyInput),
                    BrokerInvocationContext.admit("thread", "turn", "call", directory.toRealPath()).refined(),
                )
            val result = broker.dispatch(request) as BrokerDispatch.Rejected
            val failure = result.failure as BrokerFailure.SourceInputRejected
            assertEquals(
                SourceReadFailureDetail.RequestRejected(
                    SourceRequestField(SourceRequestPath.ANCHOR),
                    SourceRequestRule.REQUIRED,
                ),
                failure.cause,
            )
            assertEquals(0, starts)
            assertEquals(0, invokes)
            assertSourceEnvelope(failure)
        }

    @Test
    fun `schema admitted source syntax is refined before invocation`() {
        val input = json.encodeToJsonElement(MalformedSource.serializer(), MalformedSource())
        val schema =
            NetworkntJsonSchemaCompiler.compile(
                    json.encodeToJsonElement(ObjectSchema.serializer(), ObjectSchema()).jsonObject
                )
                .refined()
        val admitted = schema.admit(input).validated()
        val rejection = admitKastInput(CanonicalOperation.SOURCE_READ, admitted) as Validation.Rejected
        val reason = (rejection.failures.first() as KastToolInputFailure.Source).reason
        assertEquals(
            SourceReadFailureDetail.RequestRejected(
                SourceRequestField(SourceRequestPath.ANCHOR_TYPE),
                SourceRequestRule.REQUIRED,
            ),
            reason,
        )
    }

    private fun assertSourceEnvelope(failure: BrokerFailure.SourceInputRejected) {
        val envelope =
            json.encodeToJsonElement(BrokerFailureDocument.serializer(), BrokerFailureDocument.from(failure)).jsonObject
        assertEquals("SOURCE_INPUT_REJECTED", envelope.getValue("failure").jsonPrimitive.content)
        assertEquals(
            "request-rejected",
            envelope.getValue("source").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Serializable private data object EmptyInput

    @Serializable private data class MalformedSource(val anchor: EmptyInput = EmptyInput)

    @Serializable
    private data class RequiredAnchorSchema(val type: String = "object", val required: List<String> = listOf("anchor"))

    @Serializable private data class ObjectSchema(val type: String = "object")

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value

    private fun <T, F> Validation<T, F>.validated(): T = (this as Validation.Validated).value
}
