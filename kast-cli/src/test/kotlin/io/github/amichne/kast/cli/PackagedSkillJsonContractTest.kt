package io.github.amichne.kast.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `installed skill drives native commands for json literal and file inputs`() {
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

        val kastBinary = checkNotNull(System.getProperty("kast.wrapper")) {
            "kast.wrapper system property is missing"
        }
        val configHome = tempDir.resolve("kast-config")
        val wrapperEnv = mapOf(
            "KAST_CLI_PATH" to kastBinary,
            "KAST_CONFIG_HOME" to configHome.toString(),
            "KAST_WORKSPACE_ROOT" to workspaceRoot.toString(),
        )

        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/scripts/resolve-kast.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/scripts/kast-session-start.sh")))
        assertTrue(
            Files.isRegularFile(
                installedSkillDir.resolve("kast/fixtures/maintenance/scripts/build-routing-corpus.py"),
            ),
        )
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/fixtures/maintenance/evals/routing.json")))
        assertTrue(
            Files.isRegularFile(
                installedSkillDir.resolve("kast/fixtures/maintenance/references/routing-improvement.md"),
            ),
        )

        val daemon = startRealBackend(workspaceRoot, wrapperEnv)
        try {
        val resolveScriptResult = runCommand(
            command = listOf(
                "bash",
                installedSkillDir.resolve("kast/scripts/resolve-kast.sh").toString(),
            ),
            env = wrapperEnv,
        )
        assertEquals(0, resolveScriptResult.exitCode, "stderr: ${resolveScriptResult.stderr}")
        assertTrue(resolveScriptResult.stdout.contains("kast-cli"))
        assertFalse(resolveScriptResult.stdout.contains(" "))

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
        } finally {
            runCommand(
                command = listOf(kastBinary, "workspace", "stop", "--workspace-root=$workspaceRoot"),
                env = wrapperEnv,
            )
            daemon.destroyForcibly()
        }
    }

    private fun startRealBackend(
        workspace: Path,
        env: Map<String, String>,
        timeoutMillis: Long = 120_000,
    ): Process {
        val runtimeLibs = checkNotNull(System.getProperty("kast.runtime-libs")) {
            "kast.runtime-libs system property is missing"
        }
        val classpathFile = java.io.File(runtimeLibs, "classpath.txt")
        val classpath = classpathFile.readLines()
            .filter { it.isNotBlank() }
            .joinToString(":") { "$runtimeLibs/$it" }
        val command = buildList {
            add("java"); add("-cp"); add(classpath)
            add("io.github.amichne.kast.standalone.StandaloneMainKt")
            add("--workspace-root=$workspace")
        }
        Files.createDirectories(workspace)
        val process = ProcessBuilder(command)
            .directory(workspace.toFile())
            .also { pb -> env.forEach { (k, v) -> pb.environment()[k] = v } }
            .start()
        val kastBinary = checkNotNull(env["KAST_CLI_PATH"]) { "KAST_CLI_PATH missing from env" }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            val statusResult = runCatching {
                runCommand(
                    command = listOf(kastBinary, "workspace", "status", "--workspace-root=$workspace"),
                    env = env,
                )
            }.getOrNull()
            if (statusResult?.exitCode == 0) {
                val status = runCatching {
                    defaultCliJson().decodeFromString<WorkspaceStatusResult>(statusResult.stdout)
                }.getOrNull()
                if (status?.selected?.ready == true) return process
            }
            Thread.sleep(500)
        }
        process.destroyForcibly()
        error("Timed out waiting for standalone backend at $workspace")
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
