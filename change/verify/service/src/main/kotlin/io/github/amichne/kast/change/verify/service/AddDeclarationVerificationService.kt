package io.github.amichne.kast.change.verify.service

import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.journal.contract.AppliedUnverifiedAddDeclaration
import io.github.amichne.kast.change.journal.contract.VerifiedAddDeclaration
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommand
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommandFailure
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationExecutor
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationRejection
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationResult
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationJournal
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerification
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerificationFailure
import io.github.amichne.kast.change.verify.spi.CompleteAddDeclarationVerificationResult
import io.github.amichne.kast.change.verify.spi.ObservedAddDeclarationVerification
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHashFailure
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaim
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaims
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshnessClaimsFailure
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePathFailure
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionPort
import java.util.concurrent.CancellationException
import java.nio.file.Path

sealed interface AddDeclarationPublicationRequestFailure {
    data class TargetPath(
        val failure: WorkspaceSourcePathFailure,
    ) : AddDeclarationPublicationRequestFailure

    data class PostimageHash(
        val failure: WorkspaceSourceContentHashFailure,
    ) : AddDeclarationPublicationRequestFailure

    data class Claims(
        val failure: WorkspaceSourceFreshnessClaimsFailure,
    ) : AddDeclarationPublicationRequestFailure
}

sealed interface AddDeclarationBeforePublicationFailure {
    data class RequestAdmission(
        val failure: AddDeclarationPublicationRequestFailure,
    ) : AddDeclarationBeforePublicationFailure

    data class TransitionRejected(
        val failure: WorkspaceTransitionFailure,
    ) : AddDeclarationBeforePublicationFailure

    data object TransitionProtocolFailure : AddDeclarationBeforePublicationFailure
}

sealed interface AddDeclarationAfterPublicationFailure {
    data class CommandAdmission(
        val failure: AddDeclarationVerificationCommandFailure,
    ) : AddDeclarationAfterPublicationFailure

    data class VerificationRejected(
        val rejection: AddDeclarationVerificationRejection,
    ) : AddDeclarationAfterPublicationFailure

    data object VerificationProtocolFailure : AddDeclarationAfterPublicationFailure

    data object VerificationCommandMismatch : AddDeclarationAfterPublicationFailure
}

sealed interface AddDeclarationAfterVerificationFailure {
    data class CompletionAdmission(
        val failure: CompleteAddDeclarationVerificationFailure,
    ) : AddDeclarationAfterVerificationFailure

    data class CompletionPersistence(
        val failure: AddDeclarationPlanJournalFailure,
    ) : AddDeclarationAfterVerificationFailure
}

sealed interface VerifyAppliedAddDeclarationResult {
    data class Verified(
        val record: VerifiedAddDeclaration,
        val observation: ObservedAddDeclarationVerification,
    ) : VerifyAppliedAddDeclarationResult

    data class RejectedBeforePublication(
        val applied: AppliedUnverifiedAddDeclaration,
        val failure: AddDeclarationBeforePublicationFailure,
    ) : VerifyAppliedAddDeclarationResult

    data class RejectedAfterPublication(
        val applied: AppliedUnverifiedAddDeclaration,
        val publication: PublishedWorkspaceGeneration,
        val failure: AddDeclarationAfterPublicationFailure,
    ) : VerifyAppliedAddDeclarationResult

    data class RejectedAfterVerification(
        val applied: AppliedUnverifiedAddDeclaration,
        val observation: ObservedAddDeclarationVerification,
        val failure: AddDeclarationAfterVerificationFailure,
    ) : VerifyAppliedAddDeclarationResult

    data class CompletionReconciliationRequired(
        val applied: AppliedUnverifiedAddDeclaration,
        val observation: ObservedAddDeclarationVerification,
    ) : VerifyAppliedAddDeclarationResult
}

class AddDeclarationVerificationService(
    private val transitions: WorkspaceTransitionPort,
    private val executor: AddDeclarationVerificationExecutor,
    private val journal: AddDeclarationVerificationJournal,
) {
    /**
     * Proof transition: `AppliedUnverifiedAddDeclaration -> VerifyAppliedAddDeclarationResult`.
     *
     * Success establishes one exact postimage source claim, a strictly newer atomically published
     * generation, scoped compiler verification of every obligation, and a durable terminal v5
     * receipt. Every expected failure is a closed result retaining the strongest prior proof.
     * Raw paths and hashes are extracted only at the workspace request boundary; injected effects
     * are totalized before this service returns.
     */
    suspend fun verify(
        applied: AppliedUnverifiedAddDeclaration,
    ): VerifyAppliedAddDeclarationResult {
        val request = when (val result = publicationRequest(applied)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return VerifyAppliedAddDeclarationResult.RejectedBeforePublication(
                applied,
                AddDeclarationBeforePublicationFailure.RequestAdmission(result.failure),
            )
        }
        val publication = when (val result = try {
            transitions.reconcile(request)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return VerifyAppliedAddDeclarationResult.RejectedBeforePublication(
                applied,
                AddDeclarationBeforePublicationFailure.TransitionProtocolFailure,
            )
        }) {
            is WorkspaceTransitionOutcome.Published -> result.publication
            is WorkspaceTransitionOutcome.Rejected ->
                return VerifyAppliedAddDeclarationResult.RejectedBeforePublication(
                    applied,
                    AddDeclarationBeforePublicationFailure.TransitionRejected(result.failure),
                )
        }
        val command = when (val result = AddDeclarationVerificationCommand.admit(
            applied.plan,
            publication,
        )) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return rejectedAfterPublication(
                applied,
                publication,
                AddDeclarationAfterPublicationFailure.CommandAdmission(result.failure),
            )
        }
        val observation = when (val result = try {
            executor.verify(command)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return rejectedAfterPublication(
                applied,
                publication,
                AddDeclarationAfterPublicationFailure.VerificationProtocolFailure,
            )
        }) {
            is AddDeclarationVerificationResult.Observed -> {
                if (result.verification.command != command) {
                    return rejectedAfterPublication(
                        applied,
                        publication,
                        AddDeclarationAfterPublicationFailure.VerificationCommandMismatch,
                    )
                }
                result.verification
            }
            is AddDeclarationVerificationResult.Rejected -> {
                if (result.command != command) {
                    return rejectedAfterPublication(
                        applied,
                        publication,
                        AddDeclarationAfterPublicationFailure.VerificationCommandMismatch,
                    )
                }
                return rejectedAfterPublication(
                    applied,
                    publication,
                    AddDeclarationAfterPublicationFailure.VerificationRejected(result.rejection),
                )
            }
        }
        val completion = when (val result = CompleteAddDeclarationVerification.admit(
            applied = applied,
            verification = observation,
        )) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return rejectedAfterVerification(
                applied,
                observation,
                AddDeclarationAfterVerificationFailure.CompletionAdmission(result.failure),
            )
        }
        return when (val result = try {
            journal.completeVerification(completion)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return rejectedAfterVerification(
                applied,
                observation,
                AddDeclarationAfterVerificationFailure.CompletionPersistence(
                    AddDeclarationPlanJournalFailure.StorageUnavailable,
                ),
            )
        }) {
            is CompleteAddDeclarationVerificationResult.Completed ->
                VerifyAppliedAddDeclarationResult.Verified(result.record, observation)
            is CompleteAddDeclarationVerificationResult.Rejected -> rejectedAfterVerification(
                applied,
                observation,
                AddDeclarationAfterVerificationFailure.CompletionPersistence(result.failure),
            )
            is CompleteAddDeclarationVerificationResult.CommitOutcomeUnknown ->
                VerifyAppliedAddDeclarationResult.CompletionReconciliationRequired(
                    applied,
                    observation,
                )
        }
    }
}

/**
 * Proof transition: `AppliedUnverifiedAddDeclaration -> Refinement<WorkspaceTransitionRequest,
 * AddDeclarationPublicationRequestFailure>`.
 *
 * Establishes one exact workspace-relative source claim bound to the already-proven KIP-034
 * postimage. The failure type is closed; raw path and hash extraction is permitted only here.
 */
private fun publicationRequest(
    applied: AppliedUnverifiedAddDeclaration,
): Refinement<WorkspaceTransitionRequest, AddDeclarationPublicationRequestFailure> {
    val relative = Path.of(applied.plan.intent.workspaceRoot.value)
        .relativize(Path.of(applied.plan.target.targetPath.value))
        .joinToString(separator = "/") { segment -> segment.toString() }
    val path = when (val result = WorkspaceSourcePath.parse(relative)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return Refinement.Rejected(
            AddDeclarationPublicationRequestFailure.TargetPath(result.failure),
        )
    }
    val hash = when (val result = WorkspaceSourceContentHash.parse(applied.afterImage.sha256.value)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return Refinement.Rejected(
            AddDeclarationPublicationRequestFailure.PostimageHash(result.failure),
        )
    }
    val claims = when (val result = WorkspaceSourceFreshnessClaims.refine(
        listOf(WorkspaceSourceFreshnessClaim(path, WorkspaceSourceContentIdentity.Present(hash))),
    )) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return Refinement.Rejected(
            AddDeclarationPublicationRequestFailure.Claims(result.failure),
        )
    }
    return Refinement.Refined(WorkspaceTransitionRequest.SourceFiles(claims))
}

private fun rejectedAfterPublication(
    applied: AppliedUnverifiedAddDeclaration,
    publication: PublishedWorkspaceGeneration,
    failure: AddDeclarationAfterPublicationFailure,
): VerifyAppliedAddDeclarationResult.RejectedAfterPublication =
    VerifyAppliedAddDeclarationResult.RejectedAfterPublication(applied, publication, failure)

private fun rejectedAfterVerification(
    applied: AppliedUnverifiedAddDeclaration,
    observation: ObservedAddDeclarationVerification,
    failure: AddDeclarationAfterVerificationFailure,
): VerifyAppliedAddDeclarationResult.RejectedAfterVerification =
    VerifyAppliedAddDeclarationResult.RejectedAfterVerification(applied, observation, failure)
