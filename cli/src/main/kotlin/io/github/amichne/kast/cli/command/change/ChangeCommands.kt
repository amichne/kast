package io.github.amichne.kast.cli.command.change

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliOptionValue
import io.github.amichne.kast.cli.command.CliUsageFailure
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.command.closedChoiceOption
import io.github.amichne.kast.cli.command.optionalOnce
import io.github.amichne.kast.cli.command.protocolTextOption
import io.github.amichne.kast.cli.command.requiredOnce
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.ProtocolText

internal fun changeCommandGroup(
    preparers: CanonicalCliRequestPreparers,
): CommandFamily {
    val commands = listOf(
        ChangePlanCommand(preparers),
        ChangeApplyCommand(preparers),
        ChangeVerifyCommand(preparers),
        ChangeRecoverCommand(preparers),
    )
    return CommandFamily(
        KastCommandGroup("change", "Plan, apply, verify, and recover typed changes.")
            .subcommands(commands),
        commands,
    )
}

private enum class ChangeIntent { ADD_FILE, ADD_DECLARATION, REPLACE_DECLARATION, RENAME_SYMBOL }

private class ChangePlanCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<ChangePlanRequest>(
    name = "plan",
    operation = CanonicalOperation.CHANGE_PLAN,
    schemaUsage = "change plan --intent <add-file|add-declaration|replace-declaration|rename-symbol> " +
                  "<intent-options>",
    preparer = preparers.changePlan,
) {
    private val intent by closedChoiceOption(
        "--intent",
        "intent",
        "Closed change intent.",
        linkedMapOf(
            "add-file" to ChangeIntent.ADD_FILE,
            "add-declaration" to ChangeIntent.ADD_DECLARATION,
            "replace-declaration" to ChangeIntent.REPLACE_DECLARATION,
            "rename-symbol" to ChangeIntent.RENAME_SYMBOL,
        ),
    ).requiredOnce()
    private val path by protocolTextOption("--path", "Workspace-relative file path.").optionalOnce()
    private val content by protocolTextOption("--content", "Complete file content.").optionalOnce()
    private val target by protocolTextOption("--target", "Exact target selector.").optionalOnce()
    private val declaration by protocolTextOption(
        "--declaration",
        "Declaration to add.",
    ).optionalOnce()
    private val replacement by protocolTextOption(
        "--replacement",
        "Replacement declaration.",
    ).optionalOnce()
    private val newName by protocolTextOption("--new-name", "Replacement symbol name.").optionalOnce()

    override fun help(context: Context): String =
        "Plan one closed change intent without writing the workspace."

    override fun helpEpilog(context: Context): String = """
        Intent contracts:
          add-file             --path <path> --content <text>
          add-declaration      --target <selector> --declaration <text>
          replace-declaration  --target <selector> --replacement <text>
          rename-symbol        --target <selector> --new-name <name>
    """.trimIndent()

    override fun resolveAction(): CliActionResolution = when (
        val refined = ChangePlanCliInput.refine(
            intent,
            path,
            content,
            target,
            declaration,
            replacement,
            newName,
        )
    ) {
        is Refinement.Refined -> prepare(refined.value)
        is Refinement.Rejected -> CliActionResolution.UsageRejected(refined.failure)
    }
}

private object ChangePlanCliInput {
    /**
     * Proof transition: `Clikt change-plan options -> Refinement<ChangePlanRequest,
     * CliUsageFailure.ChangePlan>`.
     *
     * Establishes exactly the fields owned by the selected closed change intent and constructs the
     * existing protocol request. [CliUsageFailure.ChangePlan] closes invalid option combinations.
     * Closed Clikt option-presence states are consumed only at this outer command boundary.
     */
    @Suppress("LongParameterList")
    fun refine(
        intent: ChangeIntent,
        path: CliOptionValue<ProtocolText>,
        content: CliOptionValue<ProtocolText>,
        target: CliOptionValue<ProtocolText>,
        declaration: CliOptionValue<ProtocolText>,
        replacement: CliOptionValue<ProtocolText>,
        newName: CliOptionValue<ProtocolText>,
    ): Refinement<ChangePlanRequest, CliUsageFailure.ChangePlan> {
        val document = when (intent) {
            ChangeIntent.ADD_FILE -> if (
                path is CliOptionValue.Present && content is CliOptionValue.Present &&
                target is CliOptionValue.Absent && declaration is CliOptionValue.Absent &&
                replacement is CliOptionValue.Absent && newName is CliOptionValue.Absent
            ) {
                ChangeIntentDocument.AddFile(path.value, content.value)
            } else {
                return rejected()
            }
            ChangeIntent.ADD_DECLARATION -> if (
                path is CliOptionValue.Absent && content is CliOptionValue.Absent &&
                target is CliOptionValue.Present && declaration is CliOptionValue.Present &&
                replacement is CliOptionValue.Absent && newName is CliOptionValue.Absent
            ) {
                ChangeIntentDocument.AddDeclaration(target.value, declaration.value)
            } else {
                return rejected()
            }
            ChangeIntent.REPLACE_DECLARATION -> if (
                path is CliOptionValue.Absent && content is CliOptionValue.Absent &&
                target is CliOptionValue.Present && declaration is CliOptionValue.Absent &&
                replacement is CliOptionValue.Present && newName is CliOptionValue.Absent
            ) {
                ChangeIntentDocument.ReplaceDeclaration(target.value, replacement.value)
            } else {
                return rejected()
            }
            ChangeIntent.RENAME_SYMBOL -> if (
                path is CliOptionValue.Absent && content is CliOptionValue.Absent &&
                target is CliOptionValue.Present && declaration is CliOptionValue.Absent &&
                replacement is CliOptionValue.Absent && newName is CliOptionValue.Present
            ) {
                ChangeIntentDocument.RenameSymbol(target.value, newName.value)
            } else {
                return rejected()
            }
        }
        return Refinement.Refined(ChangePlanRequest(document))
    }

    private fun rejected(): Refinement.Rejected<CliUsageFailure.ChangePlan> =
        Refinement.Rejected(CliUsageFailure.ChangePlan.OPTIONS_DO_NOT_MATCH_INTENT)
}

private class ChangeApplyCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<ChangeApplyRequest>(
    name = "apply",
    operation = CanonicalOperation.CHANGE_APPLY,
    schemaUsage = "change apply --plan <plan-identity>",
    preparer = preparers.changeApply,
) {
    private val plan by protocolTextOption("--plan", "Plan identity.").requiredOnce()

    override fun help(context: Context): String = "Apply one admitted change plan."

    override fun resolveAction(): CliActionResolution = prepare(ChangeApplyRequest(plan))
}

private class ChangeVerifyCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<ChangeVerifyRequest>(
    name = "verify",
    operation = CanonicalOperation.CHANGE_VERIFY,
    schemaUsage = "change verify --application <application-identity>",
    preparer = preparers.changeVerify,
) {
    private val application by protocolTextOption(
        "--application",
        "Application identity.",
    ).requiredOnce()

    override fun help(context: Context): String = "Verify one applied change."

    override fun resolveAction(): CliActionResolution = prepare(ChangeVerifyRequest(application))
}

private class ChangeRecoverCommand(
    preparers: CanonicalCliRequestPreparers,
) : SemanticKastCommand<ChangeRecoverRequest>(
    name = "recover",
    operation = CanonicalOperation.CHANGE_RECOVER,
    schemaUsage = "change recover --plan <plan-identity>",
    preparer = preparers.changeRecover,
) {
    private val plan by protocolTextOption("--plan", "Plan identity.").requiredOnce()

    override fun help(context: Context): String = "Recover one plan to a known workspace state."

    override fun resolveAction(): CliActionResolution = prepare(ChangeRecoverRequest(plan))
}
