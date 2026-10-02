package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.PreviousInstallationPayload
import io.github.amichne.kast.distribution.contract.configuration.InstallationOperationalLimits
import io.github.amichne.kast.distribution.managed.writeInstallationReplacementReceipt
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlinx.serialization.SerializationException

/** Restores only the prior control using the existing replacement transaction and admitted child owner. */
internal object ControlInstallationRecovery {
    fun afterFailure(plan: VerifiedInstallationPlan, failure: InstallationFailure): InstallationOutcome {
        if (plan.request.controlOnly != InstallationSwitch.ENABLED) return InstallationOutcome.Rejected(failure)
        val transaction = plan.request.installRoot.value.resolve("recovery/replacement")
        if (Files.notExists(transaction, LinkOption.NOFOLLOW_LINKS)) return InstallationOutcome.Rejected(failure)
        return rollbackActivatedControl(plan, failure)
    }

    fun installedHostAdmission(
        plan: VerifiedInstallationPlan,
        stage: InstallationChildStage,
    ): InstallationChildOutcome =
        executeInstallationChild(
            stage,
            listOf(plan.targetRoot.resolve("share/kast/libexec/kast-service").toString(), "host-admission"),
            controlChildEnvironment(plan),
        )

    fun controlChildEnvironment(plan: VerifiedInstallationPlan): Map<String, String> =
        mapOf(
            "HOME" to plan.request.home.value.toString(),
            "KAST_OPTS" to plan.request.jvmUserHomeOption.value,
            "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
            "CODEX_HOME" to plan.request.codexHome.value.toString(),
            "JAVA_HOME" to plan.request.javaHome.value.toString(),
            "KAST_CONFIGURATION_FILE" to plan.targetRoot.resolve("config/environment").toString(),
        )

    fun restoreRunningPrior(
        plan: VerifiedInstallationPlan,
        prior: Path,
        failure: InstallationFailure,
    ): InstallationOutcome.Recovery {
        val restarted =
            executeInstallationChild(
                InstallationChildStage.CONTROL_RECOVERY,
                listOf(prior.resolve("share/kast/libexec/kast-service").toString(), "enable"),
                controlChildEnvironment(plan),
            )
        if (restarted != InstallationChildOutcome.COMPLETED)
            return InstallationOutcome.RecoveryRequired(failure, InstallationFailure.CONTROL_RECOVERY_REJECTED)
        val admitted =
            executeInstallationChild(
                InstallationChildStage.RECOVERY_HOST_ADMISSION,
                listOf(prior.resolve("share/kast/libexec/kast-service").toString(), "host-admission"),
                controlChildEnvironment(plan),
            )
        return if (admitted == InstallationChildOutcome.COMPLETED) InstallationOutcome.RolledBack(failure)
        else InstallationOutcome.RecoveryRequired(failure, InstallationFailure.RECOVERY_HOST_ADMISSION_REJECTED)
    }

    fun rollbackActivatedControl(
        plan: VerifiedInstallationPlan,
        failure: InstallationFailure,
    ): InstallationOutcome =
        try {
            FileChannel.open(
                    plan.request.installRoot.value.resolve("activation.lock"),
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
                .use { channel ->
                    val lock =
                        acquireInstallationLock(channel)
                            ?: return InstallationOutcome.RecoveryRequired(
                                failure,
                                InstallationFailure.ACTIVATION_LOCK_REJECTED,
                            )
                    lock.use { rollbackActivatedControlLocked(plan, failure) }
                }
        } catch (_: IOException) {
            InstallationOutcome.RecoveryRequired(failure, InstallationFailure.FILESYSTEM_REJECTED)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            InstallationOutcome.RecoveryRequired(failure, InstallationFailure.INTERRUPTED)
        } catch (_: SecurityException) {
            InstallationOutcome.RecoveryRequired(failure, InstallationFailure.FILESYSTEM_REJECTED)
        }

    private fun rollbackActivatedControlLocked(
        plan: VerifiedInstallationPlan,
        failure: InstallationFailure,
    ): InstallationOutcome {
        return try {
            val replacement =
                when (val admitted = AdmittedControlReplacement.admit(plan)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return InstallationOutcome.RecoveryRequired(failure, admitted.failure)
                }
            val retired =
                executeInstallationChild(
                    InstallationChildStage.CONTROL_RECOVERY_RETIREMENT,
                    listOf(plan.targetRoot.resolve("share/kast/libexec/kast-service").toString(), "disable"),
                    controlChildEnvironment(plan),
                )
            if (retired != InstallationChildOutcome.COMPLETED)
                return InstallationOutcome.RecoveryRequired(failure, InstallationFailure.CONTROL_RECOVERY_REJECTED)
            replacement.restorePayload()
            val complete = restoreRunningControl(plan, failure)
            replacement.complete(complete)
            complete
        } catch (_: IOException) {
            InstallationOutcome.RecoveryRequired(failure, InstallationFailure.FILESYSTEM_REJECTED)
        } catch (_: SerializationException) {
            InstallationOutcome.RecoveryRequired(failure, InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
        }
    }

    private fun restoreRunningControl(
        plan: VerifiedInstallationPlan,
        failure: InstallationFailure,
    ): InstallationOutcome.Recovery =
        when (val restored = restoreRunningPrior(plan, plan.targetRoot, failure)) {
            is InstallationOutcome.RolledBack ->
                if (
                    failure == InstallationFailure.CONTROL_FINALIZATION_REJECTED &&
                        restorePublicControl(plan) != InstallationChildOutcome.COMPLETED
                )
                    InstallationOutcome.RecoveryRequired(
                        failure,
                        InstallationFailure.CONTROL_PUBLICATION_RECOVERY_REJECTED,
                    )
                else restored
            is InstallationOutcome.RecoveryRequired -> restored
        }

    private fun restorePublicControl(plan: VerifiedInstallationPlan): InstallationChildOutcome =
        executeInstallationChild(
            InstallationChildStage.CONTROL_PUBLICATION_RECOVERY,
            listOf(
                plan.targetRoot.resolve("share/kast/libexec/kast-management").toString(),
                "--internal-install",
                "commit",
            ),
            controlChildEnvironment(plan) + mapOf("KAST_INSTALL_ROOT" to plan.request.installRoot.value.toString()),
        )
}

/** Exact receipt-owned candidate and prior payload paths, admitted before any retirement or move. */
private class AdmittedControlReplacement
private constructor(
    private val candidate: Path,
    private val transaction: Path,
    private val receipt: InstallationReplacementReceipt,
    private val payload: Path,
    private val priorRecovery: Path,
    private val rejected: Path,
) {
    fun restorePayload() {
        moveInstallationPayload(candidate, rejected)
        moveInstallationPayload(payload, candidate)
        val currentRecovery = transaction.parent.resolve("installation")
        if (Files.exists(currentRecovery, LinkOption.NOFOLLOW_LINKS)) deleteTree(currentRecovery)
        moveInstallationPayload(priorRecovery, currentRecovery)
    }

    fun complete(outcome: InstallationOutcome.Recovery) {
        when (outcome) {
            is InstallationOutcome.RolledBack -> deleteTree(transaction)
            is InstallationOutcome.RecoveryRequired ->
                writeInstallationReplacementReceipt(
                    transaction,
                    receipt.copy(stage = InstallationReplacementStage.RECOVERY_REQUIRED),
                )
        }
    }

    companion object {
        fun admit(plan: VerifiedInstallationPlan): Refinement<AdmittedControlReplacement, InstallationFailure> {
            val transaction = plan.request.installRoot.value.resolve("recovery/replacement")
            val raw =
                readInstallationBounded(
                    transaction.resolve("receipt.json"),
                    ControlDistributionLimits.maximumManifestBytes.toLong(),
                ) ?: return Refinement.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
            val receipt = installationManifestJson.decodeFromString(InstallationReplacementReceipt.serializer(), raw)
            val previous =
                receipt.previous as? PreviousInstallationPayload.Physical
                    ?: return Refinement.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
            if (!candidateMatches(plan.targetRoot, receipt) || !priorPathsMatch(plan.targetRoot, transaction, previous))
                return Refinement.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
            val payload = transaction.resolve("payload")
            if (previous.identity != observeInstallationFilesystemIdentity(payload))
                return Refinement.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
            val rejected = transaction.resolve("rejected-control")
            if (!Files.notExists(rejected, LinkOption.NOFOLLOW_LINKS))
                return Refinement.Rejected(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
            return Refinement.Refined(
                AdmittedControlReplacement(
                    candidate = plan.targetRoot,
                    transaction = transaction,
                    receipt = receipt,
                    payload = payload,
                    priorRecovery = transaction.resolve("recovery"),
                    rejected = rejected,
                )
            )
        }

        private fun candidateMatches(candidate: Path, receipt: InstallationReplacementReceipt): Boolean =
            receipt.installation == candidate.toString() &&
                receipt.installationIdentity == observeInstallationFilesystemIdentity(candidate)

        private fun priorPathsMatch(
            candidate: Path,
            transaction: Path,
            prior: PreviousInstallationPayload.Physical,
        ): Boolean =
            prior.installation == candidate.toString() &&
                prior.payload == transaction.resolve("payload").toString() &&
                prior.recovery == transaction.resolve("recovery").toString()
    }
}

internal fun acquireInstallationLock(channel: FileChannel): java.nio.channels.FileLock? {
    val deadline =
        System.nanoTime() + Duration.ofMillis(InstallationOperationalLimits.activationLockTimeoutMillis).toNanos()
    while (System.nanoTime() < deadline) {
        val lock =
            try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
        if (lock != null) return lock
        Thread.sleep(InstallationOperationalLimits.activationLockPollMillis)
    }
    return null
}
