package io.github.amichne.kast.cli.command.change

import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.command.CommandFamily
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.SemanticKastCommand
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest

internal fun changeCommandGroup(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput,
): CommandFamily {
    val plan = SemanticKastCommand(
        name = "plan",
        operation = CanonicalOperation.CHANGE_PLAN,
        schemaUsage = "change plan < request.json",
        description = "Plan one change from a canonical JSON request on standard input.",
        serializer = ChangePlanRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.changePlan,
    )
    val apply = SemanticKastCommand(
        name = "apply",
        operation = CanonicalOperation.CHANGE_APPLY,
        schemaUsage = "change apply < request.json",
        description = "Apply one plan from a canonical JSON request on standard input.",
        serializer = ChangeApplyRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.changeApply,
    )
    val recover = SemanticKastCommand(
        name = "recover",
        operation = CanonicalOperation.CHANGE_RECOVER,
        schemaUsage = "change recover < request.json",
        description = "Recover one plan from a canonical JSON request on standard input.",
        serializer = ChangeRecoverRequest.serializer(),
        requestInput = requestInput,
        preparer = preparers.changeRecover,
    )
    return CommandFamily(
        KastCommandGroup("change", "Plan, apply, and recover semantic changes.")
            .subcommands(plan, apply, recover),
        listOf(plan, apply, recover),
    )
}
