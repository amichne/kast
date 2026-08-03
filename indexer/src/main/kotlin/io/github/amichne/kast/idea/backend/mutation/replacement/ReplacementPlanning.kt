@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ExactReplacementOutboundReference
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.ReplacementCompilerSymbolKind
import io.github.amichne.kast.api.contract.result.ReplacementCompilerTargetSignature
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSha256
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSlice
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSignature
import io.github.amichne.kast.api.contract.result.ReplacementFunctionSignature
import io.github.amichne.kast.api.contract.result.ReplacementModality
import io.github.amichne.kast.api.contract.result.ReplacementOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.contract.result.ReplacementPropertySignature
import io.github.amichne.kast.api.contract.result.ReplacementTypeParameterSignature
import io.github.amichne.kast.api.contract.result.ReplacementTypeVariance
import io.github.amichne.kast.api.contract.result.ReplacementValueParameterSignature
import io.github.amichne.kast.api.contract.result.ReplacementVisibility
import io.github.amichne.kast.api.protocol.ReplacementProofFailureEvidence
import io.github.amichne.kast.api.protocol.ReplacementProofIncompleteException
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedReplacementPlanQuery
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.relationships.relationshipIdentity
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitInvokeCall
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReferenceExpression

private data class ReplacementPlanningSnapshot(
    val target: SymbolIdentity,
    val generation: Long,
    val sourceRange: Location,
    val oldSignature: ReplacementDeclarationSignature,
    val proposedSignature: ReplacementDeclarationSignature,
    val declarationSlice: ReplacementDeclarationSlice,
    val outboundReferences: List<ExactReplacementOutboundReference>,
    val sourceContextHash: String,
)


internal sealed interface ReplacementSourceIdentityBasis {
    val preimageDeclarationLength: PositiveInt

    data class CompilerPreimage(
        val file: KtFile,
        override val preimageDeclarationLength: PositiveInt,
    ) : ReplacementSourceIdentityBasis

    data class PersistedPreimage(
        override val preimageDeclarationLength: PositiveInt,
    ) : ReplacementSourceIdentityBasis
}
internal suspend fun KastIndexerBackend.planReplacementOperation(
    query: ParsedReplacementPlanQuery,
): ReplacementPlanResult = withContext(readDispatcher) {
    telemetry.inSpan(IdeaTelemetryScope.PLAN_REPLACEMENT, "kast.idea.planReplacement") {
        val snapshot = timedReadAction(
            telemetry,
            IdeaTelemetryScope.PLAN_REPLACEMENT,
            "kast.idea.planReplacement.collect",
        ) {
            collectReplacementPlanningSnapshot(query)
        }
        val edit = TextEdit(
            filePath = snapshot.sourceRange.filePath,
            startOffset = snapshot.sourceRange.startOffset,
            endOffset = snapshot.sourceRange.endOffset,
            newText = query.proposedDeclaration.value,
        )
        val fileImages = try {
            planExactMutationFileImages(listOf(edit))
        } catch (failure: ExactMutationFileImagePlanningException) {
            failReplacementProof(
                limitation = ReplacementProofLimitation.SOURCE_IMAGE_UNPROVEN,
                message = "Replacement exact source image proof failed: ${failure.failure.name}",
                knownMinimumCount = snapshot.outboundReferences.size,
            )
        }
        val fileHashes = fileImages.map { image ->
            FileHash(
                filePath = image.filePath.value,
                hash = image.preimage.sha256.value,
            )
        }
        val proof = timedReadAction(
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
        ReplacementPlanResult.of(
            edit = edit,
            proof = proof,
            fileImages = fileImages,
        )
    }
}
private fun KastIndexerBackend.collectReplacementPlanningSnapshot(
    query: ParsedReplacementPlanQuery,
): ReplacementPlanningSnapshot {
    if (query.target.kind != SymbolKind.FUNCTION && query.target.kind != SymbolKind.PROPERTY) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_TARGET_KIND,
            "Replacement planning supports only Kotlin function and property targets",
        )
    }
    val file = findKtFile(query.target.declarationFile.value)
    val target = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .filter { declaration ->
            declaration.nameIdentifier?.textRange?.startOffset == query.target.declarationStartOffset.value
        }
        .singleOrNull()
        ?: failReplacementProof(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The exact replacement target could not be proven at its compiler declaration offset",
        )
    if (target !is KtNamedFunction && target !is KtProperty) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_TARGET_KIND,
            "The exact replacement target is not a Kotlin function or property",
        )
    }
    requireNoReplacementAnnotations(target)
    val targetIdentity = compilerSourceIdentity(target)
    if (targetIdentity != query.target) {
        failReplacementProof(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The supplied replacement identity does not match the compiler-resolved declaration",
        )
    }

    val proposedText = query.proposedDeclaration.value
    val parsedProposal = parseProposedDeclaration(proposedText)
    requireNoReplacementAnnotations(parsedProposal.declaration)
    val targetRange = target.textRange
    val syntheticText = file.text.replaceRange(targetRange.startOffset, targetRange.endOffset, proposedText)
    val syntheticFile = KtPsiFactory.contextual(target).createFile(file.name, syntheticText)
    val proposedNameOffset = targetRange.startOffset + parsedProposal.nameOffset
    val proposed = PsiTreeUtil.findChildrenOfType(syntheticFile, KtNamedDeclaration::class.java)
        .filter { declaration -> declaration.nameIdentifier?.textRange?.startOffset == proposedNameOffset }
        .singleOrNull()
        ?: failReplacementProof(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The proposed declaration could not be analyzed in the target source context",
        )
    if (proposed::class != parsedProposal.declaration::class) {
        failReplacementProof(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
            "The context-backed proposed declaration kind changed during parsing",
        )
    }
    val proposedRange = proposed.textRange
    if (proposedRange.startOffset != targetRange.startOffset + parsedProposal.declarationSlice.startOffset.value ||
        proposedRange.endOffset != targetRange.startOffset + parsedProposal.declarationSlice.endOffset.value
    ) {
        failReplacementProof(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The context-backed proposed declaration changed its exact full-edit slice",
        )
    }

    val oldSignature = compilerReplacementSignature(target)
    val proposedSignature = compilerReplacementSignature(proposed)
    if (oldSignature != proposedSignature) {
        failReplacementProof(
            ReplacementProofLimitation.SIGNATURE_DRIFT,
            "The proposed declaration changes its compiler-observable signature",
        )
    }
    val outboundReferences = collectExactOutboundReferences(
        syntheticFile = syntheticFile,
        proposed = proposed,
        replacementStartOffset = targetRange.startOffset,
        proposedDeclarationText = proposedText,
        sourceIdentityBasis = ReplacementSourceIdentityBasis.CompilerPreimage(
            file = file,
            preimageDeclarationLength = PositiveInt(targetRange.length),
        ),
    )
    return ReplacementPlanningSnapshot(
        target = targetIdentity,
        generation = psiGeneration(),
        sourceRange = target.toKastLocation(targetRange),
        oldSignature = oldSignature,
        proposedSignature = proposedSignature,
        declarationSlice = parsedProposal.declarationSlice,
        outboundReferences = outboundReferences,
        sourceContextHash = FileHashing.sha256(file.text),
    )
}

private fun KastIndexerBackend.finalizeReplacementProof(
    query: ParsedReplacementPlanQuery,
    snapshot: ReplacementPlanningSnapshot,
    fileHashes: List<FileHash>,
): ExactReplacementProof {
    val occurrenceCount = snapshot.outboundReferences.size
    if (psiGeneration() != snapshot.generation) {
        failReplacementProof(
            ReplacementProofLimitation.GENERATION_CHANGED,
            "The semantic generation changed before replacement proof finalization",
            occurrenceCount,
        )
    }
    val file = findKtFile(snapshot.target.declarationFile.value)
    val currentContextHash = FileHashing.sha256(file.text)
    if (currentContextHash != snapshot.sourceContextHash ||
        fileHashes.singleOrNull()?.let { hash ->
            hash.filePath == snapshot.target.declarationFile.value
        } != true
    ) {
        failReplacementProof(
            ReplacementProofLimitation.SOURCE_CONTEXT_CHANGED,
            "The exact source context changed while the replacement proof was being built",
            occurrenceCount,
        )
    }
    val target = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .filter { declaration ->
            declaration.nameIdentifier?.textRange?.startOffset == snapshot.target.declarationStartOffset.value
        }
        .singleOrNull()
        ?: failReplacementProof(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The exact replacement target disappeared before proof finalization",
            occurrenceCount,
        )
    if (compilerSourceIdentity(target) != snapshot.target || target.toKastLocation(target.textRange) != snapshot.sourceRange) {
        failReplacementProof(
            ReplacementProofLimitation.TARGET_IDENTITY_UNPROVEN,
            "The exact replacement target changed before proof finalization",
            occurrenceCount,
        )
    }
    if (psiGeneration() != snapshot.generation) {
        failReplacementProof(
            ReplacementProofLimitation.GENERATION_CHANGED,
            "The semantic generation changed during replacement proof finalization",
            occurrenceCount,
        )
    }
    return ExactReplacementProof.of(
        target = snapshot.target,
        requiredGeneration = MutationSemanticGeneration(snapshot.generation),
        sourceRange = snapshot.sourceRange,
        fileHashes = fileHashes,
        oldSignature = snapshot.oldSignature,
        proposedSignature = snapshot.proposedSignature,
        proposedDeclarationHash = ReplacementDeclarationSha256(
            FileHashing.sha256(query.proposedDeclaration.value),
        ),
        proposedDeclarationLength = query.proposedDeclaration.value.length,
        declarationSlice = snapshot.declarationSlice,
        evidence = ReplacementOutboundEvidence.Complete.of(occurrenceCount),
        outboundReferences = snapshot.outboundReferences,
    )
}
