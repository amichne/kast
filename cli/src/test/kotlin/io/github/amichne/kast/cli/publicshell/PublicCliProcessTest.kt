package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicCliProcessTest {
    @TempDir lateinit var temporary: Path

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
        val file = temporary.resolve("request with spaces.json")
        Files.writeString(file, raw)
        val fromFile = child(listOf("query", "plan", "--request", file.toString()))
        val fromStdin = child(listOf("query", "plan", "--request", "-"), raw.toByteArray())
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
        val (process, stdout, stderr) = start(listOf("describe", "query", "--section", "examples"))
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
        val result = child(listOf("query", "--name", "OrderService", "--fields", "name,name"))
        assertEquals(2, result.first)
        assertEquals(
            "rejected",
            Json.parseToJsonElement(result.second).jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(1, result.second.count { it == '\n' })
    }

    private fun child(args: List<String>, bytes: ByteArray = byteArrayOf()): Pair<Int, String> {
        val (process, stdout, stderr) = start(args)
        try {
            process.outputStream.use { it.write(bytes) }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), Files.readString(stderr))
            return process.exitValue() to Files.readString(stdout)
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }

    private fun start(args: List<String>): Triple<Process, Path, Path> {
        val stdout = Files.createTempFile(temporary, "stdout", ".json")
        val stderr = Files.createTempFile(temporary, "stderr", ".txt")
        val process =
            ProcessBuilder(
                    listOf(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Duser.home=$temporary",
                        "-cp",
                        System.getProperty("java.class.path"),
                        KastPublicCliMain::class.java.name,
                    ) + args
                )
                .directory(temporary.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .apply { environment().clear() }
                .start()
        return Triple(process, stdout, stderr)
    }
}
