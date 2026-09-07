package io.github.amichne.kast.cli.broker

import io.github.amichne.kast.cli.broker.host.admission.CodexHostMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/** Produces one deterministic receipt for the host projections and their executable proof graph. */
internal object CodexHostIntegrationManifestMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 2) {
            "Expected output manifest and installed acceptance receipt"
        }
        val repository = Path.of("").toAbsolutePath().normalize()
        val output = Path.of(arguments[0]).toAbsolutePath().normalize()
        val installedReceipt = Path.of(arguments[1]).toAbsolutePath().normalize()
        require(Files.isRegularFile(installedReceipt)) {
            "Installed Codex host acceptance receipt is absent"
        }
        val installed = installedReceipt.readObject()
        require(installed.requiredString("outcome") == "COMPLETE") {
            "Installed Codex host acceptance is incomplete"
        }
        val projectionDigest = installed.requiredDigest("catalogProjectionSha256")
        val protocolDigest = installed.requiredDigest("codexProtocolSha256")
        val contractDigest = installed.requiredDigest("kastContractSha256")
        val catalogTools = installed.requiredStringArray("catalogToolNames")
        val source = GitSourceSnapshot.capture(repository)
        val document = buildJsonObject {
            put("schemaVersion", 2)
            put("taskId", "HOST-10")
            put("outcome", "COMPLETE")
            put(
                "invariant",
                "One qualified Kast tool catalog and execution path; host projections only determine how Codex reaches it.",
            )
            put("sourceRevision", source.revision)
            put("sourceTreeSha256", source.treeDigest)
            put("catalogAuthority", "AgentSessionBootstrap")
            put("catalogProjectionSha256", projectionDigest)
            put("kastContractSha256", contractDigest)
            put("catalogToolNames", catalogTools)
            put("protocolAuthority", buildJsonObject {
                put("kind", "installed-codex-generated-json-schema")
                put("codexVersion", installed.requiredString("codexVersion"))
                put("schemaSha256", protocolDigest)
            })
            put("installedArtifacts", buildJsonObject {
                put("kast", installed.requiredDigest("kastExecutableSha256"))
                put("kastCodexFacade", installed.requiredDigest("kastFacadeSha256"))
                put("codex", installed.requiredDigest("codexExecutableSha256"))
            })
            put("hostModes", buildJsonArray {
                CodexHostMode.entries.forEach { mode ->
                    add(buildJsonObject {
                        put("mode", mode.name)
                        put(
                            "transport",
                            when (mode) {
                                CodexHostMode.CLI_REMOTE_CLIENT -> "broker-uds-codex-remote"
                                CodexHostMode.APP_SERVER_STDIO -> "jsonl-stdio-broker-uds"
                            },
                        )
                    })
                }
            })
            put("installedCommands", buildJsonArray {
                add(JsonPrimitive("kast codex"))
                add(JsonPrimitive("kast codex desktop"))
                add(JsonPrimitive("kast-codex"))
                add(JsonPrimitive("kast-codex app-server"))
            })
            put("dependencyProofs", buildJsonArray {
                mapOf(
                    "HOST-01" to "CodexHostInvocationTest",
                    "HOST-02" to "KastCodexMainLifecycleTest",
                    "HOST-03" to "DesktopStdioHostTest",
                    "HOST-04" to "ManagedCodexUpstreamTest",
                    "HOST-05" to "CodexProtocolAdapterTest",
                    "HOST-06" to "CodexObserverReplayTest",
                    "HOST-07" to "InstalledCodexClientLauncherTest",
                    "HOST-08" to
                        "installedCodexHostTest + DesktopStdioProtocolIntegrationTest",
                    "HOST-09" to "AgentSessionProjectionTest",
                ).forEach { (task, proof) ->
                    add(buildJsonObject {
                        put("task", task)
                        put("proof", proof)
                    })
                }
            })
            put("proofCommands", buildJsonArray {
                listOf(
                    "./gradlew :cli:test",
                    "./gradlew installedProductTest",
                    "./gradlew installedCodexHostTest",
                    "./gradlew verifyKastArchitecture",
                    "./gradlew :cli:generateCodexHostIntegrationManifest",
                ).forEach { command ->
                    add(buildJsonObject {
                        put("command", command)
                        put("sha256", sha256(command.toByteArray(StandardCharsets.UTF_8)))
                    })
                }
            })
            put("installedAcceptanceSha256", sha256(Files.readAllBytes(installedReceipt)))
        }
        Files.createDirectories(output.parent)
        val temporary = output.resolveSibling("${output.fileName}.tmp")
        Files.writeString(temporary, Json.encodeToString(document) + "\n")
        try {
            Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(bytes: ByteArray): String = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )
}

private fun Path.readObject(): JsonObject = try {
    Json.parseToJsonElement(Files.readString(this)).jsonObject
} catch (failure: RuntimeException) {
    throw IllegalArgumentException("Installed acceptance receipt is invalid", failure)
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Installed acceptance field $name is invalid")

private fun JsonObject.requiredDigest(name: String): String = requiredString(name).also { value ->
    require(Regex("sha256:[0-9a-f]{64}").matches(value)) {
        "Installed acceptance digest $name is invalid"
    }
}

private fun JsonObject.requiredStringArray(name: String): JsonArray {
    val values = this[name]?.jsonArray
        ?: throw IllegalArgumentException("Installed acceptance field $name is invalid")
    require(values.isNotEmpty() && values.all { value ->
        value is JsonPrimitive && value.isString && value.content.isNotBlank()
    }) {
        "Installed acceptance field $name is invalid"
    }
    return values
}

private data class GitSourceSnapshot(
    val revision: String,
    val treeDigest: String,
) {
    companion object {
        fun capture(repository: Path): GitSourceSnapshot {
            val revision = executeGit(repository, "rev-parse", "HEAD")
                .toString(StandardCharsets.UTF_8).trim()
            require(Regex("[0-9a-f]{40}").matches(revision)) { "Git revision is invalid" }
            val paths = executeGit(
                repository,
                "ls-files",
                "-co",
                "--exclude-standard",
                "-z",
            ).toString(StandardCharsets.UTF_8)
                .split('\u0000')
                .filter(String::isNotEmpty)
                .sorted()
            val digest = MessageDigest.getInstance("SHA-256")
            paths.forEach { relative ->
                val path = repository.resolve(relative).normalize()
                require(path.startsWith(repository)) { "Git path escaped the repository" }
                digest.update(relative.toByteArray(StandardCharsets.UTF_8))
                digest.update(0.toByte())
                if (Files.isRegularFile(path)) {
                    Files.newInputStream(path).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                } else {
                    digest.update("MISSING".toByteArray(StandardCharsets.UTF_8))
                }
                digest.update(0.toByte())
            }
            return GitSourceSnapshot(
                revision,
                "sha256:${HexFormat.of().formatHex(digest.digest())}",
            )
        }

        private fun executeGit(repository: Path, vararg arguments: String): ByteArray {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(repository.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val output = process.inputStream.readAllBytes()
            require(process.waitFor() == 0) { "Git source evidence was unavailable" }
            return output
        }
    }
}
