@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.diagnostics

import io.github.amichne.kast.idea.backend.KastPluginBackend

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.searches.ReferencesSearch
import io.github.amichne.kast.api.continuation.ContinuationConsumeResult
import io.github.amichne.kast.api.continuation.ContinuationIssueResult
import io.github.amichne.kast.api.continuation.ContinuationStateTransition
import io.github.amichne.kast.api.continuation.ContinuationTransition
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.shared.analysis.toApiDiagnostics
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal suspend fun KastPluginBackend.diagnosticsOperation(query: ParsedDiagnosticsQuery): DiagnosticsResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.DIAGNOSTICS, "kast.idea.diagnostics") {
            val identity = DiagnosticQueryIdentity.from(query)
            val pageToken = query.pageToken
            val (projection, nextPageToken) = if (pageToken != null) {
                val token = pageToken
                when (val consumed = diagnosticContinuations.consume(
                    token = token,
                    query = identity,
                    transition = ContinuationStateTransition { state ->
                        val currentGeneration = runIdeaReadAction {
                            readEpochObserver.entered(IdeaReadEpochKind.DIAGNOSTICS)
                            psiGeneration()
                        }
                        if (state.generation != currentGeneration) {
                            throw ConflictException(
                                message = "Kotlin PSI changed after the preceding diagnostic page",
                                details = mapOf("pageToken" to token.value),
                            )
                        }
                        val page = DiagnosticContinuationProjection(
                            snapshot = state.snapshot,
                            pageOffset = state.nextOffset,
                        )
                        val nextOffset = diagnosticNextOffset(
                            state.snapshot,
                            state.nextOffset,
                            query.maxResults.value,
                        )
                        if (nextOffset < state.snapshot.diagnostics.size) {
                            state.advanceTo(nextOffset)
                            ContinuationTransition.Reissue(page, identity)
                        } else {
                            ContinuationTransition.Complete(page)
                        }
                    },
                )) {
                    is ContinuationConsumeResult.Completed -> consumed.output to null
                    is ContinuationConsumeResult.Reissued -> consumed.output to consumed.token.value
                    is ContinuationConsumeResult.Rejected -> throw ConflictException(
                        message = "The diagnostic page token is unknown, expired, consumed, or belongs to another query",
                        details = mapOf("pageToken" to token.value),
                    )
                }
            } else {
                val epoch = timedReadAction(
                    telemetry,
                    IdeaTelemetryScope.DIAGNOSTICS,
                    "kast.idea.diagnostics.snapshot",
                ) {
                    val currentGeneration = psiGeneration()
                    readEpochObserver.entered(IdeaReadEpochKind.DIAGNOSTICS)
                    val fileAnalyses = query.filePaths.value.map(::analyzeDiagnosticsFileInReadEpoch)
                    DiagnosticReadEpoch(
                        generation = currentGeneration,
                        snapshot = DiagnosticSnapshot(
                            diagnostics = fileAnalyses
                                .flatMap(DiagnosticsFileAnalysis::diagnostics)
                                .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" })),
                            fileStatuses = fileAnalyses.map(DiagnosticsFileAnalysis::status),
                            fileHashes = fileAnalyses.mapNotNull(DiagnosticsFileAnalysis::fileHash),
                        ),
                    )
                }
                val projection = DiagnosticContinuationProjection(epoch.snapshot, pageOffset = 0)
                val nextOffset = diagnosticNextOffset(epoch.snapshot, 0, query.maxResults.value)
                val nextToken = if (nextOffset < epoch.snapshot.diagnostics.size) {
                    when (val issued = diagnosticContinuations.issue(
                        query = identity,
                        state = DiagnosticContinuationState(
                            generation = epoch.generation,
                            snapshot = epoch.snapshot,
                            nextOffset = nextOffset,
                        ),
                    )) {
                        is ContinuationIssueResult.Issued -> issued.token.value
                        is ContinuationIssueResult.Rejected -> throw ConflictException(
                            "Diagnostic continuation store is unavailable",
                        )
                    }
                } else {
                    null
                }
                projection to nextToken
            }

            DiagnosticsResult.paged(
                diagnostics = projection.snapshot.diagnostics,
                fileStatuses = projection.snapshot.fileStatuses,
                fileHashes = projection.snapshot.fileHashes,
                pageOffset = projection.pageOffset,
                maxResults = query.maxResults.value,
                nextPageToken = nextPageToken,
            )
        }
    }

internal fun KastPluginBackend.diagnosticNextOffset(
        snapshot: DiagnosticSnapshot,
        pageOffset: Int,
        maxResults: Int,
    ): Int {
        if (pageOffset !in 0..snapshot.diagnostics.size) {
            throw ConflictException("Server-held diagnostic continuation offset exceeded exact cardinality")
        }
        return Math.addExact(pageOffset, minOf(maxResults, snapshot.diagnostics.size - pageOffset))
    }

internal fun KastPluginBackend.analyzeDiagnosticsFileInReadEpoch(filePath: NormalizedPath): DiagnosticsFileAnalysis {
        if (Files.notExists(Path.of(filePath.value))) {
            return skippedDiagnostics(
                filePath = filePath,
                state = FileAnalysisState.MISSING_ON_DISK,
                message = "File not found: ${filePath.value}",
            )
        }
        if (!isWorkspaceFile(filePath.value)) {
            return skippedDiagnostics(
                filePath = filePath,
                state = FileAnalysisState.OUTSIDE_SOURCE_MODULES,
                message = "File is outside the active workspace: ${filePath.value}",
            )
        }

        return try {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath.value)
                ?: return skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.PENDING_INDEX,
                        message = "File exists on disk but is not available in the IDEA virtual file system",
                    )
            if (!ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) {
                return skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.OUTSIDE_SOURCE_MODULES,
                        message = "File is not contained in an IDEA source module",
                    )
            }
            if (DumbService.isDumb(project)) {
                return skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.PENDING_INDEX,
                        message = "IDEA indexing is still in progress",
                    )
            }
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.PENDING_INDEX,
                        message = "IDEA has not created PSI for the file yet",
                    )
            val file = psiFile as? KtFile
                ?: return skippedDiagnostics(
                        filePath = filePath,
                        state = FileAnalysisState.BACKEND_FAILURE,
                        message = "Semantic diagnostics require a Kotlin source file",
                    )
            val fileDiagnostics = analyze(file) {
                file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                    .flatMap { diagnostic -> diagnostic.toApiDiagnostics() }
            }
            val documentManager = FileDocumentManager.getInstance()
            val document = documentManager.getDocument(virtualFile)
            val fileHash = if (document != null && documentManager.isDocumentUnsaved(document)) {
                FileHashing.sha256(file.text)
            } else {
                FileHashing.sha256(Files.readAllBytes(Path.of(filePath.value)))
            }
            DiagnosticsFileAnalysis(
                status = FileAnalysisStatus.analyzed(filePath),
                diagnostics = fileDiagnostics,
                fileHash = FileHash(
                    filePath = filePath.value,
                    hash = fileHash,
                ),
            )
        } catch (ex: ProcessCanceledException) {
            throw ex
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            skippedDiagnostics(
                filePath = filePath,
                state = FileAnalysisState.BACKEND_FAILURE,
                message = ex.message?.takeIf(String::isNotBlank) ?: ex.toString(),
            )
        }
    }

internal fun KastPluginBackend.skippedDiagnostics(
        filePath: NormalizedPath,
        state: FileAnalysisState,
        message: String,
    ): DiagnosticsFileAnalysis = DiagnosticsFileAnalysis(
        status = FileAnalysisStatus.skipped(filePath, state, message),
        diagnostics = listOf(
            Diagnostic(
                location = Location(
                    filePath = filePath.value,
                    startOffset = 0,
                    endOffset = 0,
                    startLine = 0,
                    startColumn = 0,
                    preview = "",
                ),
                severity = DiagnosticSeverity.ERROR,
                message = message,
                code = "ANALYSIS_FAILURE",
            ),
        ),
        fileHash = null,
    )

    // Note: Unlike the headless backend, IDEA's ReferencesSearch.search() resolves
    // import directives as reference sites, so explicit import FQN handling is not needed here.
