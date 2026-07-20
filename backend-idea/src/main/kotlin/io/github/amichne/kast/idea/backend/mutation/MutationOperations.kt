@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.idea.backend.KastPluginBackend
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
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.SemanticAdmissionStatus
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
import io.github.amichne.kast.shared.analysis.ImportAnalysis
import io.github.amichne.kast.shared.analysis.declarationEdit
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.visibility
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtFile
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

internal suspend fun KastPluginBackend.renameOperation(query: ParsedRenameQuery): RenameResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.RENAME, "kast.idea.rename") {
        val (snapshot, referenceEdits) = collectInShortReadActions(
            collectSnapshot = {
                val file = findKtFile(query.position.filePath.value)
                val target = resolveTarget(file, query.position.offset.value)
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
                    visibility = visibility,
                    scopeKind = scopeKind,
                    candidateFileCount = candidateFileCount,
                ) to refs
            },
            processItem = { ref ->
                val element = ref.element
                if (!element.isValid) return@collectInShortReadActions null
                val refFilePath = element.resolvedFilePath().value
                if (!isWorkspaceFile(refFilePath)) return@collectInShortReadActions null
                TextEdit(
                    filePath = refFilePath,
                    startOffset = ref.rangeInElement.startOffset + element.textRange.startOffset,
                    endOffset = ref.rangeInElement.endOffset + element.textRange.startOffset,
                    newText = query.newName.value,
                )
            },
            runInitialReadAction = { action -> runIdeaReadAction(action) },
            runBatchReadAction = { action -> runIdeaReadAction(action) },
        )

        val edits = (listOf(snapshot.declarationEdit) + referenceEdits)
            .distinctBy { Triple(it.filePath, it.startOffset, it.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        val affectedFiles = edits.map(TextEdit::filePath).distinct()
        val fileHashes = IdeaFileHashComputer.currentHashes(affectedFiles)

        RenameResult.of(
            edits = edits,
            fileHashes = fileHashes,
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

internal suspend fun KastPluginBackend.applyEditsOperation(query: ParsedApplyEditsQuery): ApplyEditsResult {
        return telemetry.inSpan(IdeaTelemetryScope.APPLY_EDITS, "kast.idea.applyEdits") {
            val applier = IdeaEditApplier(project, workspaceRoot, workspaceIdentity)
            applier.apply(query.toWire())
            // No asyncRefresh needed - IDEA APIs handle VFS updates automatically
        }
    }

internal suspend fun KastPluginBackend.optimizeImportsOperation(query: ParsedImportOptimizeQuery): ImportOptimizeResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.OPTIMIZE_IMPORTS, "kast.idea.optimizeImports") {
        val edits = query.filePaths.value
            .map { it.value }
            .distinct()
            .sorted()
            .flatMap { filePath ->
                timedReadAction(telemetry, IdeaTelemetryScope.OPTIMIZE_IMPORTS, "kast.idea.optimizeImports.file") {
                    ImportAnalysis.optimizeImportEdits(findKtFile(filePath))
                }
            }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        val affectedFiles = edits.map(TextEdit::filePath).distinct()
        ImportOptimizeResult(
            edits = edits,
            fileHashes = IdeaFileHashComputer.currentHashes(affectedFiles),
            affectedFiles = affectedFiles,
        )
        }
    }

internal suspend fun KastPluginBackend.refreshOperation(query: ParsedRefreshQuery): RefreshResult {
        return telemetry.inSpan(IdeaTelemetryScope.REFRESH, "kast.idea.refresh") {
            if (query.filePaths.isEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    VirtualFileManager.getInstance().asyncRefresh(null)
                }
                return@inSpan RefreshResult.full()
            }

            val admission = semanticAdmissionAwaiter.await(query.filePaths, ::probeSemanticAdmission)
            RefreshResult.focused(
                fileStatuses = admission.fileStatuses,
                attemptCount = admission.attemptCount,
                elapsedMillis = admission.elapsedMillis,
            )
        }
    }

internal suspend fun KastPluginBackend.probeSemanticAdmission(filePath: NormalizedPath): SemanticAdmissionStatus {
        val nioPath = filePath.toJavaPath()
        val fileSystem = LocalFileSystem.getInstance()
        if (Files.notExists(nioPath)) {
            fileSystem.findFileByNioFile(nioPath)?.refresh(false, false)
            nioPath.parent
                ?.let(fileSystem::refreshAndFindFileByNioFile)
                ?.refresh(false, false)
            return SemanticAdmissionStatus.removed(filePath)
        }

        val virtualFile = semanticAdmissionOperations.refreshAndFind(nioPath)
            ?: return pendingSemanticAdmission(
                filePath = filePath,
                fileSystemDiscovery = FileSystemDiscoveryState.PENDING,
                sourceModuleOwnership = SourceModuleOwnershipState.NOT_APPLICABLE,
                indexAdmission = IndexAdmissionState.NOT_APPLICABLE,
                message = "File exists on disk but IDEA has not discovered it in the virtual file system",
            )

        return timedReadAction(
            telemetry,
            IdeaTelemetryScope.REFRESH,
            "kast.idea.refresh.semanticAdmission",
        ) {
            if (!ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) {
                return@timedReadAction SemanticAdmissionStatus.incomplete(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OUTSIDE_SOURCE_MODULES,
                    indexAdmission = IndexAdmissionState.NOT_APPLICABLE,
                    analysisAvailability = AnalysisAvailabilityState.NOT_APPLICABLE,
                    analysisStatus = FileAnalysisStatus.skipped(
                        filePath,
                        FileAnalysisState.OUTSIDE_SOURCE_MODULES,
                        "File is not contained in an IDEA source module",
                    ),
                )
            }
            if (DumbService.isDumb(project)) {
                return@timedReadAction pendingSemanticAdmission(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
                    indexAdmission = IndexAdmissionState.PENDING,
                    message = "IDEA indexing is still in progress",
                )
            }
            val kotlinFileType = kotlinFileType()
            val indexScope = GlobalSearchScope.fileScope(project, virtualFile)
            val admittedToKotlinIndex = kotlinFileType != null &&
                FileTypeIndex.getFiles(kotlinFileType, indexScope).any { indexedFile -> indexedFile == virtualFile }
            if (!admittedToKotlinIndex) {
                return@timedReadAction pendingSemanticAdmission(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
                    indexAdmission = IndexAdmissionState.PENDING,
                    message = "IDEA has not admitted the file to the Kotlin index",
                )
            }
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@timedReadAction pendingSemanticAdmission(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
                    indexAdmission = IndexAdmissionState.ADMITTED,
                    message = "IDEA has not created PSI for the file yet",
                )
            val ktFile = psiFile as? KtFile
                ?: return@timedReadAction failedSemanticAdmission(
                    filePath,
                    "Semantic admission requires a Kotlin source file",
                )
            try {
                semanticAdmissionOperations.collectDiagnostics(ktFile)
            } catch (ex: ProcessCanceledException) {
                throw ex
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                return@timedReadAction failedSemanticAdmission(
                    filePath,
                    ex.message?.takeIf(String::isNotBlank) ?: ex.toString(),
                )
            }
            SemanticAdmissionStatus.admitted(filePath)
        }
    }

internal fun KastPluginBackend.pendingSemanticAdmission(
        filePath: NormalizedPath,
        fileSystemDiscovery: FileSystemDiscoveryState,
        sourceModuleOwnership: SourceModuleOwnershipState,
        indexAdmission: IndexAdmissionState,
        message: String,
    ): SemanticAdmissionStatus = SemanticAdmissionStatus.incomplete(
        filePath = filePath,
        fileSystemDiscovery = fileSystemDiscovery,
        sourceModuleOwnership = sourceModuleOwnership,
        indexAdmission = indexAdmission,
        analysisAvailability = AnalysisAvailabilityState.PENDING,
        analysisStatus = FileAnalysisStatus.skipped(
            filePath,
            FileAnalysisState.PENDING_INDEX,
            message,
        ),
    )

internal fun KastPluginBackend.failedSemanticAdmission(
        filePath: NormalizedPath,
        message: String,
    ): SemanticAdmissionStatus = SemanticAdmissionStatus.incomplete(
        filePath = filePath,
        fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
        sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
        indexAdmission = IndexAdmissionState.ADMITTED,
        analysisAvailability = AnalysisAvailabilityState.FAILED,
        analysisStatus = FileAnalysisStatus.skipped(
            filePath,
            FileAnalysisState.BACKEND_FAILURE,
            message,
        ),
    )
