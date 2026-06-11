package io.github.amichne.kast.idea.actions

import java.nio.file.Path

internal class InstallCopilotExtensionAction : KastInstallAction() {
    override fun buildArgs(workspaceRoot: Path): List<String> = listOf(
        "install",
        "copilot-extension",
        "--target-dir=${workspaceRoot.resolve(".github")}",
        "--force",
    )

    override fun successMessage(workspaceRoot: Path): String =
        "Installed Kast Copilot extension for ${workspaceRoot.fileName}"
}
