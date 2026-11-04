package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.direct.ClientInstallationFailure
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import java.io.InputStream
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface HookWait {
    data object Configured : HookWait

    data object Background : HookWait

    data class Selected(val allowance: ElapsedTimeAllowance) : HookWait
}

@Serializable internal data class HookWaitDocument(val mode: HookWaitMode, val timeoutMillis: Long?)

@Serializable
internal enum class HookWaitMode {
    BOUNDED,
    INDEFINITE,
}

@Serializable
internal sealed interface HookPreparation {
    val wait: HookWaitDocument

    @Serializable @SerialName("ready") data class Ready(override val wait: HookWaitDocument) : HookPreparation

    @Serializable
    @SerialName("preparing")
    data class Preparing(
        val requestId: String,
        val host: String,
        val stage: IdeLifecycleStage,
        override val wait: HookWaitDocument,
    ) : HookPreparation
}

@Serializable private data class HookAccepted(val preparation: HookPreparation, val additionalContext: String)

@Serializable private data class HookSkipped(val additionalContext: String)

@Serializable
private data class HookRejected(
    val failure: HookFailure,
    val cause: HookPreparationFailure? = null,
    @Required val type: String = "rejected",
)

@Serializable
internal sealed interface HookPreparationFailure {
    @Serializable
    @SerialName("installation")
    data class Installation(val failure: ClientInstallationFailure) : HookPreparationFailure

    @Serializable @SerialName("stopped") data object Stopped : HookPreparationFailure

    @Serializable @SerialName("unproven") data object Unproven : HookPreparationFailure

    @Serializable @SerialName("native") data class Native(val failure: DaemonOperationFailure) : HookPreparationFailure

    @Serializable
    @SerialName("lifecycle")
    data class Lifecycle(val result: io.github.amichne.kast.protocol.contract.IdeLifecycleResult.Blocked) :
        HookPreparationFailure
}

@Serializable
private data class CodexHookContext(val additionalContext: String, val hookEventName: String = "SessionStart")

@Serializable private data class CodexHookOutput(val hookSpecificOutput: CodexHookContext)

@Serializable private data class ContextHookOutput(val additionalContext: String)

@Serializable private data class HookProgress(val wait: String, val state: String = "preparing")

private val hookJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
private const val MAX_HOOK_EVENT_BYTES = 65536

/** Registration selects a host envelope and background admission; explicit waiting keeps the same lifecycle owner. */
internal fun preparePublicHook(
    argv: List<String>,
    input: InputStream,
    diagnostic: (String) -> Unit = {},
    prepare: (CanonicalRoot, HookWait) -> Refinement<HookPreparation, HookPreparationFailure> = { root, wait ->
        prepareInstalledHook(root, wait, diagnostic)
    },
): PublicPreparedOutput {
    if (argv in hookHelpCommands)
        return PublicPreparedOutput(HOOK_HELP, PublicShellExit.COMPLETE, PublicOutputContent.HELP)
    val waitCommand = argv.take(2) == listOf("workspace", "wait")
    if (!waitCommand && argv.take(2) != listOf("hook", "session-start"))
        return hookRejected(HookFailure.INVALID_COMMAND, PublicShellExit.INPUT)
    val arguments =
        when (val parsed = readHookArguments(argv, input, waitCommand)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected ->
                return hookRejected(
                    parsed.failure,
                    if (parsed.failure == HookFailure.INPUT_UNAVAILABLE) PublicShellExit.IO else PublicShellExit.INPUT,
                )
        }
    return when (val root = FilesystemCanonicalRootDiscovery.discover(arguments.workspace)) {
        is CanonicalRootDiscovery.Rejected -> hookRootRejected(root.failure, arguments.host, waitCommand)
        is CanonicalRootDiscovery.Discovered -> prepareProjectHook(root.root, arguments, diagnostic, prepare)
    }
}

private val hookHelpCommands =
    listOf(
        listOf("hook", "--help"),
        listOf("workspace", "--help"),
        listOf("hook", "session-start", "--help"),
        listOf("workspace", "wait", "--help"),
    )
private const val HOOK_HELP =
    "kast hook session-start [--workspace ABSOLUTE_PATH] [--wait none|indefinite|TIMEOUT_MS] [--host codex|copilot]\n" +
        "kast workspace wait [--workspace ABSOLUTE_PATH] [--wait indefinite|TIMEOUT_MS]\n" +
        "A positive timeout is in milliseconds. Indefinite waiting has no preparation deadline.\n" +
        "Omitted --wait uses KAST_WORKSPACE_PREPARATION_WAIT and saved configuration (default 1020000 ms).\n" +
        "Hooks use --wait none to return preparation state immediately.\n" +
        "Waiting stops on cancellation, native failure, editor closure, or a changed host.\n" +
        "Without --workspace, session-start reads cwd from stdin; workspace wait uses the current directory."

private fun hookRootRejected(
    failure: CanonicalRootFailure,
    host: HookHost,
    waitCommand: Boolean,
): PublicPreparedOutput =
    if (failure == CanonicalRootFailure.ROOT_MARKER_NOT_FOUND && !waitCommand)
        hookContext(host, "Kast workspace preparation skipped: this directory has no Gradle settings owner.")
    else
        hookRejected(
            HookFailure.INVALID_WORKSPACE,
            PublicShellExit.WORKSPACE,
            HookPreparationFailure.Native(DaemonOperationFailure.Root(failure)),
        )

private fun prepareProjectHook(
    root: CanonicalRoot,
    arguments: HookArguments,
    diagnostic: (String) -> Unit,
    prepare: (CanonicalRoot, HookWait) -> Refinement<HookPreparation, HookPreparationFailure>,
): PublicPreparedOutput {
    diagnostic(hookJson.encodeToString(HookProgress(waitDescription(arguments.wait))))
    return when (val result = prepare(root, arguments.wait)) {
        is Refinement.Refined -> acceptedHookOutput(arguments.host, result.value)
        is Refinement.Rejected ->
            if (result.failure.isPreparationTimeout())
                hookRejected(HookFailure.PREPARATION_TIMED_OUT, PublicShellExit.ELAPSED, result.failure)
            else hookRejected(HookFailure.PREPARATION_REJECTED, PublicShellExit.RUNTIME, result.failure)
    }
}

private fun waitDescription(wait: HookWait): String =
    when (wait) {
        HookWait.Background -> "background"
        HookWait.Configured -> "configured by KAST_WORKSPACE_PREPARATION_WAIT or saved configuration"
        is HookWait.Selected -> wait.allowance.protocolMillis()?.let { "bounded: ${it}ms" } ?: "indefinite"
    }

private fun acceptedHookOutput(host: HookHost, state: HookPreparation): PublicPreparedOutput {
    val mode =
        when (state.wait.mode) {
            HookWaitMode.BOUNDED -> "bounded (${state.wait.timeoutMillis} ms)"
            HookWaitMode.INDEFINITE -> "indefinite (no preparation deadline)"
        }
    val text =
        when (state) {
            is HookPreparation.Ready -> "Kast workspace is ready. Wait mode: $mode."
            is HookPreparation.Preparing ->
                "Kast workspace is being prepared (${state.stage}); this is pending. " +
                    "Startup continues in the background. Native preparation allowance: $mode. " +
                    "Use kast workspace wait --wait TIMEOUT_MS for a bounded wait, " +
                    "or kast workspace wait --wait indefinite. " +
                    "Waiting observes native failure, lifecycle changes, editor closure and cancellation."
        }
    return hookContext(host, text, HookAccepted(state, text))
}

private fun hookContext(host: HookHost, context: String, reply: HookAccepted? = null): PublicPreparedOutput =
    PublicPreparedOutput(
        when (host) {
            HookHost.CLI ->
                if (reply == null) hookJson.encodeToString(HookSkipped(context)) else hookJson.encodeToString(reply)
            HookHost.CODEX -> hookJson.encodeToString(CodexHookOutput(CodexHookContext(context)))
            HookHost.COPILOT -> hookJson.encodeToString(ContextHookOutput(context))
        },
        PublicShellExit.COMPLETE,
    )

private fun hookRejected(failure: HookFailure, exit: PublicShellExit, cause: HookPreparationFailure? = null) =
    PublicPreparedOutput(hookJson.encodeToString(HookRejected(failure, cause)), exit)

private fun HookPreparationFailure.isPreparationTimeout(): Boolean =
    when (this) {
        is HookPreparationFailure.Native ->
            when (val native = failure) {
                is DaemonOperationFailure.Preparation ->
                    native.reason ==
                        io.github.amichne.kast.appserver.runtime.WorkspacePreparationFailure.DEADLINE_EXCEEDED
                is DaemonOperationFailure.Workspace ->
                    (native.cause as? io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause.Lifecycle)
                        ?.reason == io.github.amichne.kast.protocol.contract.IdeLifecycleFailure.DEADLINE_EXCEEDED
                else -> false
            }
        is HookPreparationFailure.Lifecycle ->
            result.reason == io.github.amichne.kast.protocol.contract.IdeLifecycleFailure.DEADLINE_EXCEEDED
        is HookPreparationFailure.Installation,
        HookPreparationFailure.Stopped,
        HookPreparationFailure.Unproven -> false
    }
