package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.WorkspaceOperationAdmission
import io.github.amichne.kast.cli.direct.InstalledClientContext
import io.github.amichne.kast.cli.direct.InstalledToolAdmission
import io.github.amichne.kast.cli.direct.ManagedToolFailure
import io.github.amichne.kast.cli.direct.invokeManagedTool
import io.github.amichne.kast.distribution.cli.ContainerFailure
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.InstalledWorkspaceConfigurationIngress
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun prepareInstalledHook(
    root: CanonicalRoot,
    wait: HookWait,
    diagnostic: (String) -> Unit,
): Refinement<HookPreparation, HookPreparationFailure> =
    when (val context = InstalledClientContext.current(System.getenv())) {
        is Refinement.Rejected -> Refinement.Rejected(HookPreparationFailure.Installation(context.failure))
        is Refinement.Refined -> prepareInstalledHook(root, wait, diagnostic, context.value)
    }

internal fun prepareInstalledHook(
    root: CanonicalRoot,
    wait: HookWait,
    diagnostic: (String) -> Unit,
    context: InstalledClientContext,
): Refinement<HookPreparation, HookPreparationFailure> =
    when (context.observe()) {
        InstalledToolAdmission.STOPPED -> Refinement.Rejected(HookPreparationFailure.Stopped)
        InstalledToolAdmission.UNPROVEN -> Refinement.Rejected(HookPreparationFailure.Unproven)
        InstalledToolAdmission.AVAILABLE -> prepareManagedHook(root, wait, context, diagnostic)
    }

private fun prepareManagedHook(
    root: CanonicalRoot,
    wait: HookWait,
    context: InstalledClientContext,
    diagnostic: (String) -> Unit,
): Refinement<HookPreparation, HookPreparationFailure> {
    val installed = context.environment
    val environment =
        when (wait) {
            HookWait.Background -> return Refinement.Rejected(HookPreparationFailure.Unproven)
            HookWait.Configured -> installed
            is HookWait.Selected ->
                installed +
                    ("KAST_WORKSPACE_PREPARATION_WAIT" to (wait.allowance.protocolMillis()?.toString() ?: "indefinite"))
        }
    return invokeManagedTool(
        context,
        root.path,
        WorkspaceOperationAdmission.PREPARE,
        environment,
        reject = { failure -> Refinement.Rejected(failure.hookFailure()) },
    ) { selected, _ ->
        // Admission and readiness already succeeded under the shared query lease. No second lifecycle request.
        when (
            val configuration =
                InstalledWorkspaceConfigurationIngress.resolve(context.installation, selected.path, environment)
        ) {
            is Refinement.Rejected ->
                Refinement.Rejected(HookPreparationFailure.Managed(ContainerFailure.INVALID_CONFIGURATION))
            is Refinement.Refined -> {
                val policy = configuration.value.workspacePreparationWait.hookDocument()
                diagnostic(preparationJson.encodeToString(policy))
                Refinement.Refined(HookPreparation.Ready(policy))
            }
        }
    }
}

private fun ManagedToolFailure.hookFailure(): HookPreparationFailure =
    when (this) {
        is ManagedToolFailure.Workspace -> HookPreparationFailure.Managed(reason)
        is ManagedToolFailure.Preparation -> HookPreparationFailure.Native(reason)
        ManagedToolFailure.Stopped -> HookPreparationFailure.Stopped
        ManagedToolFailure.Unproven -> HookPreparationFailure.Unproven
    }

private val preparationJson = Json { encodeDefaults = true }

private fun ElapsedTimeAllowance.hookDocument(): HookWaitDocument =
    when (this) {
        is ElapsedTimeAllowance.Bounded -> HookWaitDocument(HookWaitMode.BOUNDED, limit.value)
        ElapsedTimeAllowance.Unlimited -> HookWaitDocument(HookWaitMode.INDEFINITE, null)
    }
