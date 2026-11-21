package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.encodeToString

internal fun presentProvisioning(
    outcome: ProvisionOutcome,
    jsonRequested: Boolean,
    output: ManagementOutput = ManagementOutput(),
) {
    val json = output.structured(jsonRequested)
    if (json) output.publish(managementJson.encodeToString(outcome))
    when (outcome) {
        is ProvisionOutcome.Installed -> {
            if (!json) println("Installed Kast guidance at ${outcome.destination}")
            System.err.println(FRESH_GUIDANCE_SESSION)
        }
        is ProvisionOutcome.AlreadyInstalled -> {
            System.err.println("kast: guidance at ${outcome.destination} is already installed")
            System.err.println(FRESH_GUIDANCE_SESSION)
            if (!json) println("ALREADY_INSTALLED ${outcome.destination}")
        }
        is ProvisionOutcome.Rejected -> {
            if (!json) println("REJECTED ${outcome.stage} ${outcome.reason}")
            System.err.println("kast: ${outcome.stage}: ${outcome.reason}: ${outcome.destination}")
            if (outcome.reason == ProvisionFailure.SKILL_EXISTS)
                System.err.println("kast: select another --output directory or explicitly remove the existing skill")
            kotlin.system.exitProcess(1)
        }
        is ProvisionOutcome.RecoveryRequired -> {
            if (!json) println("RECOVERY_REQUIRED ${outcome.stage} ${outcome.reason}")
            System.err.println(
                "kast: ${outcome.stage}: ${outcome.reason}: ${outcome.destination}; " +
                    "inspect provider resources before another setup"
            )
            kotlin.system.exitProcess(1)
        }
    }
}

private const val FRESH_GUIDANCE_SESSION = "kast: start a fresh agent session to use the selected guidance"
