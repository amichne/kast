package io.github.amichne.kast.cli.publicshell

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LibraryLoggingProcessTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `schema library diagnostics do not enter normal process output`() {
        val stdout = directory.resolve("stdout")
        val stderr = directory.resolve("stderr")
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    SchemaLoggingProbe::class.java.name,
                )
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .apply { environment().clear() }
                .start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), Files.readString(stderr))
            val reply = Files.readString(stdout)
            assertEquals(1, reply.count { it == '\n' })
            assertEquals(setOf("outcome"), Json.parseToJsonElement(reply).jsonObject.keys)
            assertEquals("READY", Json.parseToJsonElement(reply).jsonObject.getValue("outcome").jsonPrimitive.content)
            assertEquals("", Files.readString(stderr))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}

@Serializable private data class SchemaLoggingReady(val outcome: String)

object SchemaLoggingProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val schema = checkNotNull(javaClass.getResource("/unknown-hosted-keyword.schema.json")).readText()
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema).initializeValidators()
        println(Json.encodeToString(SchemaLoggingReady("READY")))
    }
}
