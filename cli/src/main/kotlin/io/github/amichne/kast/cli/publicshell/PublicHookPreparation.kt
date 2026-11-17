package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.DaemonOperationCall
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationResult
import io.github.amichne.kast.appserver.RequestedRuntimeSettings
import io.github.amichne.kast.appserver.diagnosticCode
import io.github.amichne.kast.cli.direct.CoordinatedToolClient
import io.github.amichne.kast.cli.direct.InstalledClientBinding
import io.github.amichne.kast.cli.direct.InstalledToolAdmission
import io.github.amichne.kast.cli.direct.PreparedWorkspaceDocument
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.InstalledWorkspaceConfigurationIngress
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun prepareInstalledHook(
    root: CanonicalRoot,
    wait: HookWait,
    diagnostic: (String) -> Unit,
): Refinement<HookPreparation, HookPreparationFailure> =
    when (val installed = InstalledClientBinding.current(System.getenv())) {
        is Refinement.Rejected -> Refinement.Rejected(HookPreparationFailure.Installation(installed.failure))
        is Refinement.Refined -> prepareInstalledHook(root, wait, diagnostic, installed.value)
    }

internal fun prepareInstalledHook(
    root: CanonicalRoot,
    wait: HookWait,
    diagnostic: (String) -> Unit,
    installed: InstalledClientBinding,
    read: (CanonicalRoot, DaemonOperationCall, RequestedRuntimeSettings) -> DaemonOperationResult =
        CoordinatedToolClient(installed)::read,
): Refinement<HookPreparation, HookPreparationFailure> {
    when (installed.observe()) {
        InstalledToolAdmission.STOPPED -> return Refinement.Rejected(HookPreparationFailure.Stopped)
        InstalledToolAdmission.UNPROVEN -> return Refinement.Rejected(HookPreparationFailure.Unproven)
        InstalledToolAdmission.AVAILABLE -> Unit
    }
    val environment =
        when (wait) {
            HookWait.Background -> return Refinement.Rejected(HookPreparationFailure.Unproven)
            HookWait.Configured -> installed.environment
            is HookWait.Selected ->
                installed.environment +
                    (ConfigurationParameter.WORKSPACE_PREPARATION_WAIT.key to
                        (wait.allowance.protocolMillis()?.toString() ?: "indefinite"))
        }
    val settings = RequestedRuntimeSettings.fromWorkspace(installed.installation, root.path, environment)
    val reply = read(root, DaemonOperationCall.PrepareWorkspace, settings)
    return when (reply) {
        is DaemonOperationResult.Rejected -> Refinement.Rejected(reply.failure.hookFailure(diagnostic))
        is DaemonOperationResult.Complete ->
            if (ready(reply)) reportPreparation(root, installed, environment, diagnostic)
            else Refinement.Rejected(HookPreparationFailure.Unproven)
        is DaemonOperationResult.Qualified,
        is DaemonOperationResult.OperationRejected,
        is DaemonOperationResult.Hosted -> Refinement.Rejected(HookPreparationFailure.Unproven)
    }
}

private fun ready(reply: DaemonOperationResult.Complete): Boolean =
    try {
        preparationJson.decodeFromString<PreparedWorkspaceDocument>(reply.document.value).ready
    } catch (_: SerializationException) {
        false
    }

private fun reportPreparation(
    root: CanonicalRoot,
    installed: InstalledClientBinding,
    environment: Map<String, String>,
    diagnostic: (String) -> Unit,
): Refinement<HookPreparation, HookPreparationFailure> =
    when (
        val configuration =
            InstalledWorkspaceConfigurationIngress.resolve(installed.installation, root.path, environment)
    ) {
        is Refinement.Rejected ->
            Refinement.Rejected(HookPreparationFailure.Managed(ContainerFailure.INVALID_CONFIGURATION))
        is Refinement.Refined -> {
            val policy = configuration.value.workspacePreparationWait.hookDocument()
            diagnostic(preparationJson.encodeToString(policy))
            Refinement.Refined(HookPreparation.Ready(policy))
        }
    }

private fun DaemonOperationClientRejection.hookFailure(diagnostic: (String) -> Unit): HookPreparationFailure =
    when (this) {
        is DaemonOperationClientRejection.Server ->
            when (val reason = failure) {
                is DaemonOperationFailure.ManagedWorkspace ->
                    if (reason.conflict == null) HookPreparationFailure.Managed(reason.reason)
                    else HookPreparationFailure.Native(reason)
                else -> HookPreparationFailure.Native(reason)
            }
        is DaemonOperationClientRejection.Startup -> {
            diagnostic(preparationJson.encodeToString(HookStartupRejectionDiagnostic(diagnosticCode())))
            HookPreparationFailure.Unproven
        }
        is DaemonOperationClientRejection.Command,
        is DaemonOperationClientRejection.Coordinator,
        is DaemonOperationClientRejection.Transport -> HookPreparationFailure.Unproven
    }

@Serializable
private data class HookStartupRejectionDiagnostic(
    val reason: String,
    @Required val type: String = "coordinator_startup_rejected",
)

private val preparationJson = Json { encodeDefaults = true }

private fun ElapsedTimeAllowance.hookDocument(): HookWaitDocument =
    when (this) {
        is ElapsedTimeAllowance.Bounded -> HookWaitDocument(HookWaitMode.BOUNDED, limit.value)
        ElapsedTimeAllowance.Unlimited -> HookWaitDocument(HookWaitMode.INDEFINITE, null)
    }
