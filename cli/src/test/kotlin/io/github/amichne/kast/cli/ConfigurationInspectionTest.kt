package io.github.amichne.kast.cli

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

class ConfigurationInspectionTest {
    @Test
    fun `schema remains passive when saved configuration and heap are malformed`(@TempDir root: Path) {
        val result = launch(root, listOf("config", "schema", "--json"), mapOf(
            "KAST_SAVED_CONFIGURATION_FAILURE" to "duplicate-record", "KAST_INDEXER_MAX_HEAP" to "",
        ))
        assertEquals(0, result.code, result.error)
        val document = Json.parseToJsonElement(result.output).jsonObject
        assertEquals("config-schema", document.getValue("operation").jsonPrimitive.content)
        assertTrue(result.output.contains("KAST_INDEXER_MAX_HEAP"))
        assertEquals(InstalledConfigurationSchema.encoded, result.output)
        assertFalse(Files.exists(root.resolve("runtime")))
        assertFalse(Files.exists(root.resolve("cache")))
    }

    @Test
    fun `show reports unknown setting as bounded rejection without echoing its value`(@TempDir root: Path) {
        val result = launch(root, listOf("config", "show", "--json"), mapOf("KAST_UNKNOWN_FIXTURE" to "secret-canary"))
        assertEquals(0, result.code, result.error)
        val document = Json.parseToJsonElement(result.output).jsonObject
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals("UNKNOWN_KEY", document.getValue("reason").jsonPrimitive.content)
        assertFalse(result.output.contains("secret-canary"))
    }

    @Test
    fun `explain reports normalized heap and winning process provenance`(@TempDir root: Path) {
        val result = launch(root, listOf("config", "explain", "KAST_INDEXER_MAX_HEAP"), mapOf("KAST_INDEXER_MAX_HEAP" to "8g"))
        assertEquals(0, result.code, result.error)
        val document = Json.parseToJsonElement(result.output).jsonObject
        assertEquals("PROCESS_ENVIRONMENT", document.getValue("source").jsonPrimitive.content)
        assertEquals("8192m", document.getValue("value").jsonPrimitive.content)
        assertEquals("NEXT_WORKER_LAUNCH", document.getValue("applicationBoundary").jsonPrimitive.content)
        assertEquals("unobserved", document.getValue("applied").jsonPrimitive.content)
    }

    @Test
    fun `saved literal configuration retains provenance below explicit process values`(@TempDir root: Path) {
        val saved = root.toRealPath().resolve("environment")
        Files.writeString(saved, "# Literal values, never shell execution\nKAST_INDEXER_MAX_HEAP=8g\n")
        val selected = mapOf("KAST_CONFIGURATION_FILE" to saved.toString())
        val fromSaved = launch(root, listOf("config", "explain", "KAST_INDEXER_MAX_HEAP"), selected)
        assertEquals(0, fromSaved.code, fromSaved.error)
        val savedDocument = Json.parseToJsonElement(fromSaved.output).jsonObject
        assertEquals("SAVED_INSTALLATION", savedDocument.getValue("source").jsonPrimitive.content)
        assertEquals("8192m", savedDocument.getValue("value").jsonPrimitive.content)
        val overridden = launch(root, listOf("config", "explain", "KAST_INDEXER_MAX_HEAP"), selected + ("KAST_INDEXER_MAX_HEAP" to "4g"))
        val overriddenDocument = Json.parseToJsonElement(overridden.output).jsonObject
        assertEquals("PROCESS_ENVIRONMENT", overriddenDocument.getValue("source").jsonPrimitive.content)
        assertEquals("4096m", overriddenDocument.getValue("value").jsonPrimitive.content)
        assertTrue(overridden.output.contains("SAVED_INSTALLATION"))
    }

    @Test
    fun `validate admits a literal file without starting runtime composition`(@TempDir root: Path) {
        val saved = root.toRealPath().resolve("environment")
        Files.writeString(saved, "KAST_INDEXER_MAX_HEAP=8g\nKAST_ENABLE_LAUNCHD=0\n")
        val result = launch(root, listOf("config", "validate", "--file", saved.toString(), "--json"), emptyMap())
        assertEquals(0, result.code, result.error)
        val document = Json.parseToJsonElement(result.output).jsonObject
        assertEquals("config-validate", document.getValue("operation").jsonPrimitive.content)
        assertEquals("complete", document.getValue("status").jsonPrimitive.content)
        assertFalse(Files.exists(root.resolve("runtime")))
        assertFalse(Files.exists(root.resolve("cache")))
    }

    @Test
    fun `validate rejects duplicate saved assignments even when the environment overrides them`(@TempDir root: Path) {
        val saved = root.toRealPath().resolve("environment")
        Files.writeString(saved, "KAST_INDEXER_MAX_HEAP=8g\nKAST_INDEXER_MAX_HEAP=4g\n")
        val result = launch(root, listOf("config", "validate", "--file", saved.toString()), mapOf("KAST_INDEXER_MAX_HEAP" to "1g"))
        assertTrue(result.code != 0, "invalid saved configuration unexpectedly validated")
        val document = Json.parseToJsonElement(result.error).jsonObject
        assertEquals("DUPLICATE_ASSIGNMENT", document.getValue("reason").jsonPrimitive.content)
        assertFalse(Files.exists(root.resolve("runtime")))
    }

    @Test
    fun `validate rejects unknown owner tool selection through the canonical parser`(@TempDir root: Path) {
        val saved = Files.writeString(root.toRealPath().resolve("environment"), "KAST_APP_SERVER_TOOLS=unknown-tool\n")
        val result = launch(root, listOf("config", "validate", "--file", saved.toString()), emptyMap())
        assertTrue(result.code != 0, "unknown tool selection unexpectedly validated")
        assertTrue(result.error.contains("INVALID_TOOL_SELECTION"), result.error)
    }

    @Test
    fun `workspace inspection resolves persisted overlay with source precedence`(@TempDir root: Path) {
        val physical = root.toRealPath()
        val workspace = Files.createDirectory(physical.resolve("workspace"))
        val saved = Files.writeString(Files.createDirectories(physical.resolve("config")).resolve("environment"), "KAST_INDEXER_MAX_HEAP=4g\n")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(workspace.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        Files.writeString(Files.createDirectories(physical.resolve("config/workspaces/$digest")).resolve("environment"), "KAST_INDEXER_MAX_HEAP=8g\n")
        val selected = mapOf("KAST_CONFIGURATION_FILE" to saved.toString())
        val result = launch(root, listOf("config", "explain", "KAST_INDEXER_MAX_HEAP", "--workspace", workspace.toString()), selected)
        val document = Json.parseToJsonElement(result.output).jsonObject
        assertEquals("complete", document.getValue("status").jsonPrimitive.content, result.output)
        assertEquals("SAVED_WORKSPACE", document.getValue("source").jsonPrimitive.content)
        assertEquals("8192m", document.getValue("value").jsonPrimitive.content)
        assertTrue(result.output.contains("SAVED_INSTALLATION"))
        val explicit = launch(root, listOf("config", "explain", "KAST_INDEXER_MAX_HEAP", "--workspace", workspace.toString()), selected + ("KAST_INDEXER_MAX_HEAP" to "2g"))
        assertEquals("PROCESS_ENVIRONMENT", Json.parseToJsonElement(explicit.output).jsonObject.getValue("source").jsonPrimitive.content)
        assertTrue(explicit.output.contains("SAVED_WORKSPACE"))
        assertFalse(Files.exists(physical.resolve("state")))
    }

    @Test
    fun `workspace inspection rejects invalid shadowed saved overlay`(@TempDir root: Path) {
        val physical = root.toRealPath()
        val workspace = Files.createDirectory(physical.resolve("workspace"))
        val saved = Files.writeString(Files.createDirectories(physical.resolve("config")).resolve("environment"), "KAST_INDEXER_MAX_HEAP=4g\n")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(workspace.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        Files.writeString(Files.createDirectories(physical.resolve("config/workspaces/$digest")).resolve("environment"), "KAST_INDEXER_MAX_HEAP=bad-secret\n")
        val result = launch(root, listOf("config", "show", "--workspace", workspace.toString(), "--json"), mapOf("KAST_CONFIGURATION_FILE" to saved.toString(), "KAST_INDEXER_MAX_HEAP" to "2g"))
        assertEquals("INVALID_HEAP", Json.parseToJsonElement(result.output).jsonObject.getValue("reason").jsonPrimitive.content, result.output)
        assertFalse(result.output.contains("bad-secret"))
    }

    @Test
    fun `workspace inspection without overlay stays passive and does not create one`(@TempDir root: Path) {
        val physical = root.toRealPath()
        val workspace = Files.createDirectory(physical.resolve("workspace"))
        val saved = Files.writeString(Files.createDirectories(physical.resolve("config")).resolve("environment"), "KAST_INDEXER_MAX_HEAP=4g\n")
        val result = launch(root, listOf("config", "show", "--workspace", workspace.toString(), "--json"), mapOf("KAST_CONFIGURATION_FILE" to saved.toString()))
        assertEquals("complete", Json.parseToJsonElement(result.output).jsonObject.getValue("status").jsonPrimitive.content, result.output)
        assertTrue(result.output.contains("SAVED_INSTALLATION"))
        assertFalse(Files.exists(physical.resolve("config/workspaces")))
        assertFalse(Files.exists(physical.resolve("state")))
    }

    @Test
    fun `schema declares derived identities and operational limits with ranges and units`(@TempDir root: Path) {
        val result = launch(root, listOf("config", "schema", "--json"), emptyMap())
        val document = Json.parseToJsonElement(result.output).jsonObject
        assertTrue(result.output.contains("kast.bootstrap.attempt.id"), result.output)
        assertTrue(result.output.contains("BROKER_SERVICE_IDENTITY"))
        assertTrue(result.output.contains("operation.graph_build.invocation"))
        assertTrue(result.output.contains("indexer.frame.maximum_bytes"))
        assertTrue(result.output.contains("admittedRange"))
        assertTrue(result.output.contains("MILLISECONDS"))
        assertTrue(document.containsKey("operationalLimits"))
    }

    @Test
    fun `validate rejects unknown saved tool even when process selection shadows it`(@TempDir root: Path) {
        val saved = Files.writeString(root.toRealPath().resolve("environment"), "KAST_APP_SERVER_TOOLS=unknown-tool\n")
        val result = launch(root, listOf("config", "validate", "--file", saved.toString()), mapOf("KAST_APP_SERVER_TOOLS" to "query"))
        assertTrue(result.code != 0, "invalid shadowed owner setting was accepted")
        assertTrue(result.error.contains("INVALID_TOOL_SELECTION"), result.error)
    }

    private fun launch(root: Path, args: List<String>, inputs: Map<String, String>): Result {
        val output = root.resolve("stdout")
        val error = root.resolve("stderr")
        val process = ProcessBuilder(listOf(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Duser.home=$root", "-Djava.io.tmpdir=$root", "-cp", System.getProperty("java.class.path"),
            "io.github.amichne.kast.cli.KastCliMainKt",
        ) + args).directory(root.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile()).apply {
            environment().clear()
            environment().putAll(mapOf("HOME" to root.toString(), "KAST_RUNTIME_DIRECTORY" to root.resolve("runtime").toString(),
                "KAST_CACHE_ROOT" to root.resolve("cache").toString()) + inputs)
        }.start()
        return try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            Result(process.exitValue(), Files.readString(output), Files.readString(error))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
    private data class Result(val code: Int, val output: String, val error: String)
}
