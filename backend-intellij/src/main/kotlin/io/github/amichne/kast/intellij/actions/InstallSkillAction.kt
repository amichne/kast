package io.github.amichne.kast.intellij.actions

import java.nio.file.Path

internal class InstallSkillAction : KastInstallAction() {
    override fun buildArgs(workspaceRoot: Path): List<String> = listOf(
        "install",
        "skill",
        "--yes=true",
    )

    override fun successMessage(workspaceRoot: Path): String =
        "Installed Kast skill for ${workspaceRoot.fileName}"
}
