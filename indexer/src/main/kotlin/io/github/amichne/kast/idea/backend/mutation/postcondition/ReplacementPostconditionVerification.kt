@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionAuthority
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionQuery
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.relationships.CompleteRelationshipCoverageAdmission
import io.github.amichne.kast.idea.backend.relationships.completeRelationshipCoverageAdmission
import io.github.amichne.kast.idea.backend.relationships.relationshipIdentity
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.visibility
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

internal fun KastIndexerBackend.verifyReplacement(
    authority: ParsedMutationPostconditionAuthority.Replacement,
): MutationPostconditionEvidence.Replacement {
    val file = exactPostimageKtFile(authority.edit.filePath)
    val declarationStart = authority.edit.startOffset + authority.proof.declarationSlice.startOffset.value
    val declarationEnd = authority.edit.startOffset + authority.proof.declarationSlice.endOffset.value
    val target = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .singleOrNull { declaration ->
            declaration.textRange.startOffset == declarationStart &&
                declaration.textRange.endOffset == declarationEnd
        } ?: failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "The replacement declaration is not present at its exact resulting range",
    )
    val declarationText = authority.edit.newText.substring(
        authority.proof.declarationSlice.startOffset.value,
        authority.proof.declarationSlice.endOffset.value,
    )
    if (target.text != declarationText) failPostcondition(
        MutationPostconditionLimitation.POSTIMAGE_MISMATCH,
        "The semantic replacement declaration does not equal its exact persisted declaration slice",
    )
    val compilerTarget = compilerSourceIdentity(target)
    val expected = authority.proof.target
    if (compilerTarget.fqName != expected.fqName || compilerTarget.kind != expected.kind ||
        compilerTarget.containingType != expected.containingType
    ) failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "The replacement changed its compiler FQ owner or declaration kind",
    )
    val resultingTarget = compilerTarget.copy(declarationFile = expected.declarationFile)
    val signature = try {
        compilerReplacementSignature(target)
    } catch (failure: ReplacementProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.SIGNATURE_MISMATCH,
            "The resulting replacement compiler signature is incomplete",
        )
    }
    if (signature != authority.proof.proposedSignature) failPostcondition(
        MutationPostconditionLimitation.SIGNATURE_MISMATCH,
        "The resulting replacement compiler signature changed",
    )
    val outbound = try {
        collectExactOutboundReferences(
            syntheticFile = file,
            proposed = target,
            replacementStartOffset = authority.edit.startOffset,
            proposedDeclarationText = authority.edit.newText,
            sourceIdentityBasis = ReplacementSourceIdentityBasis.PersistedPreimage(
                preimageDeclarationLength = PositiveInt(
                    authority.proof.sourceRange.endOffset - authority.proof.sourceRange.startOffset,
                ),
            ),
        )
    } catch (failure: ReplacementProofIncompleteException) {
        failPostcondition(
            MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
            "The resulting replacement outbound proof is incomplete",
        )
    }
    if (outbound != authority.proof.outboundReferences) failPostcondition(
        MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
        "The resulting replacement outbound occurrence set changed",
    )
    return MutationPostconditionEvidence.Replacement(
        resultingTarget = resultingTarget,
        sourceRange = target.toKastLocation(target.textRange),
        signature = signature,
        outboundEvidence = ReplacementOutboundEvidence.Complete.of(outbound.size),
        outboundReferences = outbound,
    )
}
