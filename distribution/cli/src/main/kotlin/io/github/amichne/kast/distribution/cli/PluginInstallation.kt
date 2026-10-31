package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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
    LEGACY_REGISTRATION_INSPECTION,
    MARKETPLACE_INSPECTION,
    PLUGIN_INSPECTION,
    MARKETPLACE_REGISTRATION,
    PLUGIN_INSTALLATION,
    VERIFICATION,
    LEGACY_REGISTRATION_REMOVAL,
    DEPENDENCY_PREPARATION,
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

    @Serializable
    @SerialName("LEGACY_REGISTRATION_REJECTED")
    data object LegacyRegistrationRejected : PluginInstallFailure
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

    @Serializable
    @SerialName("RECOVERY_REQUIRED")
    data class RecoveryRequired(val stage: PluginInstallStage, val failure: PluginInstallFailure) : PluginInstallOutcome
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
    home: Path,
    executeCodex: (List<String>) -> ProcessObservation = { runBounded(it) },
    executeLegacyCodexAt: (Path, List<String>) -> ProcessObservation = { selected, arguments ->
        runBounded(arguments, selected)
    },
    codexHome: Path = home.resolve(".codex"),
    composition: HarnessComposition = HarnessComposition.CliOnly,
    executeDependency: (List<String>, Map<String, String>) -> ProcessObservation = { arguments, environment ->
        runGuidanceProvider(arguments, environment, timeoutSeconds = MCP_SETUP_SECONDS)
    },
): PluginInstallOutcome =
    try {
        withRegistrationLock(root) {
            when (
                val outcome =
                    installAdmittedCodexPlugin(root, home, codexHome, executeCodex, composition, executeDependency)
            ) {
                is PluginInstallOutcome.Installed,
                is PluginInstallOutcome.AlreadyInstalled ->
                    when (val removal = removeLegacyCodexRegistrations(root, home, executeLegacyCodexAt)) {
                        is PluginObservation.Observed -> outcome
                        is PluginObservation.Rejected ->
                            PluginInstallOutcome.RecoveryRequired(
                                PluginInstallStage.LEGACY_REGISTRATION_REMOVAL,
                                removal.failure,
                            )
                    }
                is PluginInstallOutcome.Rejected,
                is PluginInstallOutcome.RecoveryRequired -> outcome
            }
        }
    } catch (_: ManagementRejected) {
        PluginInstallOutcome.Rejected(
            PluginInstallStage.INSTALLATION_LOCK,
            PluginInstallFailure.InstallationLockUnavailable,
        )
    }

private fun removeLegacyCodexRegistrations(
    root: Path,
    home: Path,
    executeCodexAt: (Path, List<String>) -> ProcessObservation,
): PluginObservation<Unit> =
    try {
        for (connection in HarnessConnection.entries.filter { it.harness == Harness.CODEX }) {
            disconnectAdmittedConnection(root, home, connection, executeCodexAt)
        }
        PluginObservation.Observed(Unit)
    } catch (_: ManagementRejected) {
        PluginObservation.Rejected(PluginInstallFailure.LegacyRegistrationRejected)
    } catch (_: IOException) {
        PluginObservation.Rejected(PluginInstallFailure.LegacyRegistrationRejected)
    } catch (_: SecurityException) {
        PluginObservation.Rejected(PluginInstallFailure.LegacyRegistrationRejected)
    }

@Suppress("ReturnCount", "CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
private fun installAdmittedCodexPlugin(
    root: Path,
    home: Path,
    codexHome: Path,
    executeCodex: (List<String>) -> ProcessObservation,
    composition: HarnessComposition,
    executeDependency: (List<String>, Map<String, String>) -> ProcessObservation,
): PluginInstallOutcome {
    val bundle =
        when (val admission = AdmittedPluginBundle.admit(root, composition)) {
            is PluginObservation.Observed -> admission.value
            is PluginObservation.Rejected ->
                return PluginInstallOutcome.Rejected(PluginInstallStage.PAYLOAD_ADMISSION, admission.failure)
        }
    when (val legacy = inspectLegacyCodexRegistration(root, home, codexHome, bundle, executeCodex)) {
        is PluginObservation.Observed -> Unit
        is PluginObservation.Rejected ->
            return PluginInstallOutcome.Rejected(PluginInstallStage.LEGACY_REGISTRATION_INSPECTION, legacy.failure)
    }
    val legacyFiles = admittedReceipt(root).registrations.filter { it.connection == HarnessConnection.CODEX_APP_SERVER }
    if (
        legacyFiles.any {
            !Files.isRegularFile(Path.of(it.destination), NOFOLLOW_LINKS) ||
                sha256(Path.of(it.destination)) != it.payloadSha256
        }
    )
        return PluginInstallOutcome.Rejected(
            PluginInstallStage.LEGACY_REGISTRATION_INSPECTION,
            PluginInstallFailure.LegacyRegistrationRejected,
        )
    val marketplace =
        when (val observation = observePluginMarketplace(bundle, executeCodex)) {
            is PluginObservation.Observed -> observation.value
            is PluginObservation.Rejected ->
                return PluginInstallOutcome.Rejected(PluginInstallStage.MARKETPLACE_INSPECTION, observation.failure)
        }
    val plugin =
        when (val observation = observeInstalledPlugin(bundle, codexHome, executeCodex)) {
            is PluginObservation.Observed -> observation.value
            is PluginObservation.Rejected ->
                return PluginInstallOutcome.Rejected(PluginInstallStage.PLUGIN_INSPECTION, observation.failure)
        }
    if (marketplace == MarketplacePresence.ABSENT && plugin != PluginPresence.ABSENT)
        return PluginInstallOutcome.Rejected(PluginInstallStage.PLUGIN_INSPECTION, PluginInstallFailure.PluginConflict)
    when (val preparation = prepareOptionalMcp(bundle, home, executeDependency)) {
        is PluginObservation.Observed -> Unit
        is PluginObservation.Rejected ->
            return PluginInstallOutcome.Rejected(PluginInstallStage.DEPENDENCY_PREPARATION, preparation.failure)
    }
    if (marketplace == MarketplacePresence.ADMITTED && plugin == PluginPresence.CURRENT)
        return PluginInstallOutcome.AlreadyInstalled(PluginHarness.CODEX)
    if (marketplace == MarketplacePresence.OWNED_OTHER) {
        val removal =
            pluginProcessResult(
                executeCodex(listOf("codex", "plugin", "marketplace", "remove", KAST_MARKETPLACE_NAME, "--json"))
            )
        if (removal is PluginObservation.Rejected)
            return PluginInstallOutcome.RecoveryRequired(PluginInstallStage.MARKETPLACE_REGISTRATION, removal.failure)
    }
    if (marketplace != MarketplacePresence.ADMITTED) {
        val result =
            pluginProcessResult(
                executeCodex(listOf("codex", "plugin", "marketplace", "add", bundle.marketplace.toString(), "--json"))
            )
        if (result is PluginObservation.Rejected)
            return PluginInstallOutcome.RecoveryRequired(PluginInstallStage.MARKETPLACE_REGISTRATION, result.failure)
        when (val verification = observePluginMarketplace(bundle, executeCodex)) {
            is PluginObservation.Observed ->
                if (verification.value != MarketplacePresence.ADMITTED)
                    return PluginInstallOutcome.RecoveryRequired(
                        PluginInstallStage.MARKETPLACE_REGISTRATION,
                        PluginInstallFailure.VerificationRejected,
                    )
            is PluginObservation.Rejected ->
                return PluginInstallOutcome.RecoveryRequired(
                    PluginInstallStage.MARKETPLACE_REGISTRATION,
                    verification.failure,
                )
        }
    }
    val installation = pluginProcessResult(executeCodex(listOf("codex", "plugin", "add", KAST_PLUGIN_ID, "--json")))
    if (installation is PluginObservation.Rejected)
        return PluginInstallOutcome.RecoveryRequired(PluginInstallStage.PLUGIN_INSTALLATION, installation.failure)
    return when (val verification = observeInstalledPlugin(bundle, codexHome, executeCodex)) {
        is PluginObservation.Observed ->
            if (verification.value == PluginPresence.CURRENT) PluginInstallOutcome.Installed(PluginHarness.CODEX)
            else
                PluginInstallOutcome.RecoveryRequired(
                    PluginInstallStage.VERIFICATION,
                    PluginInstallFailure.VerificationRejected,
                )
        is PluginObservation.Rejected ->
            PluginInstallOutcome.RecoveryRequired(PluginInstallStage.VERIFICATION, verification.failure)
    }
}

private fun inspectLegacyCodexRegistration(
    root: Path,
    home: Path,
    codexHome: Path,
    bundle: AdmittedPluginBundle,
    executeCodex: (List<String>) -> ProcessObservation,
): PluginObservation<Unit> =
    try {
        val observed = codexRegistration(executeCodex)
        if (observed == null || bundle.ownsTransport(observed)) PluginObservation.Observed(Unit)
        else {
            val prior =
                admittedReceipt(root).registrations.singleOrNull { it.connection == HarnessConnection.CODEX_MCP }
            if (
                prior != null &&
                    ownsCodex(observed, Path.of(prior.destination)) &&
                    disconnectionDirectory(root, home, prior).path == codexHome
            )
                PluginObservation.Observed(Unit)
            else PluginObservation.Rejected(PluginInstallFailure.LegacyRegistrationRejected)
        }
    } catch (_: ManagementRejected) {
        PluginObservation.Rejected(PluginInstallFailure.LegacyRegistrationRejected)
    }

internal fun pluginProcessResult(observation: ProcessObservation): PluginObservation<String> =
    when (observation) {
        is ProcessObservation.Exited ->
            if (observation.code == 0) PluginObservation.Observed(observation.output)
            else PluginObservation.Rejected(PluginInstallFailure.ProcessExited(observation.code))
        ProcessObservation.Unavailable -> PluginObservation.Rejected(PluginInstallFailure.ProcessUnavailable)
    }
