package io.github.amichne.kast.idea.backend.mutation

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ExactReplacementOutboundReference
import io.github.amichne.kast.api.contract.result.ReplacementCompilerContext
import io.github.amichne.kast.api.contract.result.ReplacementContractAdmission
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSignature
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSlice
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.contract.result.ReplacementSubmittedBodySlice
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedReplacementPlanQuery
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.contract.toKastLocation
import io.github.amichne.kast.idea.timedReadAction
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory

internal data class ReplacementPlanningSnapshot(
    val target: SymbolIdentity,
    val generation: Long,
    val sourceRange: Location,
    val proposedBodyText: String,
    val oldSignature: ReplacementDeclarationSignature,
    val proposedSignature: ReplacementDeclarationSignature,
    val declarationSlice: ReplacementDeclarationSlice,
    val proposedBodySlice: ReplacementSubmittedBodySlice,
    val outboundReferences: List<ExactReplacementOutboundReference>,
    val diagnostics: ReplacementBodyDiagnosticObservation.ErrorFree,
    val sourceContextHash: String,
    val compilerContext: ReplacementCompilerContext,
)

internal sealed interface ReplacementSourceIdentityBasis {
    val preimageBodyLength: PositiveInt

    data class CompilerPreimage(
        val file: KtFile,
        override val preimageBodyLength: PositiveInt,
    ) : ReplacementSourceIdentityBasis

    data class PersistedPreimage(
        override val preimageBodyLength: PositiveInt,
    ) : ReplacementSourceIdentityBasis
}

/**
 * Backend boundary projection from parsed replacement input to one admitted exact replacement plan.
 *
 * All semantic planning and contract construction below this boundary return closed admissions.
 * Only this backend boundary projects [ReplacementProofRejection] into the public transport
 * exception protocol.
 */
internal suspend fun KastIndexerBackend.planReplacementOperation(
    query: ParsedReplacementPlanQuery,
): ReplacementPlanResult = withContext(readDispatcher) {
    telemetry.inSpan(IdeaTelemetryScope.PLAN_REPLACEMENT, "kast.idea.planReplacement") {
        val snapshot = when (
            val admission = timedReadAction(
                telemetry,
                IdeaTelemetryScope.PLAN_REPLACEMENT,
                "kast.idea.planReplacement.collect",
            ) {
                collectReplacementPlanningSnapshot(query)
            }
        ) {
            is ReplacementAdmission.Admitted -> admission.value
            is ReplacementAdmission.Rejected -> projectReplacementProofFailure(admission.rejection)
        }
        val edit = TextEdit(
            filePath = snapshot.sourceRange.filePath,
            startOffset = snapshot.sourceRange.startOffset,
            endOffset = snapshot.sourceRange.endOffset,
            newText = snapshot.proposedBodyText,
        )
        val fileImages = try {
            planExactMutationFileImages(listOf(edit))
        } catch (failure: ExactMutationFileImagePlanningException) {
            projectReplacementProofFailure(
                ReplacementProofRejection(
                    limitation = ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN,
                    message = "Replacement exact source image proof failed: ${failure.failure.name}",
                    knownMinimumCount = snapshot.outboundReferences.size,
                ),
            )
        }
        val fileHashes = fileImages.map { image ->
            FileHash(
                filePath = image.filePath.value,
                hash = image.preimage.sha256.value,
            )
        }
        val proof = when (
            val admission = timedReadAction(
                telemetry,
                IdeaTelemetryScope.PLAN_REPLACEMENT,
                "kast.idea.planReplacement.prove",
            ) {
                finalizeReplacementProof(
                    query = query,
                    snapshot = snapshot,
                    fileHashes = fileHashes,
                )
            }
        ) {
            is ReplacementAdmission.Admitted -> admission.value
            is ReplacementAdmission.Rejected -> projectReplacementProofFailure(admission.rejection)
        }
        when (
            val admission = ReplacementPlanResult.admit(
                edit = edit,
                proof = proof,
                fileImages = fileImages,
            )
        ) {
            is ReplacementContractAdmission.Admitted -> admission.value
            is ReplacementContractAdmission.Rejected -> projectReplacementProofFailure(
                ReplacementProofRejection(
                    limitation = ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN,
                    message = "The exact replacement plan contract rejected ${admission.failure.name}",
                    knownMinimumCount = snapshot.outboundReferences.size,
                ),
            )
        }
    }
}

/**
 * Proof transition: [ParsedReplacementPlanQuery] -> [ReplacementAdmission] of
 * [ReplacementPlanningSnapshot].
 *
 * Establishes exact function identity, copied-PSI signature equivalence, original body-only edit
 * authority, body-postimage K2 bindings/diagnostics, and stable compiler context. Failure is a
 * closed [ReplacementProofRejection]. Raw PSI and source text may be extracted only inside this
 * indexer planning boundary.
 */
private fun KastIndexerBackend.collectReplacementPlanningSnapshot(
    query: ParsedReplacementPlanQuery,
): ReplacementAdmission<ReplacementPlanningSnapshot> {
    if (query.target.kind != SymbolKind.FUNCTION) {
        return replacementRejection(
            ReplacementProofLimitation.UNSUPPORTED_TARGET_KIND,
            "Declaration-body replacement planning supports only Kotlin function targets",
        )
    }
    val file = findKtFile(query.target.declarationFile.value)
    val targets = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .filter { declaration ->
            declaration.nameIdentifier?.textRange?.startOffset == query.target.declarationStartOffset.value
        }
    if (targets.size != 1) {
        return replacementRejection(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The exact replacement target could not be proven at its compiler declaration offset",
        )
    }
    val target = targets.single() as? KtNamedFunction
        ?: return replacementRejection(
            ReplacementProofLimitation.UNSUPPORTED_TARGET_KIND,
            "The exact declaration-body replacement target is not a Kotlin function",
        )
    val admittedTarget = when (val admission = admitAnnotationFreeReplacementFunction(target)) {
        is ReplacementAdmission.Admitted -> admission.value
        is ReplacementAdmission.Rejected -> return admission
    }
    val targetIdentity = compilerSourceIdentity(admittedTarget.declaration)
    if (targetIdentity != query.target) {
        return replacementRejection(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The supplied replacement identity does not match the compiler-resolved declaration",
        )
    }

    val proposedText = query.proposedDeclaration.value
    val parsedProposal = when (val admission = parseProposedDeclaration(proposedText)) {
        is ReplacementAdmission.Admitted -> admission.value
        is ReplacementAdmission.Rejected -> return admission
    }
    val admittedProposal = when (
        val admission = admitAnnotationFreeReplacementFunction(parsedProposal.declaration)
    ) {
        is ReplacementAdmission.Admitted -> admission.value
        is ReplacementAdmission.Rejected -> return admission
    }
    val targetRange = target.textRange
    val signatureSyntheticText = file.text.replaceRange(
        targetRange.startOffset,
        targetRange.endOffset,
        proposedText,
    )
    val signatureSyntheticFile = KtPsiFactory.contextual(target).createFile(
        file.name,
        signatureSyntheticText,
    )
    val proposedNameOffset = targetRange.startOffset + parsedProposal.nameOffset
    val proposedCandidates = PsiTreeUtil.findChildrenOfType(
        signatureSyntheticFile,
        KtNamedDeclaration::class.java,
    ).filter { declaration ->
        declaration.nameIdentifier?.textRange?.startOffset == proposedNameOffset
    }
    if (proposedCandidates.size != 1 || proposedCandidates.single() !is KtNamedFunction) {
        return replacementRejection(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The proposed declaration could not be analyzed in the target source context",
        )
    }
    val proposed = proposedCandidates.single() as KtNamedFunction
    if (proposed::class != admittedProposal.declaration::class) {
        return replacementRejection(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
            "The context-backed proposed declaration kind changed during parsing",
        )
    }
    val proposedRange = proposed.textRange
    if (
        proposedRange.startOffset !=
        targetRange.startOffset + parsedProposal.declarationSlice.startOffset.value ||
        proposedRange.endOffset !=
        targetRange.startOffset + parsedProposal.declarationSlice.endOffset.value
    ) {
        return replacementRejection(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The context-backed proposed declaration changed its exact full-edit slice",
        )
    }

    val oldSignature = compilerReplacementSignature(target)
    val proposedSignature = compilerReplacementSignature(proposed)
    if (oldSignature != proposedSignature) {
        return replacementRejection(
            ReplacementProofLimitation.SIGNATURE_DRIFT,
            "The proposed declaration changes its compiler-observable signature",
        )
    }
    val targetBody = target.bodyExpression ?: return replacementRejection(
        ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_CONTENT,
        "The exact function target has no replaceable declaration body",
    )
    val proposedBody = proposed.bodyExpression ?: return replacementRejection(
        ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
        "The proposed function has no declaration body",
    )
    if (target.hasBlockBody() != proposed.hasBlockBody()) {
        return replacementRejection(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_CONTENT,
            "Declaration-body replacement cannot change between block and expression body forms",
        )
    }
    val proposedBodyText = proposedBody.text
    val submittedBodyStart = parsedProposal.proposedBodySlice.startOffset.value
    val submittedBodyEnd = parsedProposal.proposedBodySlice.endOffset.value
    if (proposedText.substring(submittedBodyStart, submittedBodyEnd) != proposedBodyText) {
        return replacementRejection(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The copied-PSI body slice disagreed with the context-backed proposed body",
        )
    }

    val bodySyntheticText = file.text.replaceRange(
        targetBody.textRange.startOffset,
        targetBody.textRange.endOffset,
        proposedBodyText,
    )
    val bodySyntheticFile = KtPsiFactory.contextual(target).createFile(file.name, bodySyntheticText)
    val bodySyntheticCandidates = PsiTreeUtil.findChildrenOfType(
        bodySyntheticFile,
        KtNamedDeclaration::class.java,
    ).filter { declaration ->
        declaration.nameIdentifier?.textRange?.startOffset == query.target.declarationStartOffset.value
    }
    if (bodySyntheticCandidates.size != 1 || bodySyntheticCandidates.single() !is KtNamedFunction) {
        return replacementRejection(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The exact-body semantic postimage no longer contains the target at its unchanged declaration offset",
        )
    }
    val bodySyntheticTarget = bodySyntheticCandidates.single() as KtNamedFunction
    val bodySyntheticBody = bodySyntheticTarget.bodyExpression ?: return replacementRejection(
        ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
        "The exact-body semantic postimage has no replacement body",
    )
    if (compilerReplacementSignature(bodySyntheticTarget) != oldSignature) {
        return replacementRejection(
            ReplacementProofLimitation.SIGNATURE_DRIFT,
            "The exact-body semantic postimage did not retain the target signature",
        )
    }
    if (
        bodySyntheticBody.textRange.startOffset != targetBody.textRange.startOffset ||
        bodySyntheticBody.textRange.endOffset != targetBody.textRange.startOffset + proposedBodyText.length
    ) {
        return replacementRejection(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The exact-body semantic postimage did not retain the exact body-write authority",
        )
    }
    val admittedBodySyntheticTarget = when (
        val annotationAdmission = admitAnnotationFreeReplacementFunction(bodySyntheticTarget)
    ) {
        is ReplacementAdmission.Rejected -> return annotationAdmission
        is ReplacementAdmission.Admitted -> when (
            val referenceAdmission = admitExplicitReferenceReplacementFunction(annotationAdmission.value)
        ) {
            is ReplacementAdmission.Admitted -> referenceAdmission.value
            is ReplacementAdmission.Rejected -> return referenceAdmission
        }
    }
    val outboundReferences = when (
        val admission = collectExactOutboundReferences(
            syntheticFile = bodySyntheticFile,
            proposed = admittedBodySyntheticTarget,
            replacement = bodySyntheticBody,
            replacementStartOffset = bodySyntheticBody.textRange.startOffset,
            proposedBodyText = proposedBodyText,
            sourceIdentityBasis = ReplacementSourceIdentityBasis.CompilerPreimage(
                file = file,
                preimageBodyLength = PositiveInt(targetBody.textRange.length),
            ),
        )
    ) {
        is ReplacementAdmission.Admitted -> admission.value
        is ReplacementAdmission.Rejected -> return admission
    }
    val diagnostics = when (observeReplacementBodyDiagnostics(bodySyntheticFile, bodySyntheticBody)) {
        ReplacementBodyDiagnosticObservation.ErrorFree ->
            ReplacementBodyDiagnosticObservation.ErrorFree
        ReplacementBodyDiagnosticObservation.ContainsErrors -> return replacementRejection(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The proposed replacement body contains compiler error diagnostics",
        )

        ReplacementBodyDiagnosticObservation.Unavailable -> return replacementRejection(
            ReplacementProofLimitation.PROPOSED_PSI_TRAVERSAL_INCOMPLETE,
            "The proposed replacement body diagnostics could not be completed",
        )
    }
    val compilerContext = when (
        val observation = observeReplacementCompilerContext(
            ExactFileImagePath(targetIdentity.declarationFile.value),
        )
    ) {
        is ReplacementCompilerContextObservation.Proven -> observation.context
        is ReplacementCompilerContextObservation.Rejected -> return replacementRejection(
            ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN,
            "Replacement compiler-context observation failed: ${observation.failure.name}",
            outboundReferences.size,
        )
    }
    return ReplacementAdmission.Admitted(
        ReplacementPlanningSnapshot(
            target = targetIdentity,
            generation = psiGeneration(),
            sourceRange = target.toKastLocation(targetBody.textRange),
            proposedBodyText = proposedBodyText,
            oldSignature = oldSignature,
            proposedSignature = proposedSignature,
            declarationSlice = parsedProposal.declarationSlice,
            proposedBodySlice = parsedProposal.proposedBodySlice,
            outboundReferences = outboundReferences,
            diagnostics = diagnostics,
            sourceContextHash = FileHashing.sha256(file.text),
            compilerContext = compilerContext,
        ),
    )
}
