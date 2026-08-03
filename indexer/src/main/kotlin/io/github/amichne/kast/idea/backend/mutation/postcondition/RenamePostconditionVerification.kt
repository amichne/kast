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
import io.github.amichne.kast.idea.backend.relationships.completeLiveRenameRelationshipCoverageAdmission
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

internal fun KastIndexerBackend.verifyRename(
    authority: ParsedMutationPostconditionAuthority.Rename,
    generation: Long,
): MutationPostconditionEvidence.Rename {
    val adjusted = adjustedRenameRanges(authority.edits)
    val declarationEdit = authority.edits.singleOrNull { edit ->
        edit.filePath == authority.proof.target.declarationFile.value &&
            edit.startOffset == authority.proof.target.declarationStartOffset.value
    } ?: failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "Persisted rename authority has no exact declaration edit",
    )
    val declarationRange = adjusted.getValue(declarationEdit.rangeKey())
    val file = currentKtFile(declarationEdit.filePath)
    val target = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .singleOrNull { declaration ->
            declaration.nameIdentifier?.textRange?.let { range ->
                range.startOffset == declarationRange.first && range.endOffset == declarationRange.last + 1
            } == true
        } ?: failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "The renamed declaration is not present at its exact adjusted range",
    )
    val resultingTarget = compilerSourceIdentity(target)
    val expectedTarget = authority.proof.target.copy(
        fqName = renamedFqName(authority.proof.target.fqName, declarationEdit.newText),
        declarationStartOffset = NonNegativeInt(declarationRange.first),
    )
    if (resultingTarget != expectedTarget) failPostcondition(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "The renamed declaration does not have the exact expected compiler identity",
    )

    val (searchScope, _) = visibilityScopedSearch(target, target.visibility())
    val rawReferences = mutableListOf<PsiReference>()
    ReferencesSearch.search(target, searchScope).forEach(
        object : Processor<PsiReference> {
            override fun process(reference: PsiReference): Boolean {
                ProgressManager.checkCanceled()
                rawReferences.add(reference)
                return true
            }
        },
    )
    val occurrences = rawReferences.map { reference ->
        val occurrence = reference.toReferenceOccurrence(includeUsageSiteScope = false)
            ?: failPostcondition(
                MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
                "A renamed occurrence lost exact compiler source evidence",
            )
        if (occurrence.containingSymbol is ContainingSymbolEvidence.Unavailable) failPostcondition(
            MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
            "A renamed occurrence lost compiler-visible containing-symbol evidence",
        )
        val resolved = reference.resolve() ?: failPostcondition(
            MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
            "A renamed occurrence no longer resolves",
        )
        val resolvedFile = resolved.containingFile as? KtFile ?: failPostcondition(
            MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
            "A renamed occurrence no longer resolves to Kotlin source",
        )
        val identity = analyze(resolvedFile) {
            resolved.toSymbolModel(compilerContainingDeclarationName(resolved))
        }.relationshipIdentity()
        if (identity != resultingTarget) failPostcondition(
            MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
            "A renamed occurrence resolves to a different compiler target",
        )
        ExactRenameOccurrence(occurrence, identity, RenameOccurrenceProvenance.COMPILER)
    }.distinctBy { occurrence -> occurrence.reference.location.rangeKey() }
        .sortedWith(compareBy(
            { it.reference.location.filePath },
            { it.reference.location.startOffset },
            { it.reference.location.endOffset },
        ))

    val expectedRanges = authority.proof.occurrences.map { occurrence ->
        val oldRange = occurrence.reference.location.rangeKey()
        val edit = authority.edits.singleOrNull { candidate -> candidate.rangeKey() == oldRange }
            ?: failPostcondition(
                MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
                "Persisted rename authority dropped one proven occurrence edit",
            )
        val currentRange = adjusted.getValue(edit.rangeKey())
        Triple(edit.filePath, currentRange.first, currentRange.last + 1)
    }.toSet()
    val currentRanges = occurrences.map { it.reference.location.rangeKey() }.toSet()
    if (currentRanges != expectedRanges || occurrences.size != authority.proof.occurrences.size) failPostcondition(
        MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH,
        "The renamed reference set or exact cardinality changed",
    )

    val rootKind = resultingTarget.kind.renameRelationshipRootKind() ?: failPostcondition(
        MutationPostconditionLimitation.REFERENCE_COVERAGE_INCOMPLETE,
        "The renamed target kind cannot prove complete relationship coverage",
    )
    val admission = completeLiveRenameRelationshipCoverageAdmission(
        selector = KastExactSymbolSelector(
            fqName = resultingTarget.fqName,
            declarationFile = resultingTarget.declarationFile.value,
            declarationStartOffset = resultingTarget.declarationStartOffset.value,
            kind = resultingTarget.kind,
            containingType = resultingTarget.containingType,
        ),
        rootKind = rootKind,
        requiredGeneration = generation,
        knownMinimumCount = occurrences.size,
    )
    val coverage = when (admission) {
        is CompleteRelationshipCoverageAdmission.Limited -> failPostcondition(
            MutationPostconditionLimitation.REFERENCE_COVERAGE_INCOMPLETE,
            "Complete renamed reference-family coverage could not be reproven",
        )
        is CompleteRelationshipCoverageAdmission.Proven -> admission.coverage
    }
    return MutationPostconditionEvidence.Rename(
        resultingTarget = resultingTarget,
        evidence = RelationshipResultEvidence.Complete(
            cardinality = ResultCardinality.Exact(occurrences.size),
            coverage = coverage,
        ),
        occurrences = occurrences,
    )
}

private fun adjustedRenameRanges(edits: List<TextEdit>): Map<Triple<String, Int, Int>, IntRange> = buildMap {
    edits.groupBy(TextEdit::filePath).forEach { (_, fileEdits) ->
        var delta = 0
        fileEdits.sortedBy(TextEdit::startOffset).forEach { edit ->
            val start = edit.startOffset + delta
            put(edit.rangeKey(), start until start + edit.newText.length)
            delta += edit.newText.length - (edit.endOffset - edit.startOffset)
        }
    }
}

private fun renamedFqName(old: String, newName: String): String =
    old.substringBeforeLast('.', missingDelimiterValue = "")
        .let { owner -> if (owner.isEmpty()) newName else "$owner.$newName" }

private fun TextEdit.rangeKey(): Triple<String, Int, Int> = Triple(filePath, startOffset, endOffset)

private fun Location.rangeKey(): Triple<String, Int, Int> = Triple(filePath, startOffset, endOffset)
