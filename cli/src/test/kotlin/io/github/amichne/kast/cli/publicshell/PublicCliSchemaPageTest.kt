package io.github.amichne.kast.cli.publicshell

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicCliSchemaPageTest {
    private val schema = discoveryJson.encodeToJsonElement(SchemaFixture()).jsonObject

    @Test
    fun `encoded pages and typed failures match the advertised descriptor output schema`() {
        val authority =
            describePublicCli(
                PublicCliCommand.Describe(
                    PublicToolCatalog.COMPLETE,
                    PublicCliCommandId.DESCRIBE,
                    PublicCliDescribeSection.OUTPUT,
                )
            )
                as PublicCliDescriptor.Schema
        val validator =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(authority.value.content.toString())
        for (result in listOf(page(""), page("/absent"), page("", cursor = "9"))) {
            val encoded = discoveryJson.encodeToString(result)
            assertTrue(validator.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
            val content = Json.parseToJsonElement(encoded).jsonObject.getValue("content").jsonObject
            assertEquals("kast.cli.schema-page.v1", content.getValue("schema").toString().trim('"'))
        }
    }

    @Test
    fun `pages retain exact scalar facts and address containers without copying them`() {
        val root = page("").content as PublicSchemaPageReply.Page
        assertEquals(listOf("/a~1b", "/type", "/empty"), root.entries.map { it.pointer })
        assertEquals(listOf(1, 0, 0), root.entries.map { it.childCount })
        assertEquals(
            listOf(PublicSchemaKind.OBJECT, PublicSchemaKind.STRING, PublicSchemaKind.OBJECT),
            root.entries.map { it.kind },
        )
        assertEquals(null, root.entries[0].value)
        val array = page("/a~1b/~0key").content as PublicSchemaPageReply.Page
        assertEquals(listOf("false", "null", "9223372036854775807"), array.entries.map { it.value.toString() })
        val scalar = page("/a~1b/~0key/2").content as PublicSchemaPageReply.Page
        assertEquals("9223372036854775807", scalar.node.value.toString())
        assertTrue(scalar.complete)
        assertTrue(scalar.entries.isEmpty())
        val exact = PublicPreparedOutput(discoveryJson.encodeToString(page("/a~1b/~0key/2")), PublicShellExit.COMPLETE)
        for (format in CliOutputFormat.entries) {
            assertTrue(presentPublicOutput(exact, format).text.contains("9223372036854775807"))
        }
        assertTrue((page("/empty").content as PublicSchemaPageReply.Page).complete)
    }

    @Test
    fun `cursors page direct children without omission or implicit reference expansion`() {
        val first = page("/a~1b/~0key", maxEntries = "2").content as PublicSchemaPageReply.Page
        assertFalse(first.complete)
        assertEquals(2, first.nextCursor)
        val last = page("/a~1b/~0key", cursor = "2", maxEntries = "2").content as PublicSchemaPageReply.Page
        assertTrue(last.complete)
        assertEquals(null, last.nextCursor)
        assertEquals(
            listOf("/a~1b/~0key/0", "/a~1b/~0key/1", "/a~1b/~0key/2"),
            (first.entries + last.entries).map { it.pointer },
        )
        for (pointer in listOf("/absent", "/a~1b/~0key/01", "/a~1b/~0key/-", "/type/x")) {
            assertEquals(
                PublicSchemaPageFailure.POINTER_NOT_FOUND,
                (page(pointer).content as PublicSchemaPageReply.Rejected).failure,
            )
        }
        assertEquals(
            PublicSchemaPageFailure.CURSOR_OUT_OF_RANGE,
            (page("", cursor = "3").content as PublicSchemaPageReply.Rejected).failure,
        )
    }

    @Test
    fun `byte budget covers UTF8 JSON and TOON including the trailing newline`() {
        val large = Json.encodeToJsonElement(TextFixture("é".repeat(200), "界".repeat(200))).jsonObject
        val command = command("", maxBytes = "1024")
        val result = browsePublicSchema(large, command)
        val page = result.content as PublicSchemaPageReply.Page
        assertEquals(listOf("/first"), page.entries.map { it.pointer })
        assertEquals(1, page.nextCursor)
        val prepared = PublicPreparedOutput(discoveryJson.encodeToString(result), PublicShellExit.COMPLETE)
        for (format in CliOutputFormat.entries) {
            assertTrue(presentPublicOutput(prepared, format).text.toByteArray(Charsets.UTF_8).size + 1 <= 1024)
        }
        val oversized = Json.encodeToJsonElement(SecretFixture("x".repeat(2000))).jsonObject
        for (pointer in listOf("", "/secret")) {
            val rejected =
                browsePublicSchema(oversized, command(pointer, maxBytes = "1024")).content
                    as PublicSchemaPageReply.Rejected
            assertEquals(PublicSchemaPageFailure.ENTRY_TOO_LARGE, rejected.failure)
        }
    }

    @Test
    fun `selection admits only bounded schema browsing and literal JSON pointers`() {
        for (args in
            listOf(
                listOf("--pointer", "bad"),
                listOf("--pointer", "/~2"),
                listOf("--pointer", "", "--cursor", "-1"),
                listOf("--pointer", "", "--max-results", "129"),
                listOf("--pointer", "", "--max-returned-bytes", "1023"),
                listOf("--pointer", "", "--max-returned-bytes", "65537"),
                listOf("--cursor", "0"),
            )) assertTrue(
            parsePublicCliArguments(listOf("describe", "query", "--section", "input") + args) is Refinement.Rejected,
            args.toString(),
        )
        assertTrue(parsePublicCliArguments(listOf("query", "--name", "Order", "--pointer", "")) is Refinement.Rejected)
        assertTrue(
            parsePublicCliArguments(listOf("describe", "query", "--section", "examples", "--pointer", ""))
                is Refinement.Rejected
        )
    }

    private fun page(pointer: String, cursor: String = "0", maxEntries: String = "16", maxBytes: String = "16384") =
        browsePublicSchema(schema, command(pointer, cursor, maxEntries, maxBytes))

    private fun command(pointer: String, cursor: String = "0", maxEntries: String = "16", maxBytes: String = "16384") =
        PublicCliCommand.Describe(
            PublicToolCatalog.COMPLETE,
            PublicCliCommandId.QUERY,
            PublicCliDescribeSection.INPUT,
            (PublicSchemaSelection.parse(pointer, cursor, maxEntries, maxBytes) as Refinement.Refined).value,
        )
}

@Serializable
private data class SchemaFixture(
    @SerialName("a/b") val nested: NestedFixture = NestedFixture(),
    val type: String = "object",
    val empty: EmptyFixture = EmptyFixture(),
)

@Serializable
private data class NestedFixture(
    // Schema array elements are heterogeneous dynamic schema data.
    @SerialName("~key")
    val values: List<JsonElement> = listOf(JsonPrimitive(false), JsonNull, JsonPrimitive(Long.MAX_VALUE))
)

@Serializable private class EmptyFixture

@Serializable private data class TextFixture(val first: String, val second: String)

@Serializable private data class SecretFixture(val secret: String)
