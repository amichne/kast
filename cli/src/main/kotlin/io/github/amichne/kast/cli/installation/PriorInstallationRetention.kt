package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.InstalledEpochRetention
import io.github.amichne.kast.appserver.InstalledWorkspaceRegistryRetention
import io.github.amichne.kast.appserver.WorkspaceRegistryRetention
import io.github.amichne.kast.distribution.managed.InstallationSnapshot
import io.github.amichne.kast.distribution.managed.InstallationSnapshotKind
import io.github.amichne.kast.distribution.managed.copyInstallationSnapshot
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Retain only the inspected, retired prior's registry, state and qualified installation epoch. */
internal object PriorInstallationRetention {
    fun retain(plan: VerifiedInstallationPlan, prior: Path, staged: Path): Refinement<Unit, InstallationFailure> {
        val registry = prior.resolve("config/workspaces.json")
        if (!Files.notExists(registry, LinkOption.NOFOLLOW_LINKS)) {
            val retained =
                InstalledWorkspaceRegistryRetention.retain(registry, staged.resolve("config/workspaces.json"))
            reportRegistryRetention(retained)
            if (retained is WorkspaceRegistryRetention.Rejected)
                return Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED)
        }
        if (plan.request.controlOnly == InstallationSwitch.ENABLED) return Refinement.Refined(Unit)
        val state = prior.resolve("state")
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            when (
                val copied = copyInstallationSnapshot(state, staged.resolve("state"), InstallationSnapshotKind.STATE)
            ) {
                InstallationSnapshot.Copied -> Unit
                is InstallationSnapshot.Rejected -> return Refinement.Rejected(copied.failure.installationFailure())
            }
        }
        return when (val epoch = InstalledEpochRetention.retain(prior, staged, plan.targetRoot)) {
            InstalledEpochRetention.Absent,
            InstalledEpochRetention.Preserved,
            InstalledEpochRetention.Regenerate -> Refinement.Refined(Unit)
            is InstalledEpochRetention.Rejected -> Refinement.Rejected(epoch.failure.installationFailure())
        }
    }
}
