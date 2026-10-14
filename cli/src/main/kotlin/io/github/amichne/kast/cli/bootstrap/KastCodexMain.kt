package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.kernel.Refinement
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The integration host owns the broker and client lifetime; semantic readiness stays in Kast. */
object KastCodexMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val result = runBlocking {
            if (System.getenv().containsKey("KAST_SAVED_CONFIGURATION_FAILURE")) {
                CodexIntegrationRun.Rejected(CodexIntegrationFailure.CONFIGURATION_REJECTED)
            } else
                when (val installed = installedKastExecutable()) {
                    is Refinement.Refined -> runInstalledCodex(arguments.toList(), installed.value)
                    is Refinement.Rejected ->
                        CodexIntegrationRun.Rejected(CodexIntegrationFailure.INSTALLATION_UNAVAILABLE)
                }
        }
        when (result) {
            is CodexIntegrationRun.Completed -> exitProcess(result.exitCode)
            is CodexIntegrationRun.Rejected -> {
                System.err.println("kast-codex: ${result.failure.name.lowercase().replace('_', '-')}")
                exitProcess(64)
            }
            is CodexIntegrationRun.BrokerRejected -> {
                System.err.println(codexBrokerRejectionDiagnostic(result))
                exitProcess(64)
            }
        }
    }
}

internal fun codexBrokerRejectionDiagnostic(rejection: CodexIntegrationRun.BrokerRejected): String =
    codexDiagnosticJson.encodeToString<CodexBrokerDiagnosticDocument>(
        CodexBrokerDiagnosticDocument.Rejected(rejection.failure)
    )

@Serializable
internal sealed interface CodexBrokerDiagnosticDocument {
    @Serializable
    @SerialName("BROKER_START_REJECTED")
    data class Rejected(val failure: PersistentBrokerServiceFailure) : CodexBrokerDiagnosticDocument
}

private val codexDiagnosticJson = Json { encodeDefaults = true }
