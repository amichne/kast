package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HostedContractDocumentTest {
    private val json = Json
    private val contract =
        HostedContractDocument(
            runtimeProtocolIdentity = "kast.ide-hosted.runtime.v2",
            operationRegistryDigest = "sha256:" + "1".repeat(64),
            wireSchemaDigest = "sha256:" + "2".repeat(64),
            capabilities = listOf("source.read"),
        )

    @Test
    fun `encoded contract retains its required discriminator without serializer defaults`() {
        val encoded = json.encodeToString(HostedContractDocument.serializer(), contract)
        val shape = json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf("type", "runtimeProtocolIdentity", "operationRegistryDigest", "wireSchemaDigest", "capabilities"),
            shape.keys,
        )
        assertEquals("HOSTED_CONTRACT", shape.getValue("type").jsonPrimitive.content)
        assertEquals("kast.ide-hosted.runtime.v2", shape.getValue("runtimeProtocolIdentity").jsonPrimitive.content)
        assertEquals("sha256:" + "1".repeat(64), shape.getValue("operationRegistryDigest").jsonPrimitive.content)
        assertEquals("sha256:" + "2".repeat(64), shape.getValue("wireSchemaDigest").jsonPrimitive.content)
        assertEquals(listOf("source.read"), shape.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `missing discriminator is rejected before compatibility refinement`() {
        val missingType =
            json
                .encodeToString(HostedContractDocument.serializer(), contract)
                .replaceFirst("\"type\":\"HOSTED_CONTRACT\",", "")
        assertThrows(MissingFieldException::class.java) {
            json.decodeFromString(HostedContractDocument.serializer(), missingType)
        }
    }
}
