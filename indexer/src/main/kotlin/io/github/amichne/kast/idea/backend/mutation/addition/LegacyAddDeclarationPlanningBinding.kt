package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.api.contract.result.AddDeclarationPlanResult
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclarationKind
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.validation.ParsedAddDeclarationPlanQuery
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationIntent
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.AddDeclarationSourceOwner
import io.github.amichne.kast.change.contract.AddDeclarationTargetCapability
import io.github.amichne.kast.change.contract.AddDeclarationVerificationContract
import io.github.amichne.kast.change.contract.DeclaredWriteSet
import io.github.amichne.kast.change.contract.DetachedCompilerEvidence
import io.github.amichne.kast.change.contract.ExactFileContentProof
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.ExpectedFileProof
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RawAddDeclarationPlanRequest
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournalFailure
import io.github.amichne.kast.change.plan.intellij.IntellijAddDeclarationPlanner
import io.github.amichne.kast.change.plan.spi.AddDeclarationEvidenceResult
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningEvidenceSource
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningLimitation
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningRejection
import io.github.amichne.kast.change.plan.spi.AddDeclarationPlanningResult
import io.github.amichne.kast.change.plan.service.PersistAddDeclarationPlanResult
import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceException
import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceFailure
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val legacyPlanJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}

/**
 * Proof transition:
 * ParsedAddDeclarationPlanQuery and SemanticReadLease to PlannedAddDeclaration.
 *
 * Establishes a detached PlannedAddDeclaration through the narrow read-only planning binding.
 * Expected failure remains closed by AdditionProofIncompleteException and AdditionProofLimitation
 * at this compatibility boundary. The caller must release the semantic lease before persistence.
 */
internal suspend fun KastIndexerBackend.planAddDeclarationViaBinding(
    query: ParsedAddDeclarationPlanQuery,
    lease: SemanticReadLease,
): PlannedAddDeclaration {
    val intent = when (
        val refinement = RawAddDeclarationPlanRequest(
            workspaceRoot = lease.workspaceRoot.value,
            targetPath = query.targetPath.value,
            expectedCurrentSha256 = query.expectedCurrentSha256.value,
            proposedDeclaration = query.proposedDeclaration.value,
        ).refine()
    ) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> throw AdditionProofIncompleteException.of(
            AdditionProofLimitation.PROPOSED_SYNTAX_INVALID,
            message = "The add-declaration intent is not canonical: ${refinement.failure}",
        )
    }
    val source = LegacyAddDeclarationEvidenceSource(
        intent = intent,
        generation = lease.generation,
        legacyPlan = { planAddDeclarationOperation(query) },
    )
    return when (val result = IntellijAddDeclarationPlanner(source).plan(intent)) {
        is AddDeclarationPlanningResult.Planned -> result.plan
        is AddDeclarationPlanningResult.Rejected -> throw result.rejection.toLegacyFailure()
    }
}

/**
 * Proof transition: `PlannedAddDeclaration -> AddDeclarationPlanResult`.
 *
 * Establishes that the detached plan crossed the durable journal boundary before its legacy
 * compiler evidence is projected. Expected failure is closed by
 * `AddDeclarationPlanPersistenceException`; raw legacy JSON is extracted only after persistence.
 */
internal fun KastIndexerBackend.persistAddDeclarationPlanViaBinding(
    plan: PlannedAddDeclaration,
): AddDeclarationPlanResult = when (val persisted = addDeclarationPlanPersistence.persist(plan)) {
    is PersistAddDeclarationPlanResult.Stored -> persisted.record.plan.toLegacyResult()
    is PersistAddDeclarationPlanResult.Existing -> persisted.record.plan.toLegacyResult()
    is PersistAddDeclarationPlanResult.JournalRejected ->
        throw persisted.failure.toPersistenceFailure()
}

private class LegacyAddDeclarationEvidenceSource(
    private val intent: AddDeclarationIntent,
    private val generation: EvidenceGeneration,
    private val legacyPlan: suspend () -> AddDeclarationPlanResult,
) : AddDeclarationPlanningEvidenceSource {
    override suspend fun evidence(
        intent: AddDeclarationIntent,
    ): AddDeclarationEvidenceResult {
        if (intent != this.intent) return rejected(
            AddDeclarationPlanningLimitation.EVIDENCE_INTENT_MISMATCH,
        )
        val legacy = try {
            legacyPlan()
        } catch (failure: AdditionProofIncompleteException) {
            val mapped = failure.limitations.map { limitation ->
                AddDeclarationPlanningLimitation.valueOf(limitation.name)
            }
            return AddDeclarationEvidenceResult.Rejected(
                AddDeclarationPlanningRejection.of(
                    mapped.firstOrNull()
                    ?: AddDeclarationPlanningLimitation.EVIDENCE_CONTRACT_INVALID,
                    *mapped.drop(1).toTypedArray(),
                ),
            )
        }
        return legacy.toDetachedEvidence(intent, generation)
    }
}

/**
 * Proof transition: `AddDeclarationPlanResult -> AddDeclarationEvidenceResult`.
 *
 * A proven result establishes a detached `AddDeclarationPlanningEvidence` with exact ownership,
 * file images, singleton write set, semantic delta, G0 verification, and canonical compiler JSON.
 * Expected failures are closed by `AddDeclarationPlanningRejection`; raw legacy fields are
 * extracted only at this indexer compatibility boundary.
 */
private fun AddDeclarationPlanResult.toDetachedEvidence(
    intent: AddDeclarationIntent,
    generation: EvidenceGeneration,
): AddDeclarationEvidenceResult {
    val owner = AddDeclarationSourceOwner.admit(
        sourceRoot = proof.owner.sourceRoot.value,
        ideaModuleName = proof.owner.ideaModuleName.value,
        gradleBuildRoot = proof.owner.gradleBuildRoot.value,
        gradleProjectPath = proof.owner.gradleProjectPath.value,
        sourceSetName = proof.owner.sourceSetName.value,
    ).refinedOrNull() ?: return invalidEvidence()
    val target = AddDeclarationTargetCapability.admit(intent, owner).refinedOrNull()
                 ?: return invalidEvidence()
    val preimage = ExactFileContentProof.admit(
        sha256 = image.preimage.sha256.value,
        contentBase64 = image.preimage.contentBase64.value,
    ).refinedOrNull() ?: return invalidEvidence()
    val postimage = ExactFileContentProof.admit(
        sha256 = image.postimage.sha256.value,
        contentBase64 = image.postimage.contentBase64.value,
    ).refinedOrNull() ?: return invalidEvidence()
    val expectedFile = ExpectedFileProof.admit(target, preimage, postimage).refinedOrNull()
                       ?: return invalidEvidence()
    val declaredWriteSet = DeclaredWriteSet.admit(listOf(target.targetPath)).refinedOrNull()
                           ?: return invalidEvidence()
    val semanticDelta = ExpectedAddDeclarationDelta.admit(
        packageName = proof.packageIdentity.render(),
        declarationName = proof.declaration.name,
        declarationKind = proof.declaration.kind.toPlanningKind(),
        collisionSignature = proof.declaration.collisionSignature.value,
    ).refinedOrNull() ?: return invalidEvidence()
    val verification = AddDeclarationVerificationContract.forGeneration(generation)
    val compilerEvidence = DetachedCompilerEvidence.admit(
        legacyPlanJson.encodeToString(AddDeclarationPlanResult.serializer(), this),
    ).refinedOrNull() ?: return invalidEvidence()
    val evidence = AddDeclarationPlanningEvidence.admit(
        intent = intent,
        generation = generation,
        target = target,
        expectedFile = expectedFile,
        declaredWriteSet = declaredWriteSet,
        expectedSemanticDelta = semanticDelta,
        verification = verification,
        compilerEvidence = compilerEvidence,
    ).refinedOrNull() ?: return invalidEvidence()
    return AddDeclarationEvidenceResult.Proven(evidence)
}

private fun PlannedAddDeclaration.toLegacyResult(): AddDeclarationPlanResult =
    legacyPlanJson.decodeFromString(
        AddDeclarationPlanResult.serializer(),
        compilerEvidence.canonicalJson,
    )

private fun AddDeclarationPlanningRejection.toLegacyFailure(): AdditionProofIncompleteException {
    val mapped = limitations.map { limitation ->
        AdditionProofLimitation.entries.firstOrNull { it.name == limitation.name }
        ?: AdditionProofLimitation.POSTIMAGE_MISMATCH
    }
    return AdditionProofIncompleteException.of(
        *mapped.toTypedArray(),
        message = "Add-declaration planning was rejected by the narrow operation binding",
    )
}

private fun AddDeclarationPlanJournalFailure.toPersistenceFailure(): AddDeclarationPlanPersistenceException =
    AddDeclarationPlanPersistenceException.of(
        when (this) {
            AddDeclarationPlanJournalFailure.StorageUnavailable ->
                AddDeclarationPlanPersistenceFailure.STORAGE_UNAVAILABLE
            AddDeclarationPlanJournalFailure.CorruptRecord ->
                AddDeclarationPlanPersistenceFailure.CORRUPT_RECORD
            is AddDeclarationPlanJournalFailure.PlanIdCollision ->
                AddDeclarationPlanPersistenceFailure.PLAN_ID_COLLISION
            is AddDeclarationPlanJournalFailure.PlanNotFound ->
                AddDeclarationPlanPersistenceFailure.PLAN_NOT_FOUND
            is AddDeclarationPlanJournalFailure.StateVersionExhausted ->
                AddDeclarationPlanPersistenceFailure.STATE_VERSION_EXHAUSTED
            is AddDeclarationPlanJournalFailure.PriorStateMismatch ->
                AddDeclarationPlanPersistenceFailure.PRIOR_STATE_MISMATCH
        },
    )

private fun invalidEvidence(): AddDeclarationEvidenceResult = rejected(
    AddDeclarationPlanningLimitation.EVIDENCE_CONTRACT_INVALID,
)

private fun rejected(
    limitation: AddDeclarationPlanningLimitation,
): AddDeclarationEvidenceResult.Rejected = AddDeclarationEvidenceResult.Rejected(
    AddDeclarationPlanningRejection.of(limitation),
)

private fun <T, F> Refinement<T, F>.refinedOrNull(): T? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private fun AdditionKotlinPackage.render(): String = when (this) {
    AdditionKotlinPackage.Root -> ""
    is AdditionKotlinPackage.Named -> segments.joinToString(".") { segment -> segment.value }
}

private fun AdditionTopLevelDeclarationKind.toPlanningKind(): AddDeclarationKind =
    AddDeclarationKind.valueOf(name)
