package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.options.InstallSkillOptions
import io.github.amichne.kast.cli.results.WorkspaceStatusResult
import io.github.amichne.kast.cli.tty.defaultCliJson
import kotlinx.serialization.json.Json
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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

class PackagedSkillJsonContractTest {
    @TempDir
    lateinit var tempDir: Path

    private val rpcJson = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

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
        val wrapperEnv = wrapperEnv(kastBinary, configHome, workspaceRoot)

        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/scripts/resolve-kast.sh")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/scripts/kast-session-start.sh")))
        assertTrue(
            Files.isRegularFile(
                installedSkillDir.resolve("kast/fixtures/maintenance/scripts/build-routing-corpus.py"),
            ),
        )
        assertTrue(
            Files.isRegularFile(
                installedSkillDir.resolve("kast/fixtures/maintenance/references/routing-improvement.md"),
            ),
        )
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/references/routing-improvement.md")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/scripts/build-routing-corpus.py")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/evaluation/catalog.json")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/evaluation/catalog.schema.json")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/evaluation/scripts/render_prompts.py")))
        assertTrue(Files.isRegularFile(installedSkillDir.resolve("kast/evaluation/scripts/run_evaluation.py")))

        val daemon = startRealBackend(workspaceRoot, wrapperEnv, kastBinary)
        try {
            val resolveScriptResult = runCommand(
                command = listOf(
                    "bash",
                    installedSkillDir.resolve("kast/scripts/resolve-kast.sh").toString(),
                ),
                env = wrapperEnv,
            )
            assertEquals(0, resolveScriptResult.exitCode, "stderr: ${resolveScriptResult.stderr}")
            assertTrue(Files.isExecutable(Path.of(resolveScriptResult.stdout)))
            assertFalse(resolveScriptResult.stdout.contains(" "))

            val resolveRequest = buildJsonObject {
                put("workspaceRoot", workspaceRoot.toString())
                put("symbol", "greet")
                put("fileHint", sourceFile.toString())
            }
            val resolveRpcRequest = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "symbol/resolve")
                put("params", resolveRequest)
                put("id", 1)
            }
            val resolveResult = runCommand(
                command = listOf(
                    kastBinary,
                    "rpc",
                    rpcJson.encodeToString(JsonObject.serializer(), resolveRpcRequest),
                    "--workspace-root=$workspaceRoot",
                ),
                env = wrapperEnv,
            )
            assertEquals(0, resolveResult.exitCode, "stderr: ${resolveResult.stderr}")
            val resolvedEnvelope = defaultCliJson()
                .parseToJsonElement(resolveResult.stdout)
                .jsonObject
            val resolvedPayload = resolvedEnvelope["result"]?.jsonObject ?: resolvedEnvelope
            assertEquals(
                true,
                resolvedPayload["ok"]?.toString()?.toBooleanStrictOrNull(),
                "stdout: ${resolveResult.stdout}\nstderr: ${resolveResult.stderr}",
            )
            assertEquals(
                "RESOLVE_SUCCESS",
                resolvedPayload["type"]?.jsonPrimitive?.content,
                "stdout: ${resolveResult.stdout}\nstderr: ${resolveResult.stderr}",
            )
            assertTrue(resolveResult.stderr.isBlank())

            val diagnosticsRequestFile = tempDir.resolve("diagnostics-request.json")
            val diagnosticsRequest = buildJsonObject {
                put(
                    "filePaths",
                    buildJsonArray {
                        add(JsonPrimitive(sourceFile.toString()))
                    },
                )
            }
            diagnosticsRequestFile.writeText(
                defaultCliJson().encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", "raw/diagnostics")
                        put("params", diagnosticsRequest)
                        put("id", 1)
                    },
                ),
            )

            val diagnosticsResult = runCommand(
                command = listOf(
                    kastBinary,
                    "rpc",
                    "--request-file=${diagnosticsRequestFile}",
                    "--workspace-root=$workspaceRoot",
                ),
                env = wrapperEnv,
            )
            assertEquals(0, diagnosticsResult.exitCode, "stderr: ${diagnosticsResult.stderr}")
            val diagnosticsEnvelope = defaultCliJson()
                .parseToJsonElement(diagnosticsResult.stdout)
                .jsonObject
            val diagnosticsPayload = diagnosticsEnvelope["result"]?.jsonObject ?: diagnosticsEnvelope
            assertEquals(true, diagnosticsPayload["schemaVersion"] != null)
        } finally {
            runCommand(
                command = listOf(kastBinary, "workspace", "stop", "--workspace-root=$workspaceRoot"),
                env = wrapperEnv,
            )
            daemon.destroyForcibly()
        }
    }

    @Test
    @Disabled
    fun `diagnostics stay clean for repo wrapper executor file`() {
        val repoRoot = findRepoRoot(Path.of("").toAbsolutePath())
        val targetFile = repoRoot.resolve(
            "analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt",
        )
        val kastBinary = checkNotNull(System.getProperty("kast.wrapper")) {
            "kast.wrapper system property is missing"
        }
        val configHome = tempDir.resolve("kast-config-repo")
        val wrapperEnv = wrapperEnv(kastBinary, configHome, repoRoot)

        val daemon = startRealBackend(repoRoot, wrapperEnv, kastBinary)
        try {
            val ensureResult = runCommand(
                command = listOf(kastBinary, "workspace", "ensure", "--workspace-root=$repoRoot"),
                env = wrapperEnv,
            )
            assertEquals(0, ensureResult.exitCode, "stderr: ${ensureResult.stderr}")

            val diagnosticsRequest = buildJsonObject {
                put(
                    "filePaths",
                    buildJsonArray {
                        add(JsonPrimitive(targetFile.toString()))
                    },
                )
            }

            val diagnosticsResult = runCommand(
                command = listOf(
                    kastBinary,
                    "rpc",
                    defaultCliJson().encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("method", "raw/diagnostics")
                            put("params", diagnosticsRequest)
                            put("id", 1)
                        },
                    ),
                    "--workspace-root=$repoRoot",
                ),
                env = wrapperEnv,
            )

            assertEquals(0, diagnosticsResult.exitCode, "stderr: ${diagnosticsResult.stderr}")
            val diagnosticsEnvelope = defaultCliJson()
                .parseToJsonElement(diagnosticsResult.stdout)
                .jsonObject
            val diagnosticsPayload = diagnosticsEnvelope["result"]?.jsonObject ?: diagnosticsEnvelope
            assertEquals(true, diagnosticsPayload["diagnostics"]?.toString() == "[]")
        } finally {
            runCommand(
                command = listOf(kastBinary, "workspace", "stop", "--workspace-root=$repoRoot"),
                env = wrapperEnv,
            )
            daemon.destroyForcibly()
        }
    }

    private fun wrapperEnv(
        kastBinary: String,
        configHome: Path,
        workspaceRoot: Path,
    ): Map<String, String> = mapOf(
        "PATH" to listOfNotNull(
            Path.of(kastBinary).parent?.toString(),
            System.getenv("PATH"),
        ).joinToString(File.pathSeparator),
        "KAST_CONFIG_HOME" to configHome.toString(),
        "KAST_WORKSPACE_ROOT" to workspaceRoot.toString(),
    )

    private fun startRealBackend(
        workspace: Path,
        env: Map<String, String>,
        kastBinary: String,
        timeoutMillis: Long = 120_000,
    ): Process = startStandaloneBackendForTest(
        workspace = workspace,
        env = env,
        timeoutMillis = timeoutMillis,
        statusProbe = {
            val statusResult = runCommand(
                command = listOf(kastBinary, "workspace", "status", "--workspace-root=$workspace"),
                env = env,
            )
            BackendStatusProbeSnapshot(
                exitCode = statusResult.exitCode,
                stdout = statusResult.stdout,
                stderr = statusResult.stderr,
            )
        },
        isReady = { probe ->
            probe.exitCode == 0 &&
                runCatching {
                    defaultCliJson().decodeFromString<WorkspaceStatusResult>(probe.stdout)
                }.getOrNull()?.selected?.ready == true
        },
    )

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

    private fun findRepoRoot(start: Path): Path = generateSequence(start.normalize()) { it.parent }
        .firstOrNull { candidate -> Files.isRegularFile(candidate.resolve(".github/hooks/session-end.sh")) }
        ?: error("Could not locate repo root from $start")

    private fun Path.createDirectoriesForParent(): Path {
        Files.createDirectories(checkNotNull(parent))
        return this
    }
}
