package io.github.amichne.kast.idea.actions

import java.nio.file.Path

internal class UninstallCopilotExtensionAction : KastInstallAction() {
    override fun buildArgs(workspaceRoot: Path): List<String> = listOf(
        "uninstall",
        "copilot-extension",
        "--target-dir=${workspaceRoot.resolve(".github")}",
    )

    override fun successMessage(workspaceRoot: Path): String =
        "Uninstalled Kast Copilot extension for ${workspaceRoot.fileName}"
}
