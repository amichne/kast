package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.query.MutationScratchDirection
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.result.AddDeclarationPlanResult
import io.github.amichne.kast.api.contract.result.AddFilePlanResult
import io.github.amichne.kast.api.contract.result.ExactFileImageResult
import io.github.amichne.kast.api.contract.result.MutationPostconditionResult
import io.github.amichne.kast.api.contract.result.MutationScratchInspectResult
import io.github.amichne.kast.api.contract.result.MutationScratchObservation
import io.github.amichne.kast.api.contract.result.MutationScratchOwnership
import io.github.amichne.kast.api.contract.result.MutationScratchRecoveryOutcome
import io.github.amichne.kast.api.contract.result.MutationScratchRecoveryResult
import io.github.amichne.kast.api.contract.result.MutationScratchRole
import io.github.amichne.kast.api.contract.result.MutationScratchState
import io.github.amichne.kast.api.contract.result.MutationScratchTargetState
import io.github.amichne.kast.api.contract.result.RawExactFileObservationResult
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.validation.ParsedAddFilePlanQuery
import io.github.amichne.kast.api.validation.ParsedExactFileImageQuery
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionAuthority
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionQuery
import io.github.amichne.kast.api.validation.ParsedMutationScratchInspectQuery
import io.github.amichne.kast.api.validation.ParsedMutationScratchRecoveryQuery
import io.github.amichne.kast.api.validation.ParsedRawExactFileObservationQuery
import io.github.amichne.kast.api.validation.ParsedReplacementPlanQuery

internal data class DocExampleGeneratorMutationResponses(
    val replacement: ReplacementPlanResult,
    val addFile: AddFilePlanResult,
    val addDeclaration: AddDeclarationPlanResult,
    val postcondition: MutationPostconditionResult,
    val observation: RawExactFileObservationResult.Present,
)

internal class DocExampleGeneratorMutationBackend(
    private val delegate: AnalysisBackend,
    private val responses: DocExampleGeneratorMutationResponses,
) : AnalysisBackend by delegate {

    override suspend fun capabilities(): BackendCapabilities {
        val base = delegate.capabilities()
        return base.copy(mutationCapabilities = base.mutationCapabilities + DOCUMENTED_MUTATION_CAPABILITIES)
    }

    override suspend fun planReplacement(query: ParsedReplacementPlanQuery): ReplacementPlanResult {
        require(query.target == responses.replacement.proof.target)
        require(query.proposedDeclaration.value == responses.replacement.edit.newText)
        return responses.replacement
    }

    override suspend fun planAddFile(query: ParsedAddFilePlanQuery): AddFilePlanResult {
        require(query.targetPath == responses.addFile.proof.targetPath)
        require(query.proposedContent.value == responses.addFile.proposedContent)
        return responses.addFile
    }

    override suspend fun verifyMutationPostcondition(
        query: ParsedMutationPostconditionQuery,
    ): MutationPostconditionResult {
        val authority = query.authority
        require(authority is ParsedMutationPostconditionAuthority.AddFile)
        require(authority.proof == responses.addFile.proof)
        require(authority.postimage == responses.addFile.postimage)
        return responses.postcondition
    }

    override suspend fun observeExactFile(
        query: ParsedRawExactFileObservationQuery,
    ): RawExactFileObservationResult {
        require(query.filePath == responses.observation.filePath)
        return responses.observation
    }

    override suspend fun exactFileImageCas(query: ParsedExactFileImageQuery): ExactFileImageResult =
        ExactFileImageResult.committed(
            filePath = query.filePath.value,
            previousSha256 = query.expectedCurrentSha256,
            resultSha256 = query.expectedResultSha256,
        )

    override suspend fun inspectMutationScratch(
        query: ParsedMutationScratchInspectQuery,
    ): MutationScratchInspectResult = MutationScratchInspectResult(
        mutationAttemptId = query.mutationAttemptId,
        observations = query.ownedScratchSets
            .flatMap { scratch -> scratch.absentObservations() }
            .sortedBy(MutationScratchObservation::filePath),
    )

    override suspend fun recoverMutationScratch(
        query: ParsedMutationScratchRecoveryQuery,
    ): MutationScratchRecoveryResult {
        require(query.action == MutationScratchRecoveryAction.RESTORE_PREIMAGE)
        require(query.scratchDirection == MutationScratchDirection.RESTORE_PREIMAGE)
        val preimage = query.preimage as MutationScratchRecoveryPreimage.Present
        return MutationScratchRecoveryResult(
            mutationAttemptId = query.mutationAttemptId,
            action = query.action,
            outcome = MutationScratchRecoveryOutcome.RESTORED_PREIMAGE,
            targetState = MutationScratchTargetState.PRESENT,
            targetSha256 = preimage.image.sha256,
            scratchObservations = query.scratch.absentObservations(),
        )
    }
}

private fun io.github.amichne.kast.api.validation.ParsedMutationScratchSet.absentObservations():
    List<MutationScratchObservation> = listOf(
        quarantinePath.value to MutationScratchRole.QUARANTINE,
        preparedPath.value to MutationScratchRole.PREPARED,
        preparedCleanupPath.value to MutationScratchRole.PREPARED_CLEANUP,
        quarantineCleanupPath.value to MutationScratchRole.QUARANTINE_CLEANUP,
    ).map { (path, role) ->
        MutationScratchObservation(
            filePath = path,
            ownership = MutationScratchOwnership.OWNED,
            role = role,
            state = MutationScratchState.ABSENT,
        )
    }

private val DOCUMENTED_MUTATION_CAPABILITIES = setOf(
    MutationCapability.PLAN_REPLACEMENT,
    MutationCapability.PLAN_ADD_FILE,
    MutationCapability.VERIFY_MUTATION_POSTCONDITION,
    MutationCapability.EXACT_FILE_OBSERVATION,
    MutationCapability.EXACT_FILE_IMAGE_CAS,
    MutationCapability.MUTATION_SCRATCH_RECOVERY,
)
