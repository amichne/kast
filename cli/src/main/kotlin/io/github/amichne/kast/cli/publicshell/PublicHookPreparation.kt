package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.McpWorkspaceOperationClient
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.mcpWorkspaceOperationClient
import io.github.amichne.kast.cli.direct.InstalledClientContext
import io.github.amichne.kast.cli.direct.InstalledToolAdmission
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun prepareInstalledHook(
    root: CanonicalRoot,
    wait: HookWait,
    diagnostic: (String) -> Unit,
): Refinement<HookPreparation, HookPreparationFailure> =
    when (val context = InstalledClientContext.current(System.getenv())) {
        is Refinement.Rejected -> Refinement.Rejected(HookPreparationFailure.Installation(context.failure))
        is Refinement.Refined ->
            when (context.value.observe()) {
                InstalledToolAdmission.STOPPED -> Refinement.Rejected(HookPreparationFailure.Stopped)
                InstalledToolAdmission.UNPROVEN -> Refinement.Rejected(HookPreparationFailure.Unproven)
                InstalledToolAdmission.AVAILABLE ->
                    prepareHookClient(root, wait, hookClient(wait, context.value.environment), diagnostic)
            }
    }

private fun hookClient(wait: HookWait, installed: Map<String, String>): McpWorkspaceOperationClient {
    val environment =
        when (wait) {
            HookWait.Configured,
            HookWait.Background -> installed
            is HookWait.Selected ->
                installed +
                    ("KAST_WORKSPACE_PREPARATION_WAIT" to (wait.allowance.protocolMillis()?.toString() ?: "indefinite"))
        }
    val home = Path.of(environment["HOME"] ?: System.getProperty("user.home"))
    return mcpWorkspaceOperationClient(home, environment)
}

private fun prepareHookClient(
    root: CanonicalRoot,
    wait: HookWait,
    client: McpWorkspaceOperationClient,
    diagnostic: (String) -> Unit,
): Refinement<HookPreparation, HookPreparationFailure> {
    val policy =
        when (val allowance = client.preparationAllowance()) {
            is Refinement.Refined -> allowance.value.hookDocument()
            is Refinement.Rejected -> return Refinement.Rejected(HookPreparationFailure.Native(allowance.failure))
        }
    diagnostic(preparationJson.encodeToString(policy))
    return if (wait == HookWait.Background) beginHookPreparation(client, root, policy)
    else
        when (val outcome = runBlocking { client.awaitPreparation(root) }) {
            is Refinement.Refined -> Refinement.Refined(HookPreparation.Ready(policy))
            is Refinement.Rejected -> Refinement.Rejected(HookPreparationFailure.Native(outcome.failure))
        }
}

private val preparationJson = Json { encodeDefaults = true }

private fun ElapsedTimeAllowance.hookDocument(): HookWaitDocument =
    when (this) {
        is ElapsedTimeAllowance.Bounded -> HookWaitDocument(HookWaitMode.BOUNDED, limit.value)
        ElapsedTimeAllowance.Unlimited -> HookWaitDocument(HookWaitMode.INDEFINITE, null)
    }

private fun beginHookPreparation(
    client: McpWorkspaceOperationClient,
    root: CanonicalRoot,
    policy: HookWaitDocument,
): Refinement<HookPreparation, HookPreparationFailure> =
    when (val result = client.beginPreparation(root)) {
        is Refinement.Refined -> hookAdmissionResult(result.value, root, policy)
        is Refinement.Rejected -> Refinement.Rejected(HookPreparationFailure.Native(result.failure))
    }

private fun hookAdmissionResult(
    result: IdeLifecycleResult,
    root: CanonicalRoot,
    policy: HookWaitDocument,
): Refinement<HookPreparation, HookPreparationFailure> =
    when (result) {
        is IdeLifecycleResult.Pending ->
            Refinement.Refined(HookPreparation.Preparing(result.requestId, result.host, result.stage, policy))
        is IdeLifecycleResult.Opened ->
            if (result.target.root == root.path.toString()) Refinement.Refined(HookPreparation.Ready(policy))
            else Refinement.Rejected(HookPreparationFailure.Unproven)
        is IdeLifecycleResult.Blocked -> Refinement.Rejected(HookPreparationFailure.Lifecycle(result))
        else -> Refinement.Rejected(HookPreparationFailure.Unproven)
    }
