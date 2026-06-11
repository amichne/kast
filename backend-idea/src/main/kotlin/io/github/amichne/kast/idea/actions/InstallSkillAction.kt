package io.github.amichne.kast.idea.actions

import java.nio.file.Path

internal class InstallSkillAction : KastInstallAction() {
    override fun buildArgs(workspaceRoot: Path): List<String> = listOf(
        "install",
        "skill",
        "--force",
    )

    override fun successMessage(workspaceRoot: Path): String =
        "Installed Kast skill for ${workspaceRoot.fileName}"
}
