package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private data class PiGuidanceTarget(val directory: ConnectionDirectory, val composition: HarnessComposition) {
    val skill: Path
        get() = directory.path.resolve("skills/kast")
}

private sealed interface PiSkillCompletion {
    data class Changed(val outcome: ProvisionOutcome.Installed) : PiSkillCompletion

    data class Unchanged(val outcome: ProvisionOutcome.AlreadyInstalled) : PiSkillCompletion
}

internal fun installPiGuidance(
    root: Path,
    home: Path,
    composition: HarnessComposition,
    environment: Map<String, String>,
): ProvisionOutcome {
    val rawHome = environment["PI_CODING_AGENT_DIR"] ?: home.resolve(".pi/agent").toString()
    val directory =
        when (val admission = ConnectionDirectory.admit(rawHome)) {
            is ConnectionDirectoryAdmission.Admitted -> admission.directory
            is ConnectionDirectoryAdmission.Rejected ->
                return ProvisionOutcome.Rejected(
                    ProvisionStage.DESTINATION_ADMISSION,
                    ProvisionFailure.DESTINATION_UNAVAILABLE,
                    rawHome,
                )
        }
    val target = PiGuidanceTarget(directory, composition)
    return when (val skill = provisionPiSkill(root, home, target)) {
        is ProvisionOutcome.Installed -> finishPiComposition(root, home, target, PiSkillCompletion.Changed(skill))
        is ProvisionOutcome.AlreadyInstalled ->
            finishPiComposition(root, home, target, PiSkillCompletion.Unchanged(skill))
        is ProvisionOutcome.Rejected,
        is ProvisionOutcome.RecoveryRequired -> skill
    }
}

private fun provisionPiSkill(root: Path, home: Path, target: PiGuidanceTarget): ProvisionOutcome =
    try {
        withRegistrationLock(root) { provisionAdmittedPiSkill(root, home, target) }
    } catch (_: ManagementRejected) {
        ProvisionOutcome.Rejected(
            ProvisionStage.PROVIDER_INSPECTION,
            ProvisionFailure.PROVIDER_CONFLICT,
            target.skill.toString(),
        )
    } catch (_: IOException) {
        ProvisionOutcome.RecoveryRequired(
            ProvisionStage.PUBLICATION,
            ProvisionFailure.DESTINATION_UNAVAILABLE,
            target.skill.toString(),
        )
    }

private fun provisionAdmittedPiSkill(root: Path, home: Path, target: PiGuidanceTarget): ProvisionOutcome {
    val receipt = requireOwnedExecutable(root)
    val extension = target.directory.path.resolve("extensions/kast.ts")
    when (admitPiExtension(receipt, extension)) {
        is PluginObservation.Observed -> Unit
        is PluginObservation.Rejected ->
            return ProvisionOutcome.Rejected(
                ProvisionStage.PROVIDER_INSPECTION,
                ProvisionFailure.PROVIDER_CONFLICT,
                extension.toString(),
            )
    }
    val prior = receipt.guidance.singleOrNull { it.harness == Harness.PI }
    if (prior != null && !admitPriorPiSkill(target.skill, prior))
        return ProvisionOutcome.Rejected(
            ProvisionStage.RESOURCE_INSPECTION,
            ProvisionFailure.PROVIDER_CONFLICT,
            target.skill.toString(),
        )
    when (val prepared = preparePiMcp(root, home, target.composition)) {
        is PluginObservation.Observed -> Unit
        is PluginObservation.Rejected ->
            return ProvisionOutcome.Rejected(
                ProvisionStage.DEPENDENCY_PREPARATION,
                pluginFailure(prepared.failure),
                target.skill.toString(),
            )
    }
    val result =
        if (prior != null) refreshPiSkill(root, target.skill, prior.files) { commitPiGuidance(root, target.skill, it) }
        else exportCliSkill(root, target.skill.parent)
    return commitPiSkillResult(root, target.skill, result)
}

private fun commitPiSkillResult(root: Path, destination: Path, result: ProvisionOutcome): ProvisionOutcome =
    when (result) {
        is ProvisionOutcome.Installed,
        is ProvisionOutcome.AlreadyInstalled ->
            when (val inventory = guidanceInventory(destination)) {
                is GuidanceInventoryRead.Read -> {
                    commitPiGuidance(root, destination, inventory.files)
                    result
                }
                is GuidanceInventoryRead.Rejected ->
                    ProvisionOutcome.RecoveryRequired(
                        ProvisionStage.RESOURCE_INSPECTION,
                        inventory.reason,
                        destination.toString(),
                    )
            }
        is ProvisionOutcome.Rejected,
        is ProvisionOutcome.RecoveryRequired -> result
    }

private fun finishPiComposition(
    root: Path,
    home: Path,
    target: PiGuidanceTarget,
    completed: PiSkillCompletion,
): ProvisionOutcome =
    try {
        val changed =
            if (target.composition == HarnessComposition.CliWithMcp)
                !connectHarness(
                    root,
                    home,
                    HarnessConnection.PI,
                    ownership = RegistrationOwnership.REQUIRE_OWNED,
                    directory = target.directory,
                )
            else disconnectHarness(root, home, Harness.PI)
        if (changed) ProvisionOutcome.Installed(target.skill.toString())
        else
            when (completed) {
                is PiSkillCompletion.Changed -> completed.outcome
                is PiSkillCompletion.Unchanged -> completed.outcome
            }
    } catch (_: ManagementRejected) {
        ProvisionOutcome.RecoveryRequired(
            ProvisionStage.PROVIDER_INSTALLATION,
            ProvisionFailure.PROVIDER_REJECTED,
            target.skill.toString(),
        )
    }

private fun admitPiExtension(receipt: ManagementReceipt, extension: Path): PluginObservation<Unit> {
    val prior = receipt.registrations.singleOrNull { it.connection == HarnessConnection.PI }
    if (!Files.exists(extension, NOFOLLOW_LINKS)) return PluginObservation.Observed(Unit)
    if (prior == null || prior.destination != extension.toString())
        return PluginObservation.Rejected(PluginInstallFailure.LegacyRegistrationRejected)
    return if (Files.isRegularFile(extension, NOFOLLOW_LINKS) && sha256(extension) == prior.payloadSha256)
        PluginObservation.Observed(Unit)
    else PluginObservation.Rejected(PluginInstallFailure.LegacyRegistrationRejected)
}

private fun admitPriorPiSkill(destination: Path, prior: ManagedGuidance): Boolean =
    prior.destination == destination.toString() && matchesGuidance(destination, prior.files)

private fun preparePiMcp(root: Path, home: Path, composition: HarnessComposition): PluginObservation<Unit> {
    if (composition == HarnessComposition.CliOnly) return PluginObservation.Observed(Unit)
    return when (val admission = AdmittedPluginBundle.admit(root, composition)) {
        is PluginObservation.Rejected -> admission
        is PluginObservation.Observed -> prepareOptionalMcp(admission.value, home)
    }
}

private fun commitPiGuidance(root: Path, destination: Path, files: List<GuidanceFile>) {
    val receipt = admittedReceipt(root)
    val next = ManagedGuidance(Harness.PI, destination.toString(), files)
    if (receipt.guidance.singleOrNull { it.harness == Harness.PI } != next)
        writeManagementReceipt(
            root,
            receipt.copy(guidance = receipt.guidance.filter { it.harness != Harness.PI } + next),
        )
}
