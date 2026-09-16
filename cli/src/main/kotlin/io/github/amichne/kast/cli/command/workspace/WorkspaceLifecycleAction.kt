package io.github.amichne.kast.cli.command.workspace

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliProductCommand
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CliUsageFailure
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.LocalCommandFamily
import io.github.amichne.kast.cli.command.LocalKastCommand
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface WorkspaceLifecycleAction {
    data class Open(val root: String, val requestId: String, val client: String) : WorkspaceLifecycleAction

    data class ApprovedClose(
        val invocation: io.github.amichne.kast.protocol.contract.ApprovedProjectCloseInvocation,
        val client: String,
    ) : WorkspaceLifecycleAction

    data class Control(val command: WorkspaceLifecycleRequest, val client: String) : WorkspaceLifecycleAction
}

internal fun workspaceLifecycleCommands(requestInput: CliRequestDocumentInput): LocalCommandFamily {
    val commands = listOf(WorkspaceOpenCommand(), WorkspaceControlCommand(requestInput))
    return LocalCommandFamily(
        KastCommandGroup("workspace", "Explicit local IDEA project lifecycle; background opening is best effort.")
            .subcommands(commands),
        commands,
    )
}

private class WorkspaceOpenCommand : LocalKastCommand("open", CliProductCommand.WORKSPACE_OPEN) {
    private val root by argument("ROOT")
    private val requestId by option("--request-id", help = "Idempotency identity; defaults to a fresh UUID.")
    private val client by option("--client", help = "Stable caller identity for project use and release.")

    override fun help(context: Context) =
        "Reuse or open an exact worktree in the selected IDEA, with quiet initial import."

    override fun resolveAction() =
        CliActionResolution.Selected(
            CliAction.Local.WorkspaceLifecycle(
                WorkspaceLifecycleAction.Open(root, requestId ?: UUID.randomUUID().toString(), client ?: "cli")
            )
        )
}

private class WorkspaceControlCommand(private val requestInput: CliRequestDocumentInput) :
    LocalKastCommand("lifecycle", CliProductCommand.WORKSPACE_LIFECYCLE) {
    private val document by
        argument("DOCUMENT", help = "Tagged lifecycle document; defaults to standard input.").optional()
    private val client by
        option(
            "--client",
            "--lifecycle-client",
            help = "Stable caller identity; agents use their coordinator identity.",
        )
    private val approvedClose by option("--lifecycle-approved-close", hidden = true).flag()

    override fun help(context: Context) =
        "Inspect the host or perform an explicit lifecycle action using its exact returned identity."

    override fun resolveAction(): CliActionResolution {
        val raw =
            document
                ?: when (
                    val supplied =
                        if (requestInput is CliRequestDocumentInput.Deferred) requestInput.read() else requestInput
                ) {
                    is CliRequestDocumentInput.Provided -> supplied.document
                    else -> return CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REQUIRED)
                }
        if (approvedClose) {
            val invocation =
                try {
                    Json.decodeFromString<io.github.amichne.kast.protocol.contract.ApprovedProjectCloseInvocation>(raw)
                } catch (_: SerializationException) {
                    return CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
                }
            return CliActionResolution.Selected(
                CliAction.Local.WorkspaceLifecycle(WorkspaceLifecycleAction.ApprovedClose(invocation, client ?: "cli"))
            )
        }
        val request =
            try {
                Json.decodeFromString<WorkspaceLifecycleRequest>(raw)
            } catch (_: SerializationException) {
                return CliActionResolution.UsageRejected(CliUsageFailure.RequestDocument.REJECTED)
            }
        return CliActionResolution.Selected(
            CliAction.Local.WorkspaceLifecycle(WorkspaceLifecycleAction.Control(request, client ?: "cli"))
        )
    }
}
