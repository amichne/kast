package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.broker.core.BrokerDispatch
import io.github.amichne.kast.cli.broker.core.BrokerFailure
import io.github.amichne.kast.cli.broker.schema.canonicalJson
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapCorrectiveAction
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapFailure
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapPhase
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.TimeSource

internal enum class ColdBrokerStage { SETUP, QUALIFICATION, CATALOG, COLD_STATUS, DISCOVERY, INSPECTION, SOURCE, RELATION, STOP }
internal enum class ColdBrokerFailure { ASSERTION_REJECTED, DISPATCH_REJECTED, TOOL_REJECTED, OBSERVATION_LIMIT, EVIDENCE_UNAVAILABLE }

/** Test-boundary receipt: closed stage/outcome IDs and digests survive teardown without payloads. */
internal class InstalledColdBrokerEvidence(private val path: Path) {
    private var stage = ColdBrokerStage.SETUP
    private sealed interface Progress {
        data object Running : Progress
        data class Rejected(val failure: ColdBrokerFailure, val stage: ColdBrokerStage) : Progress
    }
    private var progress: Progress = Progress.Running
    private val observations = mutableListOf<JsonObject>()

    init { persist() }

    fun advance(next: ColdBrokerStage) { stage = next; persist() }

    fun reject(reason: ColdBrokerFailure) {
        progress = when (val observed = progress) {
            Progress.Running -> Progress.Rejected(reason, stage)
            is Progress.Rejected -> observed
        }
        persist()
    }

    fun executor(delegate: BrokerProcessExecutor): BrokerProcessExecutor = BrokerProcessExecutor { request ->
        val command = when (request.arguments.take(2)) {
            listOf("--version") -> "version"
            listOf("--schema") -> "schema"
            listOf("start") -> "start"
            listOf("status") -> "status"
            listOf("stop") -> "stop"
            listOf("symbol", "discover") -> "symbol-discover"
            listOf("symbol", "inspect") -> "symbol-inspect"
            listOf("source", "read") -> "source-read"
            listOf("relation", "read") -> "relation-read"
            else -> "unrecognized"
        }
        if (request.arguments == listOf("stop")) {
            // Cleanup must execute even when the observation destination has become unavailable.
            stage = ColdBrokerStage.STOP
        } else {
            record(buildJsonObject { put("kind", "process-started"); put("command", command) })
        }
        val started = TimeSource.Monotonic.markNow()
        val outcome = delegate.execute(request)
        record(buildJsonObject {
            put("kind", "process-completed"); put("command", command)
            put("elapsedMilliseconds", started.elapsedNow().inWholeMilliseconds)
            when (outcome) {
                is BrokerProcessExecution.Rejected -> {
                    put("status", "rejected"); put("cause", outcome.failure.name)
                }
                is BrokerProcessExecution.Completed -> {
                    put("status", "observed"); put("exitCode", outcome.exitCode)
                    put("stdoutBytes", outcome.stdout.toByteArray().size)
                    put("stderrBytes", outcome.stderr.toByteArray().size)
                    put("stdoutDigest", digest(outcome.stdout)); put("stderrDigest", digest(outcome.stderr))
                    put("publicOutcome", publicOutcome(if (outcome.exitCode == 0) outcome.stdout else outcome.stderr))
                }
            }
        })
        outcome
    }

    fun dispatch(result: BrokerDispatch) {
        record(buildJsonObject {
            put("kind", "dispatch")
            when (result) {
                is BrokerDispatch.Completed -> {
                    put("status", if (result.presentation.success) "complete" else "rejected")
                    val text = result.presentation.content.singleOrNull()?.text.orEmpty()
                    put("documentDigest", digest(text))
                    if (!result.presentation.success) put("publicOutcome", publicOutcome(text, wrapped = true))
                }
                is BrokerDispatch.Rejected -> {
                    put("status", "rejected")
                    when (val cause = result.failure) {
                        is BrokerFailure.UnknownNamespace -> put("cause", "unknown-namespace")
                        is BrokerFailure.UnknownTool -> put("cause", "unknown-tool")
                        is BrokerFailure.InvalidArguments -> put("cause", "invalid-arguments")
                        is BrokerFailure.ProviderStartupRejected -> {
                            put("cause", "provider-startup-rejected"); put("providerCode", cause.code.name)
                        }
                        is BrokerFailure.ProviderInvocationRejected -> {
                            put("cause", "provider-invocation-rejected"); put("providerCode", cause.code.name)
                        }
                        is BrokerFailure.OutputContractRejected -> put("cause", "output-contract-rejected")
                        is BrokerFailure.InvocationCancelled -> put("cause", "invocation-cancelled")
                        is BrokerFailure.Overloaded -> { put("cause", "overloaded"); put("limit", cause.limit.name) }
                    }
                }
            }
        })
        when (result) {
            is BrokerDispatch.Rejected -> reject(ColdBrokerFailure.DISPATCH_REJECTED)
            is BrokerDispatch.Completed -> if (!result.presentation.success) reject(ColdBrokerFailure.TOOL_REJECTED)
        }
    }

    fun complete(document: JsonObject) {
        check(progress == Progress.Running) { "Cold broker receipt already rejected" }
        write(JsonObject(document + ("observations" to JsonArray(observations.toList()))))
    }

    private fun record(observation: JsonObject) {
        if (observations.size >= 96) {
            reject(ColdBrokerFailure.OBSERVATION_LIMIT)
            // Evidence saturation rejects the receipt, but must never prevent owned process cleanup.
            return
        }
        observations += JsonObject(observation + ("stage" to JsonPrimitive(stage.name)))
        persist()
    }

    private fun persist() = write(buildJsonObject {
        put("schemaVersion", 2); put("stage", stage.name)
        when (val observed = progress) {
            Progress.Running -> put("status", "running")
            is Progress.Rejected -> {
                put("status", "rejected"); put("failure", observed.failure.name)
                put("failedStage", observed.stage.name)
            }
        }
        put("observations", JsonArray(observations.toList()))
    })

    private fun write(document: JsonObject) {
        try {
            writeAtomically(document)
        } catch (_: IOException) {
            evidenceUnavailable()
        } catch (_: SecurityException) {
            evidenceUnavailable()
        }
    }

    private fun evidenceUnavailable(): Nothing {
        progress = when (val observed = progress) {
            Progress.Running -> Progress.Rejected(ColdBrokerFailure.EVIDENCE_UNAVAILABLE, stage)
            is Progress.Rejected -> observed
        }
        // The filesystem exception may contain private paths; retain only the finite boundary ID.
        throw IOException("Cold broker evidence unavailable")
    }

    private fun writeAtomically(document: JsonObject) {
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".cold-broker-", ".json")
        try {
            Files.writeString(temporary, canonicalJson(document) + "\n")
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun publicOutcome(raw: String, wrapped: Boolean = false): JsonObject {
        val parsed = try { Json.parseToJsonElement(raw) as? JsonObject }
        catch (_: SerializationException) { null }
        catch (_: IllegalArgumentException) { null }
        val document = if (wrapped) parsed?.get("diagnostic") as? JsonObject else parsed
        fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
        val bootstrap = document?.get("bootstrap") as? JsonObject
        return buildJsonObject {
            put("status", document.text("status").takeIf { it in setOf("complete", "completed", "qualified", "rejected") } ?: "unrecognized")
            val boundary = document.text("boundary")
            put("boundary", CliBoundaryExitStatus.entries.singleOrNull { it.name.lowercase() == boundary }?.name ?: "unrecognized")
            put("reason", document.text("reason").takeIf { it in KNOWN_REASONS } ?: "unrecognized")
            put("phase", SemanticRuntimeBootstrapPhase.entries.singleOrNull { it.wireName == bootstrap.text("phase") }?.name ?: "unrecognized")
            put("cause", SemanticRuntimeBootstrapFailure.entries.singleOrNull { it.wireName == bootstrap.text("cause") }?.name ?: "unrecognized")
            put("correctiveAction", SemanticRuntimeBootstrapCorrectiveAction.entries.singleOrNull { it.instruction == bootstrap.text("correctiveAction") }?.name ?: "unrecognized")
        }
    }

    private companion object {
        val KNOWN_REASONS = setOf(
            "idea-installation-ambiguous", "idea-installation-missing", "idea-installation-incompatible", "idea-installation-rejected",
            "manifest-invalid", "source-invalid", "artifact-unavailable", "digest-mismatch", "archive-rejected", "layout-invalid",
            "runtime-incompatible", "idea-jbr-unavailable", "user-home-unavailable", "process-start-failed", "session-ended-before-ready",
            "bootstrap-state-unavailable", "bootstrap-attempt-unavailable", "bootstrap-attempt-mismatch", "bootstrap-attempt-lock-unavailable",
            "bootstrap-document-malformed", "bootstrap-schema-unsupported", "process-observation-failed", "endpoint-unavailable",
            "runtime-identity-mismatch", "legacy-sidecar-active", "interrupted", "sidecar-cache-rejected", "sidecar-cache-rebuild-required",
            "arguments-rejected", "argument-too-long", "runtime-not-running", "gradle-import-environment-rejected",
        ) + SemanticRuntimeBootstrapFailure.entries.map { it.wireName }

        fun digest(value: String): String = "sha256:" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
        )
    }
}
