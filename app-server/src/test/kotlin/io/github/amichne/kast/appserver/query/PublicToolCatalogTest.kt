package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicToolCatalogTest {
    @Test
    fun `generated public catalogs publish replace body and no retired tool`() {
        val expected = listOf("query_symbols", "check_diagnostics", "add_declaration", "replace_body")
        assertEquals(expected, PublicToolIdentity.entries.map { it.toolName })
        val hosted =
            requireNotNull(
                    javaClass.getResourceAsStream("/io/github/amichne/kast/appserver/query/tools.app-server.json")
                )
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
                .getValue("tools")
                .jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(expected, hosted)
        val responses =
            requireNotNull(
                    javaClass.getResourceAsStream("/io/github/amichne/kast/appserver/query/tools.responses.json")
                )
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonArray }
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(expected.map { "kast_$it" }, responses)
        assertEquals(
            listOf("workspace_lifecycle") + expected,
            CanonicalAgentToolDefinitions.all.map { it.name.value },
        )
        for (retired in listOf("read_source", "validate_workspace")) {
            assertTrue(CanonicalAgentToolDefinitions.resolveInput(retired) is Refinement.Rejected)
        }
    }
}
