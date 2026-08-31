package io.github.amichne.kast.cli.command.product

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliProductCommand
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.LocalCommandFamily
import io.github.amichne.kast.cli.command.LocalKastCommand

internal fun productCommandGroup(): LocalCommandFamily {
    val inspect = ProductInspectCommand()
    return LocalCommandFamily(
        ProductCommand().subcommands(inspect),
        listOf(inspect),
    )
}

private class ProductCommand : KastCommandGroup(
    "product",
    "Inspect the installed control product and local IDE endpoint evidence.",
)

private class ProductInspectCommand : LocalKastCommand(
    "inspect",
    CliProductCommand.INSPECT,
) {
    override fun help(context: Context): String =
        "Inspect control identity and endpoint evidence without compatible runtime admission."

    override fun resolveAction(): CliActionResolution = CliActionResolution.Selected(
        CliAction.Local.ProductInspect,
    )
}
