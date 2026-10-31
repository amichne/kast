package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private data class PiSkillExchange(
    val destination: Path,
    val staged: Path,
    val previous: List<GuidanceFile>,
    val skill: AdmittedCliSkill,
)

private sealed interface PiStageOutcome {
    data class Disposable(val outcome: ProvisionOutcome) : PiStageOutcome

    data class Kept(val outcome: ProvisionOutcome.RecoveryRequired) : PiStageOutcome
}

internal fun refreshPiSkill(
    root: Path,
    destination: Path,
    previous: List<GuidanceFile>,
    commit: (List<GuidanceFile>) -> Unit,
): ProvisionOutcome {
    val skill =
        when (val admitted = AdmittedCliSkill.admit(root)) {
            is SkillAdmission.Admitted -> admitted.skill
            is SkillAdmission.Rejected ->
                return ProvisionOutcome.Rejected(
                    ProvisionStage.PAYLOAD_ADMISSION,
                    admitted.reason,
                    destination.toString(),
                )
        }
    if (skill.identical(destination)) return ProvisionOutcome.AlreadyInstalled(destination.toString())
    val staged = Files.createTempDirectory(destination.parent, ".kast-guidance-")
    val exchange = PiSkillExchange(destination, staged, previous, skill)
    val result =
        try {
            skill.materialize(staged)
            if (matchesGuidance(destination, previous)) replacePiSkill(exchange, commit)
            else
                PiStageOutcome.Disposable(
                    ProvisionOutcome.Rejected(
                        ProvisionStage.RESOURCE_INSPECTION,
                        ProvisionFailure.PROVIDER_CONFLICT,
                        destination.toString(),
                    )
                )
        } catch (_: IOException) {
            PiStageOutcome.Disposable(
                ProvisionOutcome.Rejected(
                    ProvisionStage.STAGING,
                    ProvisionFailure.DESTINATION_UNAVAILABLE,
                    destination.toString(),
                )
            )
        }
    return finishPiStage(exchange, result)
}

private fun replacePiSkill(exchange: PiSkillExchange, commit: (List<GuidanceFile>) -> Unit): PiStageOutcome =
    when (exchangeDirectories(exchange.staged, exchange.destination)) {
        DirectoryPublication.PUBLISHED -> settlePiSkill(exchange, commit)
        DirectoryPublication.UNSUPPORTED ->
            PiStageOutcome.Disposable(
                ProvisionOutcome.Rejected(
                    ProvisionStage.PUBLICATION,
                    ProvisionFailure.PLATFORM_UNSUPPORTED,
                    exchange.destination.toString(),
                )
            )
        DirectoryPublication.TARGET_EXISTS,
        DirectoryPublication.IO_FAILURE ->
            PiStageOutcome.Disposable(
                ProvisionOutcome.Rejected(
                    ProvisionStage.PUBLICATION,
                    ProvisionFailure.DESTINATION_UNAVAILABLE,
                    exchange.destination.toString(),
                )
            )
    }

private fun settlePiSkill(exchange: PiSkillExchange, commit: (List<GuidanceFile>) -> Unit): PiStageOutcome =
    try {
        if (!matchesGuidance(exchange.staged, exchange.previous) || !exchange.skill.identical(exchange.destination))
            restorePiSkill(exchange)
        else
            when (val inventory = guidanceInventory(exchange.destination)) {
                is GuidanceInventoryRead.Rejected ->
                    retainedPiStage(exchange, ProvisionStage.RESOURCE_INSPECTION, inventory.reason)
                is GuidanceInventoryRead.Read -> {
                    commit(inventory.files)
                    PiStageOutcome.Disposable(ProvisionOutcome.Installed(exchange.destination.toString()))
                }
            }
    } catch (_: IOException) {
        retainedPiStage(exchange, ProvisionStage.PUBLICATION, ProvisionFailure.DESTINATION_UNAVAILABLE)
    }

private fun restorePiSkill(exchange: PiSkillExchange): PiStageOutcome =
    if (
        exchange.skill.identical(exchange.destination) &&
            exchangeDirectories(exchange.staged, exchange.destination) == DirectoryPublication.PUBLISHED
    )
        PiStageOutcome.Disposable(
            ProvisionOutcome.Rejected(
                ProvisionStage.RESOURCE_INSPECTION,
                ProvisionFailure.PROVIDER_CONFLICT,
                exchange.destination.toString(),
            )
        )
    else retainedPiStage(exchange, ProvisionStage.RESOURCE_INSPECTION, ProvisionFailure.RESOURCE_MISMATCH)

private fun retainedPiStage(
    exchange: PiSkillExchange,
    stage: ProvisionStage,
    reason: ProvisionFailure,
): PiStageOutcome.Kept =
    PiStageOutcome.Kept(ProvisionOutcome.RecoveryRequired(stage, reason, exchange.staged.toString()))

private fun finishPiStage(exchange: PiSkillExchange, result: PiStageOutcome): ProvisionOutcome =
    when (result) {
        is PiStageOutcome.Kept -> result.outcome
        is PiStageOutcome.Disposable ->
            try {
                Files.walk(exchange.staged).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                result.outcome
            } catch (_: IOException) {
                ProvisionOutcome.RecoveryRequired(
                    ProvisionStage.PUBLICATION,
                    ProvisionFailure.DESTINATION_UNAVAILABLE,
                    exchange.staged.toString(),
                )
            }
    }
