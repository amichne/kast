@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.edit.IdeaEditApplier

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.result.AnalysisAvailabilityState
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.result.FileSystemDiscoveryState
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.IndexAdmissionState
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.RefreshExternalFailureOutcome
import io.github.amichne.kast.api.contract.result.RefreshExternalFailureStatus
import io.github.amichne.kast.api.contract.result.RefreshRelationshipFailure
import io.github.amichne.kast.api.contract.result.SemanticAdmissionStatus
import io.github.amichne.kast.api.contract.result.SemanticGraphExternalBoundaryFailureId
import io.github.amichne.kast.api.contract.result.SemanticGraphExternalBoundaryReason
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ExactRenameOccurrence
import io.github.amichne.kast.api.contract.result.ExactRenameProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.result.RenameOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.MutationProofIncompleteException
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
import io.github.amichne.kast.shared.analysis.ImportAnalysis
import io.github.amichne.kast.shared.analysis.declarationEdit
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.visibility
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.analysis.api.analyze
import java.nio.file.Files
import java.util.concurrent.CancellationException
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*
import io.github.amichne.kast.indexstore.api.index.FileStageFailureExternalizationResult
import io.github.amichne.kast.indexstore.api.index.FileStageFailureCode
import io.github.amichne.kast.indexstore.api.index.FileStageFailureId

internal suspend fun KastIndexerBackend.renameOperation(query: ParsedRenameQuery): RenameResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.RENAME, "kast.idea.rename") {
        val (snapshot, referencePlans) = collectInShortReadActions(
            collectSnapshot = {
                val file = findKtFile(query.position.filePath.value)
                val target = resolveTarget(file, query.position.offset.value)
                val targetSymbol = analyze(file) {
                    target.toSymbolModel(
                        containingDeclaration = compilerContainingDeclarationName(target),
                    )
                }
                val visibility = target.visibility()
                val (searchScope, scopeKind) = visibilityScopedSearch(target, visibility)
                val candidateFileCount = kotlinFileType()?.let { fileType ->
                    FileTypeIndex.getFiles(fileType, searchScope)
                        .count { isWorkspaceFile(it.path) }
                } ?: 0
                val refs = mutableListOf<PsiReference>()
                ReferencesSearch.search(target, searchScope).forEach(
                    object : Processor<PsiReference> {
                        override fun process(ref: PsiReference): Boolean {
                            ProgressManager.checkCanceled()
                            refs.add(ref)
                            return true
                        }
                    },
                )
                RenameSnapshot(
                    declarationEdit = target.declarationEdit(query.newName.value),
                    targetIdentity = targetSymbol.relationshipIdentity(),
                    generation = psiGeneration(),
                    searchScope = searchScope,
                    visibility = visibility,
                    scopeKind = scopeKind,
                    candidateFileCount = candidateFileCount,
                    collectedReferenceCount = refs.size,
                ) to refs
            },
            processItem = { ref ->
                val occurrence = ref.toReferenceOccurrence(includeUsageSiteScope = false)
                    ?: return@collectInShortReadActions null
                if (occurrence.containingSymbol is ContainingSymbolEvidence.Unavailable) {
                    return@collectInShortReadActions null
                }
                val resolvedTarget = ref.resolve() ?: return@collectInShortReadActions null
                val resolvedTargetFile = resolvedTarget.containingFile as? KtFile
                    ?: return@collectInShortReadActions null
                val resolvedTargetIdentity = try {
                    analyze(resolvedTargetFile) {
                        resolvedTarget.toSymbolModel(
                            containingDeclaration = compilerContainingDeclarationName(resolvedTarget),
                        )
                    }.relationshipIdentity()
                } catch (failure: ProcessCanceledException) {
                    throw failure
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    return@collectInShortReadActions null
                }
                RenameReferencePlan(
                    occurrence = occurrence,
                    resolvedTarget = resolvedTargetIdentity,
                    edit = TextEdit(
                        filePath = occurrence.location.filePath,
                        startOffset = occurrence.location.startOffset,
                        endOffset = occurrence.location.endOffset,
                        newText = query.newName.value,
                    ),
                )
            },
            runInitialReadAction = { action -> runIdeaReadAction(action) },
            runBatchReadAction = { action -> runIdeaReadAction(action) },
        )

        val provenReferencePlans = referencePlans.filter { plan ->
            plan.resolvedTarget == snapshot.targetIdentity
        }
        if (provenReferencePlans.size != snapshot.collectedReferenceCount) {
            throw MutationProofIncompleteException(
                limitedReferenceEvidence(
                    knownMinimumCount = provenReferencePlans.size,
                    reason = ReferencePartialReason.PSI_RESOLUTION_FAILED,
                    searchScope = snapshot.searchScope,
                ),
                message = "Rename references could not all retain compiler occurrence evidence",
            )
        }

        val exactReferencePlans = provenReferencePlans
            .distinctBy { plan -> plan.occurrence.location.sourceRangeKey() }
            .sortedWith(
                compareBy(
                    { plan -> plan.occurrence.location.filePath },
                    { plan -> plan.occurrence.location.startOffset },
                    { plan -> plan.occurrence.location.endOffset },
                ),
            )

        val edits = (listOf(snapshot.declarationEdit) + exactReferencePlans.map(RenameReferencePlan::edit))
            .distinctBy { Triple(it.filePath, it.startOffset, it.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        val fileImages = try {
            planExactMutationFileImages(edits)
        } catch (failure: ExactMutationFileImagePlanningException) {
            throw MutationProofIncompleteException(
                evidence = RelationshipResultEvidence.Limited(
                    cardinality = ResultCardinality.KnownMinimum(exactReferencePlans.size),
                    coverage = RelationshipSearchCoverage.limited(
                        RelationshipSearchLimitation.SOURCE_IMAGE_UNPROVEN,
                    ),
                ),
                message = "Rename exact source image proof failed: ${failure.failure.name}",
            )
        }
        val fileHashes = fileImages.map { image ->
            FileHash(
                filePath = image.filePath.value,
                hash = image.preimage.sha256.value,
            )
        }

        val rootKind = snapshot.targetIdentity.kind.renameRelationshipRootKind()
            ?: throw MutationProofIncompleteException(
                RelationshipResultEvidence.Limited(
                    cardinality = ResultCardinality.KnownMinimum(exactReferencePlans.size),
                    coverage = RelationshipSearchCoverage.limited(
                        RelationshipSearchLimitation.IDENTITY_UNPROVEN,
                        RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE,
                    ),
                ),
                message = "Rename target kind cannot establish an exact relationship family",
            )
        val selector = KastExactSymbolSelector(
            fqName = snapshot.targetIdentity.fqName,
            declarationFile = snapshot.targetIdentity.declarationFile.value,
            declarationStartOffset = snapshot.targetIdentity.declarationStartOffset.value,
            kind = snapshot.targetIdentity.kind,
            containingType = snapshot.targetIdentity.containingType,
        )
        val admission = timedReadAction(
            telemetry,
            IdeaTelemetryScope.RENAME,
            "kast.idea.rename.prove",
        ) {
            completeLiveRenameRelationshipCoverageAdmission(
                selector = selector,
                rootKind = rootKind,
                requiredGeneration = snapshot.generation,
                knownMinimumCount = exactReferencePlans.size,
            )
        }
        val proof = when (admission) {
            is CompleteRelationshipCoverageAdmission.Limited ->
                throw MutationProofIncompleteException(admission.evidence)
            is CompleteRelationshipCoverageAdmission.Proven -> ExactRenameProof.of(
                target = snapshot.targetIdentity,
                requiredGeneration = MutationSemanticGeneration(admission.generation),
                evidence = RelationshipResultEvidence.Complete(
                    cardinality = ResultCardinality.Exact(exactReferencePlans.size),
                    coverage = admission.coverage,
                ),
                occurrences = exactReferencePlans.map { plan ->
                    ExactRenameOccurrence(
                        reference = plan.occurrence,
                        resolvedTarget = plan.resolvedTarget,
                        provenance = RenameOccurrenceProvenance.COMPILER,
                    )
                },
            )
        }

        RenameResult.of(
            edits = edits,
            fileHashes = fileHashes,
            fileImages = fileImages,
            proof = proof,
            searchScope = SearchScope(
                visibility = snapshot.visibility,
                scope = snapshot.scopeKind,
                exhaustive = true,
                candidateFileCount = snapshot.candidateFileCount,
                searchedFileCount = snapshot.candidateFileCount,
            ),
        )
        }
    }

internal fun SymbolKind.renameRelationshipRootKind(): RelationshipRootKind? = when (this) {
    SymbolKind.CLASS,
    SymbolKind.INTERFACE,
    SymbolKind.OBJECT,
    -> RelationshipRootKind.TYPE
    SymbolKind.FUNCTION,
    SymbolKind.PROPERTY,
    SymbolKind.PARAMETER,
    -> RelationshipRootKind.CALLABLE
    SymbolKind.UNKNOWN -> null
}

private fun io.github.amichne.kast.api.contract.Location.sourceRangeKey(): Triple<String, Int, Int> =
    Triple(filePath, startOffset, endOffset)
