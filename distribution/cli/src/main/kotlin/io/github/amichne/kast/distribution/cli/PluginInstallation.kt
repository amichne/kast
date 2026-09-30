package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class PluginHarness(val publicName: String) {
    CODEX("codex")
}

@Serializable
internal enum class PluginInstallStage {
    INSTALLATION_LOCK,
    PAYLOAD_ADMISSION,
    MARKETPLACE_INSPECTION,
    PLUGIN_INSPECTION,
    MARKETPLACE_REGISTRATION,
    PLUGIN_INSTALLATION,
    VERIFICATION,
}

@Serializable
internal sealed interface PluginInstallFailure {
    @Serializable
    @SerialName("INSTALLATION_LOCK_UNAVAILABLE")
    data object InstallationLockUnavailable : PluginInstallFailure

    @Serializable @SerialName("INSTALLATION_UNAVAILABLE") data object InstallationUnavailable : PluginInstallFailure

    @Serializable @SerialName("PAYLOAD_REJECTED") data object PayloadRejected : PluginInstallFailure

    @Serializable @SerialName("PROCESS_UNAVAILABLE") data object ProcessUnavailable : PluginInstallFailure

    @Serializable @SerialName("PROCESS_EXITED") data class ProcessExited(val exitCode: Int) : PluginInstallFailure

    @Serializable @SerialName("OBSERVATION_REJECTED") data object ObservationRejected : PluginInstallFailure

    @Serializable @SerialName("MARKETPLACE_CONFLICT") data object MarketplaceConflict : PluginInstallFailure

    @Serializable @SerialName("PLUGIN_CONFLICT") data object PluginConflict : PluginInstallFailure

    @Serializable @SerialName("VERIFICATION_REJECTED") data object VerificationRejected : PluginInstallFailure
}

@Serializable
internal sealed interface PluginInstallOutcome {
    @Serializable @SerialName("INSTALLED") data class Installed(val harness: PluginHarness) : PluginInstallOutcome

    @Serializable
    @SerialName("ALREADY_INSTALLED")
    data class AlreadyInstalled(val harness: PluginHarness) : PluginInstallOutcome

    @Serializable
    @SerialName("REJECTED")
    data class Rejected(val stage: PluginInstallStage, val failure: PluginInstallFailure) : PluginInstallOutcome
}

internal fun PluginInstallOutcome.asJson(): String = managementJson.encodeToString(this)

internal sealed interface PluginObservation<out T> {
    data class Observed<T>(val value: T) : PluginObservation<T>

    data class Rejected(val failure: PluginInstallFailure) : PluginObservation<Nothing>
}

internal const val KAST_PLUGIN_ID = "kast@kast"
internal const val KAST_MARKETPLACE_NAME = "kast"
internal const val AGENT_TOOLS_RELATIVE_PATH = "share/kast/agent-tools"

/** Codex owns its marketplace, plugin cache, and removal; Kast admits bytes and observes exact identities. */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
internal fun installCodexPlugin(
    root: Path,
    executeCodex: (List<String>) -> ProcessObservation = { runBounded(it) },
): PluginInstallOutcome =
    try {
        withRegistrationLock(root) { installAdmittedCodexPlugin(root, executeCodex) }
    } catch (_: ManagementRejected) {
        PluginInstallOutcome.Rejected(
            PluginInstallStage.INSTALLATION_LOCK,
            PluginInstallFailure.InstallationLockUnavailable,
        )
    }

@Suppress("ReturnCount", "CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
private fun installAdmittedCodexPlugin(
    root: Path,
    executeCodex: (List<String>) -> ProcessObservation,
): PluginInstallOutcome {
    val bundle =
        when (val admission = AdmittedPluginBundle.admit(root)) {
            is PluginObservation.Observed -> admission.value
            is PluginObservation.Rejected ->
                return PluginInstallOutcome.Rejected(PluginInstallStage.PAYLOAD_ADMISSION, admission.failure)
        }
    val marketplace =
        when (val observation = observePluginMarketplace(bundle, executeCodex)) {
            is PluginObservation.Observed -> observation.value
            is PluginObservation.Rejected ->
                return PluginInstallOutcome.Rejected(PluginInstallStage.MARKETPLACE_INSPECTION, observation.failure)
        }
    val plugin =
        when (val observation = observeInstalledPlugin(bundle, executeCodex)) {
            is PluginObservation.Observed -> observation.value
            is PluginObservation.Rejected ->
                return PluginInstallOutcome.Rejected(PluginInstallStage.PLUGIN_INSPECTION, observation.failure)
        }
    if (marketplace == MarketplacePresence.ABSENT && plugin != PluginPresence.ABSENT)
        return PluginInstallOutcome.Rejected(PluginInstallStage.PLUGIN_INSPECTION, PluginInstallFailure.PluginConflict)
    if (marketplace == MarketplacePresence.ADMITTED && plugin == PluginPresence.CURRENT)
        return PluginInstallOutcome.AlreadyInstalled(PluginHarness.CODEX)
    if (marketplace == MarketplacePresence.ABSENT) {
        val result =
            pluginProcessResult(
                executeCodex(listOf("codex", "plugin", "marketplace", "add", bundle.marketplace.toString(), "--json"))
            )
        if (result is PluginObservation.Rejected)
            return PluginInstallOutcome.Rejected(PluginInstallStage.MARKETPLACE_REGISTRATION, result.failure)
        when (val verification = observePluginMarketplace(bundle, executeCodex)) {
            is PluginObservation.Observed ->
                if (verification.value != MarketplacePresence.ADMITTED)
                    return PluginInstallOutcome.Rejected(
                        PluginInstallStage.MARKETPLACE_REGISTRATION,
                        PluginInstallFailure.VerificationRejected,
                    )
            is PluginObservation.Rejected ->
                return PluginInstallOutcome.Rejected(PluginInstallStage.MARKETPLACE_REGISTRATION, verification.failure)
        }
    }
    val installation = pluginProcessResult(executeCodex(listOf("codex", "plugin", "add", KAST_PLUGIN_ID, "--json")))
    if (installation is PluginObservation.Rejected)
        return PluginInstallOutcome.Rejected(PluginInstallStage.PLUGIN_INSTALLATION, installation.failure)
    return when (val verification = observeInstalledPlugin(bundle, executeCodex)) {
        is PluginObservation.Observed ->
            if (verification.value == PluginPresence.CURRENT) PluginInstallOutcome.Installed(PluginHarness.CODEX)
            else
                PluginInstallOutcome.Rejected(
                    PluginInstallStage.VERIFICATION,
                    PluginInstallFailure.VerificationRejected,
                )
        is PluginObservation.Rejected ->
            PluginInstallOutcome.Rejected(PluginInstallStage.VERIFICATION, verification.failure)
    }
}

internal fun pluginProcessResult(observation: ProcessObservation): PluginObservation<String> =
    when (observation) {
        is ProcessObservation.Exited ->
            if (observation.code == 0) PluginObservation.Observed(observation.output)
            else PluginObservation.Rejected(PluginInstallFailure.ProcessExited(observation.code))
        ProcessObservation.Unavailable -> PluginObservation.Rejected(PluginInstallFailure.ProcessUnavailable)
    }
