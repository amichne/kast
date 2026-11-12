package io.github.amichne.kast.cli.publicshell

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import dev.toonformat.jtoon.JToon
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicCliProcessTest {
    @TempDir lateinit var temporary: Path

    private val children
        get() = PublicCliProcessFixture(temporary)

    @Test
    fun `session hook reports a skipped non project in model context`() {
        val result =
            children.child(
                listOf("hook", "session-start", "--workspace", temporary.toString()),
                environment = emptyMap(),
            )
        assertEquals(0, result.first)
        assertEquals(setOf("additionalContext"), Json.parseToJsonElement(result.second).jsonObject.keys)
        val toon =
            children.child(
                listOf("hook", "session-start", "--workspace", temporary.toString()),
                environment = mapOf("KAST_OUTPUT_TOON" to "1"),
            )
        assertEquals(result, toon)
    }

    @Test
    fun `session hook does not require an installation or grant workspace trust`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "")
        val result = children.child(listOf("hook", "session-start", "--workspace", temporary.toString()))
        assertEquals(0, result.first)
        val document = Json.parseToJsonElement(result.second).jsonObject
        assertEquals(setOf("additionalContext"), document.keys)
        assertTrue(
            document.getValue("additionalContext").jsonPrimitive.content.contains("do not grant trust or start a Host")
        )
        assertFalse(Files.exists(temporary.resolve(".local/share/kast-workspaces")))
    }

    @Test
    fun `TOON switch preserves plans descriptors and rejected results with their exit codes`() {
        for (args in
            listOf(
                listOf("query", "plan", "--name", "OrderService"),
                listOf("describe", "query", "--section", "examples"),
                listOf("query", "--name", "OrderService", "--fields", "name,name"),
                listOf("source", "read"),
                listOf("result", "read"),
            )) {
            val standard = children.child(args)
            val toon = children.child(args, environment = mapOf("KAST_OUTPUT_TOON" to "1"))
            assertEquals(standard.first, toon.first, args.toString())
            assertFalse(toon.second.trimStart().startsWith("{"), args.toString())
            assertEquals(
                Json.parseToJsonElement(standard.second),
                Json.parseToJsonElement(JToon.decodeToJson(toon.second)),
            )
        }
    }

    @Test
    fun `unset empty and zero select JSON while explicit TOON remains available`() {
        val args = listOf("query", "plan", "--name", "OrderService")
        val standard = children.child(args, environment = mapOf("KAST_OUTPUT_TOON" to "0"))
        assertEquals(
            "planned",
            Json.parseToJsonElement(standard.second).jsonObject.getValue("type").jsonPrimitive.content,
        )
        for (environment in listOf(emptyMap(), mapOf("KAST_OUTPUT_TOON" to ""))) {
            val actual = children.child(args, environment = environment)
            assertEquals(standard, actual)
        }
        val toon = children.child(args, environment = mapOf("KAST_OUTPUT_TOON" to "1"))
        assertEquals(standard.first, toon.first)
        assertEquals(Json.parseToJsonElement(standard.second), Json.parseToJsonElement(JToon.decodeToJson(toon.second)))
        val invalid = children.child(args, environment = mapOf("KAST_OUTPUT_TOON" to "typo"))
        assertEquals(2, invalid.first)
        assertEquals(
            "INVALID_OUTPUT_FORMAT",
            Json.parseToJsonElement(invalid.second).jsonObject.getValue("failure").jsonPrimitive.content,
        )
    }

    @Test
    fun `real plan output descriptor validates COMPLETE plans without installation`() {
        assertPlanOutputDescriptor(PublicToolCatalog.COMPLETE)
    }

    @Test
    fun `real plan output descriptor validates COMMON plans without installation`() {
        assertPlanOutputDescriptor(PublicToolCatalog.COMMON)
    }

    private fun assertPlanOutputDescriptor(catalog: PublicToolCatalog) {
        val (process, stdout, stderr) =
            children.start(
                listOf("describe", "query.plan", "--section", "output", "--catalog", catalog.name.lowercase())
            )
        val text =
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Plan metadata waited for stdin EOF")
                assertEquals(0, process.exitValue(), Files.readString(stderr))
                Files.readString(stdout)
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        assertEquals(1, text.count { it == '\n' })
        val descriptor = Json.parseToJsonElement(text).jsonObject
        assertEquals(catalog.name, descriptor.getValue("catalog").jsonPrimitive.content)
        val validator =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(descriptor.getValue("content").toString())
        val plan =
            children.child(listOf("query", "plan", "--name", "OrderService", "--catalog", catalog.name.lowercase()))
        assertEquals(0, plan.first)
        assertTrue(validator.validate(plan.second, InputFormat.JSON).isEmpty())
        assertFalse(
            validator.validate(plan.second.replace("REQUEST_SHAPE_ONLY", "UNKNOWN_BASIS"), InputFormat.JSON).isEmpty()
        )
    }

    @Test
    fun `metadata and valid planning never compose an installation or read unintended stdin`() {
        val forbiddenInput =
            object : InputStream() {
                override fun read(): Int = error("Unrequested stdin read")
            }
        for (args in
            listOf(
                listOf("query", "--help"),
                listOf("describe"),
                listOf("describe", "query", "--section", "input"),
                listOf("query", "plan", "--name", "OrderService"),
            )) {
            val result =
                preparePublicCli(
                    args,
                    forbiddenInput,
                    temporary,
                    session = { _, _ -> error("Metadata host composition") },
                )
            assertEquals(PublicShellExit.COMPLETE, result.exit, args.toString())
        }
    }

    @Test
    fun `invalid bytes and invalid admission never compose a host`() {
        val malformedDocuments =
            listOf(
                byteArrayOf(),
                byteArrayOf(0xc3.toByte(), 0x28),
                "{} {}".toByteArray(),
                PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                    .examples
                    .getValue("runByName")
                    .value
                    .toString()
                    .replace("\"RUN\"", "\"MISSING\"")
                    .toByteArray(),
            )
        for (bytes in malformedDocuments) {
            val result =
                preparePublicCli(
                    listOf("query", "--request", "-"),
                    ByteArrayInputStream(bytes),
                    temporary,
                    session = { _, _ -> error("Rejected input reached host") },
                )
            assertEquals(PublicShellExit.INPUT, result.exit)
        }
        val result =
            preparePublicCli(
                listOf("query", "--name", "OrderService", "--max-returned-bytes", "4095"),
                ByteArrayInputStream(byteArrayOf()),
                temporary,
                session = { _, _ -> error("Rejected allowance reached host") },
            )
        assertEquals(PublicShellExit.INPUT, result.exit)
    }

    @Test
    fun `invalid workspace path rejects before installation composition`() {
        val result =
            preparePublicCli(
                listOf("query", "--name", "OrderService", "--workspace", "invalid\u0000path"),
                ByteArrayInputStream(byteArrayOf()),
                temporary,
                session = { _, _ -> error("Invalid workspace reached host") },
            )
        assertEquals(PublicShellExit.INPUT, result.exit)
    }

    @Test
    fun `file and stdin plans have identical admission and no native effects`() {
        val raw =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .examples
                .getValue("runByName")
                .value
                .toString()
        val file = temporary.resolve("{request with spaces}.json")
        Files.writeString(file, raw)
        val fromFile = children.child(listOf("query", "plan", "--request", file.toString()))
        val fromStdin = children.child(listOf("query", "plan", "--request", "-"), raw.toByteArray())
        assertEquals(0, fromFile.first)
        assertEquals(fromFile, fromStdin)
        assertEquals(
            "planned",
            Json.parseToJsonElement(fromFile.second.trim()).jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertTrue(fromFile.second.endsWith("\n"))
        assertEquals(1, fromFile.second.count { it == '\n' })
    }

    @Test
    fun `real metadata child does not wait for stdin EOF`() {
        val (process, stdout, stderr) = children.start(listOf("describe", "query", "--section", "examples"))
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Metadata child waited for stdin")
            assertEquals(0, process.exitValue(), Files.readString(stderr))
            val text = Files.readString(stdout)
            assertEquals(
                "kast.cli.describe.v1",
                Json.parseToJsonElement(text).jsonObject.getValue("schema").jsonPrimitive.content,
            )
            assertEquals(1, text.count { it == '\n' })
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `real invalid child emits one JSON document with nonzero status`() {
        val result = children.child(listOf("query", "--name", "OrderService", "--fields", "name,name"))
        assertEquals(2, result.first)
        assertEquals(
            "rejected",
            Json.parseToJsonElement(result.second).jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(1, result.second.count { it == '\n' })
    }
}
