package io.github.amichne.kast.cli.publicshell

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.rpc.QueryDeliveryFixtures
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_CONTRACT_VERSION
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicCliDiscoveryTest {
    @Test
    fun `inventory has fixed contract fields and the supported commands`() {
        val result = document(null, PublicCliDescribeSection.INVENTORY)
        assertEquals(setOf("schema", "catalog", "public_contract_version", "section", "content"), result.keys)
        assertEquals("kast.cli.describe.v1", result.getValue("schema").jsonPrimitive.content)
        assertEquals(
            PUBLIC_TOOL_CONTRACT_VERSION.toString(),
            result.getValue("public_contract_version").jsonPrimitive.content,
        )
        assertEquals("COMPLETE", result.getValue("catalog").jsonPrimitive.content)
        assertEquals(
            listOf("query", "query.plan", "result.read", "result.resume", "source.read", "describe"),
            result.getValue("content").jsonArray.map { it.jsonObject.getValue("command").jsonPrimitive.content },
        )
    }

    @Test
    fun `summary flags share parser inventory and preserve catalog restrictions`() {
        for (catalog in PublicToolCatalog.entries) for (command in PublicCliCommandId.entries) {
            val result = document(command, PublicCliDescribeSection.SUMMARY, catalog)
            assertEquals(command.id, result.getValue("command").jsonPrimitive.content)
            val summary = result.getValue("content").jsonObject
            assertEquals(
                flagsFor(command).map { it.spelling },
                summary.getValue("flags").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            )
            assertTrue(summary.getValue("catalog_restriction").jsonPrimitive.content.startsWith(catalog.name))
            if (command == PublicCliCommandId.DESCRIBE)
                assertFalse(
                    summary.getValue("flags").jsonArray.any {
                        it.jsonObject.getValue("name").jsonPrimitive.content == "--workspace"
                    }
                )
        }
    }

    @Test
    fun `packaged examples admit with the same catalog and misspelled examples reject`() {
        for (catalog in PublicToolCatalog.entries) for (command in
            PublicCliCommandId.entries.filter { it != PublicCliCommandId.DESCRIBE }) {
            val examples = document(command, PublicCliDescribeSection.EXAMPLES, catalog).getValue("content").jsonArray
            for (example in examples) {
                val value = example.jsonObject.getValue("value")
                assertTrue(
                    PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, value, catalog) is Refinement.Refined,
                    "$catalog $command $example",
                )
            }
        }
        val malformed =
            Json.parseToJsonElement(
                PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                    .examples
                    .getValue("runByName")
                    .value
                    .toString()
                    .replace("\"RUN\"", "\"RNU\"")
            )
        assertTrue(PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, malformed) is Refinement.Rejected)
    }

    @Test
    fun `advertised output schema admits real canonical fixture envelopes and rejects unknown outcomes`() {
        val schema = document(PublicCliCommandId.QUERY, PublicCliDescribeSection.OUTPUT).getValue("content").jsonObject
        val validator =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema.toString())
        val fixtures = Json.parseToJsonElement(QueryDeliveryFixtures.serialized()).jsonObject
        for ((name, fixture) in fixtures) assertTrue(
            validator.validate(fixture.toString(), InputFormat.JSON).isEmpty(),
            name,
        )
        val valid = fixtures.values.first().toString()
        val type = fixtures.values.first().jsonObject.getValue("type").jsonPrimitive.content
        val malformed = valid.replaceFirst("\"$type\"", "\"unrecognized\"")
        assertFalse(validator.validate(malformed, InputFormat.JSON).isEmpty())
    }

    @Test
    fun `query input schema equals selected packaged authority`() {
        for (catalog in PublicToolCatalog.entries) assertEquals(
            PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS, catalog),
            document(PublicCliCommandId.QUERY, PublicCliDescribeSection.INPUT, catalog).getValue("content"),
        )
    }

    @Test
    fun `alias descriptors restrict the action while preserving packaged definitions`() {
        val examples = PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS).examples.values
        val run =
            examples
                .first {
                    it.value.jsonObject.getValue("request").jsonObject.getValue("type").jsonPrimitive.content == "RUN"
                }
                .value
        val read =
            examples
                .first {
                    it.value.jsonObject.getValue("request").jsonObject.getValue("type").jsonPrimitive.content ==
                        "READ_RESULT"
                }
                .value
        val schema = document(PublicCliCommandId.RESULT_READ, PublicCliDescribeSection.INPUT).getValue("content")
        val validator =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema.toString())
        assertTrue(validator.validate(read.toString(), InputFormat.JSON).isEmpty())
        assertFalse(validator.validate(run.toString(), InputFormat.JSON).isEmpty())
    }
}

private fun document(
    command: PublicCliCommandId?,
    section: PublicCliDescribeSection,
    catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
) =
    Json.parseToJsonElement(
            encodePublicCliDescriptor(describePublicCli(PublicCliCommand.Describe(catalog, command, section)))
        )
        .jsonObject
