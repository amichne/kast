package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolDescription
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.schema.JsonSchemaViolationEvidence
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BrokerFailureDocumentTest {
    private val address =
        ToolAddress(
            ProviderNamespace.admit("kast").refined(),
            ToolName.admit("query_symbols").refined(),
        )

    @Test
    fun `plain rejection DTO preserves the existing wire shape without empty fields`() {
        val document = BrokerFailureDocument.from(BrokerFailure.UnknownTool(address))
        assertEquals("""{"failure":"UNKNOWN_TOOL"}""", Json.encodeToString(document))
    }

    @Test
    fun `argument corrections retain their existing field through DTO serialization`() {
        val document =
            BrokerFailureDocument.from(
                BrokerFailure.InvalidArguments(
                    address,
                    1,
                    listOf(ToolDescription.admit("Choose one exact selector.").refined()),
                )
            )
        assertEquals(
            """{"failure":"INVALID_ARGUMENTS","corrections":["Choose one exact selector."]}""",
            Json.encodeToString(document),
        )
    }

    @Test
    fun `output rejection DTO includes only the closed diagnostic evidence`() {
        val schema =
            NetworkntJsonSchemaCompiler.compile(
                    Json.parseToJsonElement("""{"type":"object","properties":{"identity":{"type":"integer"}}}""")
                        .jsonObject
                )
                .refined()
        val rejected = schema.admit(Json.parseToJsonElement("""{"identity":"private-source"}""")) as Validation.Rejected
        val failure =
            BrokerFailure.OutputContractRejected(
                address,
                rejected.failures.size,
                JsonSchemaViolationEvidence.from(rejected.failures),
                io.github.amichne.kast.appserver.core.BrokerOperationEffect.Unknown,
            )
        val encoded = Json.encodeToString(BrokerFailureDocument.from(failure))
        val expected =
            """
            {"failure":"OUTPUT_CONTRACT_REJECTED",
             "outputViolationEvidence":{"observations":[{"keyword":"TYPE","field":"IDENTITY"}]}}
            """
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(encoded))
        assertFalse(encoded.contains("private-source"))
    }

    @Test
    fun `source ingress rejection retains typed origin and field in actual broker document`() {
        val cause =
            io.github.amichne.kast.protocol.contract.SourceReadFailureDetail.RequestRejected(
                io.github.amichne.kast.protocol.contract.SourceRequestField(
                    io.github.amichne.kast.protocol.contract.SourceRequestPath.ANCHOR_TYPE
                ),
                io.github.amichne.kast.protocol.contract.SourceRequestRule.REQUIRED,
            )
        val encoded = Json.encodeToString(BrokerFailureDocument.from(BrokerFailure.SourceInputRejected(cause)))
        val output = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("failure", "source"), output.keys)
        assertEquals(
            "SOURCE_INPUT_REJECTED",
            (output.getValue("failure") as kotlinx.serialization.json.JsonPrimitive).content,
        )
        val source = output.getValue("source").jsonObject
        assertEquals(setOf("type", "field", "reason", "expected"), source.keys)
        assertEquals("request-rejected", (source.getValue("type") as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(
            "anchor.type",
            (source.getValue("field").jsonObject.getValue("path") as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals("required", (source.getValue("reason") as kotlinx.serialization.json.JsonPrimitive).content)
    }

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
