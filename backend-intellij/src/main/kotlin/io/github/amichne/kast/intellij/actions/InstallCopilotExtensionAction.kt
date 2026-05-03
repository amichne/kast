package io.github.amichne.kast.intellij.actions

import java.nio.file.Path

internal class InstallCopilotExtensionAction : KastInstallAction() {
    override fun buildArgs(workspaceRoot: Path): List<String> = listOf(
        "install",
        "copilot-extension",
        "--target-dir=${workspaceRoot.resolve(".github")}",
        "--yes=true",
    )

    override fun successMessage(workspaceRoot: Path): String =
        "Installed Kast Copilot extension for ${workspaceRoot.fileName}"
}
