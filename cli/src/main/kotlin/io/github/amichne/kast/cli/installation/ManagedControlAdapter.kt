package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.ManagedControlReservation
import io.github.amichne.kast.distribution.contract.PreviousInstallationPayload
import io.github.amichne.kast.distribution.managed.InstallationRecoveryAdmission
import io.github.amichne.kast.distribution.managed.InstallationRecoveryBaseline
import io.github.amichne.kast.distribution.managed.InstallationReplacementWrite
import io.github.amichne.kast.distribution.managed.admitInstallationRecovery
import io.github.amichne.kast.distribution.managed.writeInstallationReplacementReceipt
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.SerializationException

/** Uses the existing stage, retention, exchange, and journal owners without the old shutdown sequence. */
internal object ManagedControlAdapter {
    fun abort(request: InstallationRequest, transaction: String): ManagedControlOutcome =
        underLock(request) { plan -> abortLocked(plan, transaction) }

    private fun abortLocked(plan: VerifiedInstallationPlan, transaction: String): ManagedControlOutcome {
        val journal = plan.request.installRoot.value.resolve("recovery/replacement")
        if (!onlyPreparationRecord(journal))
            return ManagedControlOutcome.RecoveryRequired(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
        val candidate =
            when (
                val admitted = admitManagedControlCandidate(plan, transaction, ManagedControlCandidatePurpose.ABORT)
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return ManagedControlOutcome.RecoveryRequired(admitted.failure)
            }
        when (val prior = selectRecordedPrior(plan, candidate.receipt.previous)) {
            PriorSelection.Absent -> Unit
            PriorSelection.Rejected ->
                return ManagedControlOutcome.RecoveryRequired(InstallationFailure.PRIOR_SELECTION_REJECTED)
            is PriorSelection.Selected -> {
                when (val admitted = admitPrior(prior.root, candidate.staged, plan.request, PriorInspection.PAYLOAD)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return ManagedControlOutcome.RecoveryRequired(admitted.failure)
                }
            }
        }
        if (!onlyPreparationRecord(journal))
            return ManagedControlOutcome.RecoveryRequired(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
        deleteTree(candidate.staged)
        Files.delete(journal.resolve("receipt.json"))
        Files.delete(journal)
        return ManagedControlOutcome.Aborted(transaction)
    }

    private fun onlyPreparationRecord(journal: Path): Boolean =
        journal.toRealPath() == journal &&
            Files.list(journal).use { entries ->
                entries.map { it.fileName.toString() }.toList() == listOf("receipt.json")
            }

    fun prepare(request: InstallationRequest, transaction: String): ManagedControlOutcome =
        underLock(request) { plan -> prepareLocked(plan, transaction) }

    private fun prepareLocked(plan: VerifiedInstallationPlan, transaction: String): ManagedControlOutcome {
        val journal = plan.request.installRoot.value.resolve("recovery/replacement")
        if (Files.exists(journal, NOFOLLOW_LINKS)) return rejected(InstallationFailure.RECOVERY_REQUIRED)
        val prior =
            when (val selected = InstallationWorkflow.selectedInstallation(plan)) {
                PriorSelection.Absent -> null
                is PriorSelection.Selected -> selected.root
                PriorSelection.Rejected -> return rejected(InstallationFailure.PRIOR_SELECTION_REJECTED)
            }
        if (prior != null && admitInstallationRecovery(prior) !is InstallationRecoveryAdmission.Admitted)
            return rejected(InstallationFailure.RECOVERY_REQUIRED)
        val configuration =
            when (val selected = observeInstallationConfigurationSelection(plan, prior)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return rejected(InstallationFailure.CONFIGURATION_REJECTED)
            }
        val staged =
            when (val result = InstallationWorkflow.stage(plan, configuration)) {
                is StageResult.Complete -> result.root
                is StageResult.Rejected -> return rejected(result.failure)
            }
        return prepareCandidate(plan, prior, staged, transaction)
    }

    private fun prepareCandidate(
        plan: VerifiedInstallationPlan,
        prior: Path?,
        staged: Path,
        transaction: String,
    ): ManagedControlOutcome {
        val request = plan.request
        val journal = request.installRoot.value.resolve("recovery/replacement")
        var recorded = false
        try {
            if (InstallationWorkflow.qualifyCandidate(plan, staged) != InstallationChildOutcome.COMPLETED)
                return rejected(InstallationFailure.CANDIDATE_QUALIFICATION_REJECTED)
            if (prior != null) {
                when (val admitted = admitPrior(prior, staged, request, PriorInspection.PAYLOAD)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return rejected(admitted.failure)
                }
            }
            Files.createDirectories(journal.parent)
            Files.createDirectory(journal)
            Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rwx------"))
            val receipt = preparedReceipt(plan, prior, staged, transaction)
            if (writeInstallationReplacementReceipt(journal, receipt) != InstallationReplacementWrite.Written)
                return ManagedControlOutcome.RecoveryRequired(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
            recorded = true
            return ManagedControlOutcome.Prepared(transaction, staged.toString())
        } finally {
            if (!recorded) deleteTree(staged)
        }
    }

    private fun preparedReceipt(
        plan: VerifiedInstallationPlan,
        prior: Path?,
        staged: Path,
        transaction: String,
    ): InstallationReplacementReceipt =
        InstallationReplacementReceipt(
            stage = InstallationReplacementStage.PREPARED,
            installation = plan.targetRoot.toString(),
            installationIdentity = observeInstallationFilesystemIdentity(staged),
            previous =
                InstallationWorkflow.previousPayload(
                    plan,
                    prior,
                    plan.request.installRoot.value.resolve("recovery/replacement"),
                ),
            reservation =
                ManagedControlReservation(
                    transaction,
                    staged.toString(),
                    plan.payloadDigest.value,
                    plan.request.version.toString(),
                    digest(staged.resolve("installation.json"))?.value
                        ?: throw IOException("staged manifest unavailable"),
                ),
        )

    fun commit(request: InstallationRequest, transaction: String): ManagedControlOutcome =
        underLock(request) { plan ->
            val journal = request.installRoot.value.resolve("recovery/replacement")
            val candidate =
                when (
                    val admitted =
                        admitManagedControlCandidate(plan, transaction, ManagedControlCandidatePurpose.COMMIT)
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return@underLock rejected(admitted.failure)
                }
            val staged = candidate.staged
            val receipt = candidate.receipt
            val prior =
                when (val selected = selectRecordedPrior(plan, receipt.previous)) {
                    PriorSelection.Absent -> null
                    is PriorSelection.Selected -> selected.root
                    PriorSelection.Rejected -> return@underLock rejected(InstallationFailure.PRIOR_SELECTION_REJECTED)
                }
            val baseline =
                when (val kept = retainPrior(plan, prior, staged)) {
                    is Refinement.Refined -> kept.value
                    is Refinement.Rejected -> return@underLock rejected(kept.failure)
                }
            commitCandidate(ReplacementActivation(plan, journal, receipt), staged, baseline, transaction)
        }

    private fun retainPrior(
        plan: VerifiedInstallationPlan,
        prior: Path?,
        staged: Path,
    ): Refinement<InstallationRecoveryBaseline?, InstallationFailure> {
        if (prior == null) return Refinement.Refined(null)
        val baseline =
            when (val admitted = admitInstallationRecovery(prior)) {
                is InstallationRecoveryAdmission.Admitted -> admitted.baseline
                is InstallationRecoveryAdmission.Pending,
                InstallationRecoveryAdmission.Rejected ->
                    return Refinement.Rejected(InstallationFailure.RECOVERY_REQUIRED)
            }
        when (val admitted = admitPrior(prior, staged, plan.request)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        return when (val kept = PriorInstallationRetention.retain(plan, prior, staged)) {
            is Refinement.Refined -> Refinement.Refined(baseline)
            is Refinement.Rejected -> kept
        }
    }

    private fun selectRecordedPrior(
        plan: VerifiedInstallationPlan,
        previous: PreviousInstallationPayload,
    ): PriorSelection =
        when (previous) {
            PreviousInstallationPayload.None ->
                if (Files.notExists(plan.targetRoot, NOFOLLOW_LINKS)) PriorSelection.Absent else PriorSelection.Rejected
            is PreviousInstallationPayload.Physical -> {
                val root = Path.of(previous.installation)
                if (root == plan.targetRoot && previous.identity == observeInstallationFilesystemIdentity(root))
                    PriorSelection.Selected(root)
                else PriorSelection.Rejected
            }
            is PreviousInstallationPayload.Legacy -> {
                val root = Path.of(previous.installation)
                val selector = Path.of(previous.selector)
                if (
                    legacyPriorLocation(plan, root, previous) &&
                        Files.isSymbolicLink(selector) &&
                        Files.readSymbolicLink(selector).toString() == previous.target
                )
                    PriorSelection.Selected(root)
                else PriorSelection.Rejected
            }
        }

    private fun legacyPriorLocation(
        plan: VerifiedInstallationPlan,
        root: Path,
        previous: PreviousInstallationPayload.Legacy,
    ): Boolean =
        root.parent == plan.request.installRoot.value.resolve("versions") &&
            previous.identity == observeInstallationFilesystemIdentity(root)

    private fun commitCandidate(
        context: ReplacementActivation,
        staged: Path,
        baseline: InstallationRecoveryBaseline?,
        transaction: String,
    ): ManagedControlOutcome =
        when (val committed = InstallationWorkflow.commitReplacement(context, staged, baseline)) {
            ActivationResult.Complete -> ManagedControlOutcome.Committed(transaction)
            ActivationResult.RecoveryRequired ->
                ManagedControlOutcome.RecoveryRequired(InstallationFailure.RECOVERY_REQUIRED)
            is ActivationResult.Rejected -> ManagedControlOutcome.RecoveryRequired(committed.failure)
        }

    private fun underLock(
        request: InstallationRequest,
        action: (VerifiedInstallationPlan) -> ManagedControlOutcome,
    ): ManagedControlOutcome {
        val plan =
            when (val admitted = InstallationWorkflow.verify(request)) {
                is PlanVerification.Verified -> admitted.plan
                is PlanVerification.Rejected -> return rejected(admitted.failure)
            }
        return try {
            Files.createDirectories(request.installRoot.value)
            if (request.installRoot.value.toRealPath() != request.installRoot.value)
                return rejected(InstallationFailure.INSTALLATION_ROOT_REJECTED)
            val path = request.installRoot.value.resolve("activation.lock")
            if (Files.isSymbolicLink(path)) return rejected(InstallationFailure.ACTIVATION_LOCK_REJECTED)
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use {
                channel ->
                val lock =
                    acquireInstallationLock(channel) ?: return rejected(InstallationFailure.ACTIVATION_LOCK_REJECTED)
                lock.use { withSecuredLock(path, plan, action) }
            }
        } catch (_: IOException) {
            ManagedControlOutcome.RecoveryRequired(InstallationFailure.FILESYSTEM_REJECTED)
        } catch (_: SerializationException) {
            ManagedControlOutcome.RecoveryRequired(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED)
        } catch (_: SecurityException) {
            ManagedControlOutcome.RecoveryRequired(InstallationFailure.FILESYSTEM_REJECTED)
        }
    }

    private fun withSecuredLock(
        path: Path,
        plan: VerifiedInstallationPlan,
        action: (VerifiedInstallationPlan) -> ManagedControlOutcome,
    ): ManagedControlOutcome =
        if (secureActivationLock(path)) action(plan) else rejected(InstallationFailure.ACTIVATION_LOCK_REJECTED)

    private fun rejected(reason: InstallationFailure) = ManagedControlOutcome.Rejected(reason)
}
