@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.references

import io.github.amichne.kast.idea.backend.KastPluginBackend

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.api.continuation.ContinuationConsumeResult
import io.github.amichne.kast.api.continuation.ContinuationAccessFailure
import io.github.amichne.kast.api.continuation.ContinuationIssueResult
import io.github.amichne.kast.api.continuation.ContinuationStateTransition
import io.github.amichne.kast.api.continuation.ContinuationTransition
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.resolveTarget
import io.github.amichne.kast.shared.analysis.supertypeNames
import io.github.amichne.kast.shared.analysis.targetFqNameAndPackage
import io.github.amichne.kast.shared.analysis.toKastLocation
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.analysis.usageSiteDeclarationScope
import io.github.amichne.kast.shared.analysis.visibility
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.CancellationException
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal suspend fun KastPluginBackend.resolveSymbolOperation(query: ParsedSymbolQuery): SymbolResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.RESOLVE, "kast.idea.resolveSymbol") {
            timedReadAction(telemetry, IdeaTelemetryScope.RESOLVE, "kast.idea.resolveSymbol.readAction") {
                val file = findKtFile(query.position.filePath.value)
                val target = resolveTarget(file, query.position.offset.value)
                SymbolResult(
                    analyze(file) {
                        target.toSymbolModel(
                            containingDeclaration = compilerContainingDeclarationName(target),
                            supertypes = supertypeNames(target),
                            includeDeclarationScope = query.includeDeclarationScope,
                            includeDocumentation = query.includeDocumentation,
                        )
                    },
                )
            }
        }
    }

internal suspend fun KastPluginBackend.findReferencesOperation(query: ParsedReferencesQuery): ReferencesResult = withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.REFERENCES, "kast.idea.findReferences") { span ->
            val identity = ReferenceQueryIdentity.from(query)
            val pageToken = query.pageToken
            val (projection, nextPageToken) = if (pageToken != null) {
                val token = pageToken
                when (val consumed = referenceContinuations.consume(
                    token = token,
                    query = identity,
                    transition = ContinuationStateTransition { state ->
                        val page = referenceContinuationPage(query, state, span)
                        val nextPosition = page.outcome.nextPosition
                            ?.takeIf { page.outcome.completion is ReferenceSearchCompletion.Exhaustive }
                        nextPosition?.let {
                            state.advanceTo(page.knownCount, it)
                            ContinuationTransition.Reissue(page, identity)
                        } ?: ContinuationTransition.Complete(page)
                    },
                )) {
                    is ContinuationConsumeResult.Completed -> consumed.output to null
                    is ContinuationConsumeResult.Reissued -> consumed.output to consumed.token
                    is ContinuationConsumeResult.Rejected -> throw ConflictException(
                        message = "The reference page token is unknown, expired, consumed, or belongs to another query",
                        details = mapOf(
                            "pageToken" to token.value,
                            "continuationFailure" to when (consumed.failure) {
                                ContinuationAccessFailure.ExpiredToken -> "expired"
                                ContinuationAccessFailure.QueryMismatch -> "queryMismatch"
                                ContinuationAccessFailure.StoreClosed,
                                ContinuationAccessFailure.TokenCollision,
                                ContinuationAccessFailure.UnknownToken,
                                -> "unknown"
                            },
                        ),
                    )
                }
            } else {
                val plan = referenceSearchPlan(query, span)
                val outcome = indexedReferenceSearch(query, plan, null, span)
                    ?: ideaReferenceSearch(query, plan, null, span)
                val knownCount = outcome.references.size
                val page = ReferenceContinuationProjection(plan, outcome, knownCount)
                val token = outcome.nextPosition
                    ?.takeIf { outcome.completion is ReferenceSearchCompletion.Exhaustive }
                    ?.let { nextPosition ->
                    when (val issued = referenceContinuations.issue(
                        query = identity,
                        state = ReferenceContinuationState(
                            plan = plan,
                            returnedBefore = knownCount,
                            position = nextPosition,
                        ),
                    )) {
                        is ContinuationIssueResult.Issued -> issued.token
                        is ContinuationIssueResult.Rejected -> throw ConflictException(
                            message = "Reference continuation store is unavailable",
                            details = mapOf("continuationFailure" to "boundSourceUnavailable"),
                        )
                    }
                }
                page to token
            }
            val plan = projection.plan
            val outcome = projection.outcome
            val evidence = when (val completion = outcome.completion) {
                ReferenceSearchCompletion.Exhaustive -> relationshipEvidence(
                    completion = if (outcome.hasMoreEvidence) {
                        RelationshipCoverageAuthority.FamilyCompletion.RESUMABLE
                    } else {
                        RelationshipCoverageAuthority.FamilyCompletion.COMPLETE
                    },
                    knownMinimumCount = projection.knownCount,
                )
                is ReferenceSearchCompletion.Partial -> limitedReferenceEvidence(
                    knownMinimumCount = projection.knownCount,
                    reason = completion.reason,
                )
            }
            val page = nextPageToken?.let { token ->
                PageInfo(
                    truncated = true,
                    nextPageToken = token.value,
                )
            }

            span.setAttribute("kast.references.source", outcome.source.name.lowercase())
            span.setAttribute("kast.references.visibility", plan.visibility.name)
            span.setAttribute("kast.references.scope", plan.scopeKind.name)
            span.setAttribute("kast.references.candidateFileCount", outcome.candidateFileCount)
            span.setAttribute("kast.references.searchedFileCount", outcome.searchedFileCount)
            span.setAttribute("kast.references.evidenceCount", outcome.consumedEvidence)
            span.setAttribute("kast.references.observedEvidenceCount", outcome.observedEvidence)
            span.setAttribute("kast.references.knownMinimumCount", evidence.cardinality.knownMinimum())
            span.setAttribute("kast.references.resultCount", outcome.references.size)
            span.setAttribute("kast.references.exhaustive", outcome.completion.exhaustive)
            span.setAttribute("kast.references.partialReason", outcome.completion.partialReason)

            ReferencesResult(
                declaration = plan.declaration,
                references = outcome.references,
                evidence = evidence,
                page = page,
                searchScope = SearchScope(
                    visibility = plan.visibility,
                    scope = plan.scopeKind,
                    exhaustive = outcome.completion.exhaustive && !outcome.hasMoreEvidence,
                    candidateCoverage = if (outcome.completion.exhaustive) {
                        SearchScope.CandidateCoverage.COMPLETE
                    } else {
                        SearchScope.CandidateCoverage.PARTIAL
                    },
                    candidateFileCount = outcome.candidateFileCount,
                    searchedFileCount = outcome.searchedFileCount,
                ),
            )
        }
    }

internal fun KastPluginBackend.referenceContinuationPage(
        query: ParsedReferencesQuery,
        continuation: ReferenceContinuationState,
        span: IdeaTelemetrySpan,
    ): ReferenceContinuationProjection {
        val outcome = when (val position = continuation.position) {
            is ReferenceContinuationPosition.Index -> indexedReferenceSearch(query, continuation.plan, position, span)
                ?: error("Indexed continuation must return an indexed outcome or throw a typed conflict")
            is ReferenceContinuationPosition.Idea -> ideaReferenceSearch(query, continuation.plan, position, span)
        }
        return ReferenceContinuationProjection(
            plan = continuation.plan,
            outcome = outcome,
            knownCount = Math.addExact(continuation.returnedBefore, outcome.references.size),
        )
    }

internal suspend fun KastPluginBackend.referenceSearchPlan(
        query: ParsedReferencesQuery,
        span: IdeaTelemetrySpan,
    ): ReferenceSearchPlan {
        val target = span.child("kast.idea.findReferences.targetResolution") {
            timedReadAction(
                telemetry = telemetry,
                scope = IdeaTelemetryScope.REFERENCES,
                name = "kast.idea.findReferences.targetResolution.readAction",
            ) {
                val file = findKtFile(query.position.filePath.value)
                val element = resolveTarget(file, query.position.offset.value)
                val targetFqName = element.targetFqNameAndPackage()?.first?.value
                val symbol = analyze(file) {
                    element.toSymbolModel(containingDeclaration = compilerContainingDeclarationName(element))
                }
                query.selector?.let { selector ->
                    val selectorMatches = selector.fqName == symbol.fqName &&
                        NormalizedPath.parse(selector.declarationFile) == NormalizedPath.parse(symbol.location.filePath) &&
                        selector.declarationStartOffset == symbol.location.startOffset &&
                        (selector.kind == null || selector.kind == symbol.kind) &&
                        (selector.containingType == null || selector.containingType == symbol.containingDeclaration)
                    if (!selectorMatches) {
                        throw ConflictException(
                            message = "The resolved reference target does not match its exact selector",
                            details = mapOf("referenceTarget" to "identityMismatch"),
                        )
                    }
                }
                ReferenceResolvedTarget(
                    pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
                    targetFqName = targetFqName,
                    exactTarget = targetFqName?.let { fqName ->
                        ExactReferenceTarget(
                            fqName = fqName,
                            declarationFile = NormalizedPath.parse(symbol.location.filePath),
                            declarationStartOffset = NonNegativeInt(symbol.location.startOffset),
                        )
                    },
                    declaration = if (query.includeDeclaration) {
                        symbol
                    } else {
                        null
                    },
                    visibility = element.visibility(),
                )
            }
        }
        val scope = span.child("kast.idea.findReferences.scopeCalculation") {
            timedReadAction(
                telemetry = telemetry,
                scope = IdeaTelemetryScope.REFERENCES,
                name = "kast.idea.findReferences.scopeCalculation.readAction",
            ) {
                val element = target.pointer.element
                    ?: throw NotFoundException(
                        "Cannot resolve symbol at ${query.position.filePath.value}:${query.position.offset.value}",
                    )
                val (searchScope, scopeKind) = visibilityScopedSearch(element, target.visibility)
                ReferenceScopePlan(
                    searchScope = searchScope,
                    scopeKind = scopeKind,
                )
            }
        }

        return ReferenceSearchPlan(
            target = target.pointer,
            targetFqName = target.targetFqName,
            exactTarget = target.exactTarget,
            declaration = target.declaration,
            visibility = target.visibility,
            searchScope = scope.searchScope,
            scopeKind = scope.scopeKind,
        )
    }

internal fun KastPluginBackend.indexedReferenceSearch(
        query: ParsedReferencesQuery,
        plan: ReferenceSearchPlan,
        continuation: ReferenceContinuationPosition.Index?,
        span: IdeaTelemetrySpan,
    ): ReferenceSearchOutcome? = span.child("kast.idea.findReferences.indexLookup") { indexSpan ->
        val target = plan.exactTarget ?: return@child null
        when (
            val lookup = referenceIndexLookup.referencesTo(
                target,
                continuation?.offset ?: io.github.amichne.kast.api.contract.NonNegativeInt(0),
                query.maxResults,
            )
        ) {
            IndexedReferenceLookupResult.NotReady -> {
                indexSpan.setAttribute("kast.references.indexReady", false)
                if (continuation != null) {
                    throw ConflictException(
                        message = "The source index became unavailable while continuing an indexed reference page",
                        details = mapOf(
                            "pageTokenSource" to "INDEX",
                            "continuationFailure" to "boundSourceUnavailable",
                        ),
                    )
                }
                null
            }
            is IndexedReferenceLookupResult.IdentityUnavailable -> {
                indexSpan.setAttribute("kast.references.indexReady", true)
                indexSpan.setAttribute("kast.references.indexIdentityAvailable", false)
                if (continuation != null) {
                    throw ConflictException(
                        message = "The source index cannot prove the exact reference target identity",
                        details = mapOf(
                            "pageTokenSource" to "INDEX",
                            "continuationFailure" to "indexIdentityUnavailable",
                        ),
                    )
                }
                null
            }
            is IndexedReferenceLookupResult.Ready -> {
                if (continuation != null && continuation.generation != lookup.generation) {
                    throw ConflictException(
                        message = "The source index changed after the preceding reference page",
                        details = mapOf(
                            "pageTokenSource" to "INDEX",
                            "continuationFailure" to "generationChanged",
                        ),
                    )
                }
                if (
                    continuation == null &&
                    lookup.page.references.isEmpty() &&
                    lookup.page.nextOffset == null
                ) {
                    indexSpan.setAttribute("kast.references.indexReady", true)
                    indexSpan.setAttribute("kast.references.indexEmptyFallback", true)
                    return@child null
                }
                val indexedRows = runIdeaReadAction {
                    lookup.page.references.filter { row -> indexedReferenceRowInScope(row, plan.searchScope) }
                }
                val indexedSourcePaths = indexedRows.mapTo(mutableSetOf()) { row -> row.sourcePath }
                val cumulativeCandidateFilePaths = continuation?.candidateFilePaths.orEmpty() + indexedSourcePaths
                val cumulativeSearchedFilePaths = continuation?.searchedFilePaths.orEmpty() + indexedSourcePaths
                val locations = indexedReferenceLocations(
                    rows = indexedRows,
                    includeUsageSiteScope = query.includeUsageSiteScope,
                )
                indexSpan.setAttribute("kast.references.indexReady", true)
                indexSpan.setAttribute("kast.references.indexRowCount", indexedRows.size)
                indexSpan.setAttribute("kast.references.indexLocationCount", locations.size)
                ReferenceSearchOutcome(
                    source = ReferenceSearchSource.INDEX,
                    references = locations,
                    consumedEvidence = lookup.page.references.size,
                    observedEvidence = lookup.page.references.size,
                    nextPosition = lookup.page.nextOffset?.let { nextOffset ->
                        ReferenceContinuationPosition.Index(
                            offset = nextOffset,
                            generation = lookup.generation,
                            candidateFilePaths = cumulativeCandidateFilePaths,
                            searchedFilePaths = cumulativeSearchedFilePaths,
                        )
                    },
                    candidateFileCount = cumulativeCandidateFilePaths.size,
                    searchedFileCount = cumulativeSearchedFilePaths.size,
                    completion = if (indexedRows.size == locations.size) {
                        ReferenceSearchCompletion.Exhaustive
                    } else {
                        ReferenceSearchCompletion.Partial(ReferencePartialReason.INDEX_LOCATION_UNRESOLVED)
                    },
                )
            }
        }
    }

internal fun KastPluginBackend.indexedReferenceRowInScope(
        row: SymbolReferenceRow,
        searchScope: GlobalSearchScope,
    ): Boolean {
        if (!isWorkspaceFile(row.sourcePath)) return false
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(row.sourcePath) ?: return false
        return virtualFile.isValid && !virtualFile.isDirectory && searchScope.contains(virtualFile)
    }

internal fun KastPluginBackend.indexedReferenceLocations(
        rows: List<SymbolReferenceRow>,
        includeUsageSiteScope: Boolean,
    ): List<ReferenceOccurrence> {
        val locations = mutableListOf<ReferenceOccurrence>()
        for (batch in rows.chunked(READ_ACTION_BATCH_SIZE)) {
            val batchLocations = runIdeaReadAction {
                batch.mapNotNull { row -> indexedReferenceLocationOrNull(row, includeUsageSiteScope) }
            }
            locations.addAll(batchLocations)
        }
        return locations
            .distinctBy { it.location.key() }
            .sortedWith(referenceOccurrenceOrder)
    }

internal fun KastPluginBackend.indexedReferenceLocationOrNull(
        row: SymbolReferenceRow,
        includeUsageSiteScope: Boolean,
    ): ReferenceOccurrence? = try {
        indexedReferenceLocation(row, includeUsageSiteScope)
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

internal fun KastPluginBackend.indexedReferenceLocation(
        row: SymbolReferenceRow,
        includeUsageSiteScope: Boolean,
    ): ReferenceOccurrence? {
        if (!isWorkspaceFile(row.sourcePath)) return null
        val file = findKtFile(row.sourcePath)
        val sourceOffset = row.sourceOffset.coerceIn(0, file.textLength)
        val anchor = file.findElementAt(sourceOffset) ?: return null
        val reference = anchor.referenceAtOffset(sourceOffset)
        val element = reference?.element ?: anchor
        if (!element.isValid) return null
        val range = reference?.absoluteTextRange() ?: indexedFallbackRange(file, row)
        val location = element.toKastLocation(range)
        val enrichedLocation = if (includeUsageSiteScope) {
            location.copy(usageSiteScope = element.usageSiteDeclarationScope())
        } else {
            location
        }
        return ReferenceOccurrence(
            location = enrichedLocation,
            containingSymbol = element.containingSymbolEvidence(),
        )
    }

internal fun KastPluginBackend.indexedFallbackRange(
        file: KtFile,
        row: SymbolReferenceRow,
    ): TextRange {
        val start = row.sourceOffset.coerceIn(0, file.textLength)
        val nameLength = row.targetFqName.substringAfterLast('.').length.coerceAtLeast(1)
        val end = (start + nameLength).coerceAtMost(file.textLength)
        return TextRange(start, end)
    }
