package io.github.amichne.kast.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

class PackagedSkillJsonContractTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `installed skill uses resolve-kast and native commands for json literal and file inputs`() {
        val installedSkillDir = tempDir.resolve("skills")
        InstallSkillService(
            embeddedSkillResources = EmbeddedSkillResources(version = "test"),
        ).install(
            InstallSkillOptions(
                targetDir = installedSkillDir,
                name = "kast",
                force = true,
            ),
        )

        val workspaceRoot = tempDir.resolve("workspace")
        val sourceFile = workspaceRoot
            .resolve("src/main/kotlin/sample/Greeter.kt")
            .createDirectoriesForParent()
        sourceFile.writeText(
            """
            package sample

            fun greet(name: String): String = "hi ${'$'}name"
            """.trimIndent() + "\n",
        )

        val skillRoot = installedSkillDir.resolve("kast")
        val kastResolver = skillRoot.resolve("scripts/resolve-kast.sh")
        val kastWrapper = checkNotNull(System.getProperty("kast.wrapper")) {
            "kast.wrapper system property is missing"
        }
        val runtimeLibs = checkNotNull(System.getProperty("kast.runtime-libs")) {
            "kast.runtime-libs system property is missing"
        }
        val configHome = tempDir.resolve("kast-config")
        val wrapperEnv = mapOf(
            "KAST_CLI_PATH" to kastWrapper,
            "KAST_RUNTIME_LIBS" to runtimeLibs,
            "KAST_CONFIG_HOME" to configHome.toString(),
            "KAST_WORKSPACE_ROOT" to workspaceRoot.toString(),
        )

        val resolvedKast = runCommand(
            command = listOf("bash", kastResolver.toString()),
            env = wrapperEnv,
        )
        assertEquals(0, resolvedKast.exitCode, "stderr: ${resolvedKast.stderr}")
        assertTrue(resolvedKast.stderr.isBlank())
        val kastBinary = resolvedKast.stdout

        val resolveRequest = buildJsonObject {
            put("workspaceRoot", workspaceRoot.toString())
            put("symbol", "greet")
            put("fileHint", sourceFile.toString())
        }
        val resolveResult = runCommand(
            command = listOf(
                kastBinary,
                "skill",
                "resolve",
                defaultCliJson().encodeToString(JsonObject.serializer(), resolveRequest),
            ),
            env = wrapperEnv,
        )
        assertEquals(0, resolveResult.exitCode, "stderr: ${resolveResult.stderr}")
        val resolvedPayload = defaultCliJson()
            .parseToJsonElement(resolveResult.stdout)
            .jsonObject
        assertEquals(true, resolvedPayload["ok"]?.toString()?.toBooleanStrictOrNull())
        assertEquals("RESOLVE_SUCCESS", resolvedPayload["type"]?.jsonPrimitive?.content)
        assertTrue(resolveResult.stderr.isBlank())

        val diagnosticsRequestFile = tempDir.resolve("diagnostics-request.json")
        val diagnosticsRequest = buildJsonObject {
            put("workspaceRoot", workspaceRoot.toString())
            put(
                "filePaths",
                buildJsonArray {
                    add(JsonPrimitive(sourceFile.toString()))
                },
            )
        }
        diagnosticsRequestFile.writeText(
            defaultCliJson().encodeToString(JsonObject.serializer(), diagnosticsRequest),
        )

        val diagnosticsResult = runCommand(
            command = listOf(
                kastBinary,
                "skill",
                "diagnostics",
                diagnosticsRequestFile.toString(),
            ),
            env = wrapperEnv,
        )
        assertEquals(0, diagnosticsResult.exitCode, "stderr: ${diagnosticsResult.stderr}")
        val diagnosticsPayload = defaultCliJson()
            .parseToJsonElement(diagnosticsResult.stdout)
            .jsonObject
        assertEquals(true, diagnosticsPayload["ok"]?.toString()?.toBooleanStrictOrNull())
        assertEquals("DIAGNOSTICS_SUCCESS", diagnosticsPayload["type"]?.jsonPrimitive?.content)
    }

    private fun runCommand(
        command: List<String>,
        env: Map<String, String>,
    ): CommandResult {
        val process = ProcessBuilder(command)
            .directory(Path.of("").toAbsolutePath().toFile())
            .also { pb -> env.forEach { (key, value) -> pb.environment()[key] = value } }
            .start()
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        check(finished) { "command timed out: ${command.joinToString(" ")}" }
        return CommandResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim(),
            stderr = process.errorStream.readAllBytes().toString(Charsets.UTF_8).trim(),
        )
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun Path.createDirectoriesForParent(): Path {
        Files.createDirectories(checkNotNull(parent))
        return this
    }
}
