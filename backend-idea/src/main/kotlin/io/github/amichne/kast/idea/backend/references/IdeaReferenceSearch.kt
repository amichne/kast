@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.references

import io.github.amichne.kast.idea.backend.KastPluginBackend

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
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

internal fun KastPluginBackend.ideaReferenceSearch(
        query: ParsedReferencesQuery,
        plan: ReferenceSearchPlan,
        continuation: ReferenceContinuationPosition.Idea?,
        span: IdeaTelemetrySpan,
    ): ReferenceSearchOutcome = span.child("kast.idea.findReferences.findUsagesFallback") { fallbackSpan ->
        val budget = try {
            ReferenceSearchBudget.start(limits, referenceSearchClock)
        } catch (failure: Throwable) {
            continuation?.traversal?.close()
            throw failure
        }
        fallbackSpan.setAttribute("kast.references.fallbackApi", "server-held-psi-traversal")
        val locations = mutableListOf<ReferenceOccurrence>()
        var completion: ReferenceSearchCompletion = ReferenceSearchCompletion.Exhaustive
        var pathProbes = 0
        var psiFileProbes = 0
        var elementProbes = 0
        var referenceProbes = 0
        var compilerProviderProbes = 0
        val compilerProviderProbeLimit = Math.addExact(query.maxResults.value, 1)
        var resolutionFailed = false
        var position: ReferenceContinuationPosition.Idea? = continuation
        try {
            runIdeaReadAction {
                val currentGeneration = psiGeneration()
                readEpochObserver.entered(IdeaReadEpochKind.REFERENCES)
                if (continuation != null && continuation.generation != currentGeneration) {
                    continuation.traversal.close()
                    throw ConflictException(
                        message = "Kotlin PSI changed after the preceding reference page",
                        details = mapOf(
                            "pageTokenSource" to "IDEA",
                            "continuationFailure" to "generationChanged",
                        ),
                    )
                }
                if (position == null) {
                    val searchRoots = referenceSearchRoots(plan)
                    if (searchRoots.isEmpty()) {
                        throw NotFoundException("The reference target has no searchable source root")
                    }
                    position = ReferenceContinuationPosition.Idea(
                        traversal = IdeaReferenceTraversal(searchRoots, referenceTraversalObserver),
                        pending = null,
                        generation = currentGeneration,
                        candidateFilePaths = linkedSetOf(),
                        searchedFilePaths = linkedSetOf(),
                        seenLocations = linkedSetOf(),
                    )
                }
                val activePosition = requireNotNull(position)
                activePosition.pending?.let(locations::add)
                val target = plan.target.element
                    ?: run {
                        completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.TARGET_INVALIDATED)
                        activePosition.traversal.exhausted = true
                        activePosition.traversal.close()
                        return@runIdeaReadAction
                    }
                search@ while (
                    locations.size <= query.maxResults.value &&
                    (activePosition.traversal.currentFile != null || pathProbes < REFERENCE_DISCOVERY_PATH_LIMIT)
                ) {
                    ProgressManager.checkCanceled()
                    if (budget.requestExhausted()) {
                        completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                        break
                    }
                    var currentFile = activePosition.traversal.currentFile
                    if (currentFile == null) {
                        while (pathProbes < REFERENCE_DISCOVERY_PATH_LIMIT) {
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            if (!activePosition.traversal.paths.hasNext()) {
                                activePosition.traversal.exhausted = true
                                activePosition.traversal.close()
                                break
                            }
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            val path = activePosition.traversal.paths.next()
                            pathProbes += 1
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            val fileName = path.fileName.toString()
                            if (
                                !Files.isRegularFile(path) ||
                                !(fileName.endsWith(".kt") || fileName.endsWith(".kts"))
                            ) continue
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path.toString()) ?: continue
                            if (!plan.searchScope.contains(virtualFile)) continue
                            currentFile = virtualFile
                            activePosition.traversal.currentFile = virtualFile
                            activePosition.traversal.nextOffset = 0
                            activePosition.traversal.nextReferenceIndex = 0
                            activePosition.candidateFilePaths += virtualFile.path
                            break
                        }
                        if (currentFile == null) {
                            break
                        }
                    }
                    if (budget.requestExhausted()) {
                        completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                        break
                    }
                    psiFileProbes += 1
                    val file = PsiManager.getInstance(project).findFile(currentFile)
                    if (file == null) {
                        activePosition.searchedFilePaths += currentFile.path
                        activePosition.traversal.currentFile = null
                        activePosition.traversal.nextOffset = 0
                        activePosition.traversal.nextReferenceIndex = 0
                        continue
                    }
                    val fileStartedNanos = budget.fileStarted()
                    var leaf = file.findElementAt(activePosition.traversal.nextOffset)
                    while (leaf != null) {
                        if (locations.size > query.maxResults.value) break@search
                        if (budget.requestExhausted()) {
                            completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED)
                            break@search
                        }
                        if (budget.fileExhausted(fileStartedNanos)) {
                            completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.FILE_BUDGET_EXHAUSTED)
                            break@search
                        }
                        val leafStart = leaf.textRange.startOffset
                        val nextLeaf = PsiTreeUtil.nextLeaf(leaf, true)
                        elementProbes += 1
                        val references = referencesAtLeaf(file, leaf, leafStart)
                        var referenceIndex = activePosition.traversal.nextReferenceIndex
                        activePosition.traversal.nextOffset = leafStart
                        while (referenceIndex < references.size) {
                            if (budget.requestExhausted()) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.REQUEST_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            if (budget.fileExhausted(fileStartedNanos)) {
                                completion = ReferenceSearchCompletion.Partial(
                                    ReferencePartialReason.FILE_BUDGET_EXHAUSTED,
                                )
                                break@search
                            }
                            val reference = references[referenceIndex]
                            referenceProbes += 1
                            val resolved = try {
                                reference.resolve()
                            } catch (failure: ProcessCanceledException) {
                                throw failure
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                resolutionFailed = true
                                null
                            }
                            if (
                                resolved != null &&
                                (resolved == target || resolved.navigationElement == target.navigationElement)
                            ) {
                                reference.toReferenceOccurrence(query.includeUsageSiteScope)?.let { occurrence ->
                                    if (activePosition.seenLocations.add(occurrence.location.key())) {
                                        locations += occurrence
                                    }
                                }
                            }
                            referenceTraversalObserver.referenceProcessed(
                                filePath = currentFile.path,
                                leafOffset = leafStart,
                                referenceIndex = referenceIndex,
                                referenceCount = references.size,
                            )
                            referenceIndex += 1
                            activePosition.traversal.nextReferenceIndex = referenceIndex
                            if (locations.size > query.maxResults.value) break@search
                        }
                        activePosition.traversal.nextOffset = nextLeaf?.textRange?.startOffset ?: file.textLength
                        activePosition.traversal.nextReferenceIndex = 0
                        leaf = nextLeaf
                    }
                    var providerStoppedForBudget = false
                    var providerStoppedForPage = false
                    var providerStoppedForLimit = false
                    val providerCompleted = ReferencesSearch.search(target, LocalSearchScope(file)).forEach(
                        Processor { reference ->
                            if (budget.requestExhausted() || budget.fileExhausted(fileStartedNanos)) {
                                providerStoppedForBudget = true
                                return@Processor false
                            }
                            compilerProviderProbes += 1
                            if (compilerProviderProbes > compilerProviderProbeLimit) {
                                providerStoppedForLimit = true
                                return@Processor false
                            }
                            reference.toReferenceOccurrence(query.includeUsageSiteScope)?.let { occurrence ->
                                if (activePosition.seenLocations.add(occurrence.location.key())) {
                                    locations += occurrence
                                }
                            }
                            if (locations.size > query.maxResults.value) {
                                providerStoppedForPage = true
                                false
                            } else {
                                true
                            }
                        },
                    )
                    if (!providerCompleted) {
                        completion = when {
                            providerStoppedForBudget -> ReferenceSearchCompletion.Partial(
                                ReferencePartialReason.FILE_BUDGET_EXHAUSTED,
                            )
                            providerStoppedForLimit -> ReferenceSearchCompletion.Partial(
                                ReferencePartialReason.COMPILER_PROVIDER_LIMIT_EXHAUSTED,
                            )
                            providerStoppedForPage -> completion
                            else -> ReferenceSearchCompletion.Partial(ReferencePartialReason.PSI_RESOLUTION_FAILED)
                        }
                        if (providerStoppedForLimit) {
                            activePosition.traversal.exhausted = true
                            activePosition.traversal.close()
                        }
                        break@search
                    }
                    activePosition.searchedFilePaths += currentFile.path
                    activePosition.traversal.currentFile = null
                    activePosition.traversal.nextOffset = 0
                    activePosition.traversal.nextReferenceIndex = 0
                }
            }
        } catch (failure: Throwable) {
            position?.traversal?.close()
            throw failure
        }
        val completedPosition = requireNotNull(position)
        if (resolutionFailed && completion == ReferenceSearchCompletion.Exhaustive) {
            completion = ReferenceSearchCompletion.Partial(ReferencePartialReason.PSI_RESOLUTION_FAILED)
        }
        val pageReferences = locations.take(query.maxResults.value).sortedWith(referenceOccurrenceOrder)
        val pending = locations.getOrNull(query.maxResults.value)
        val nextPosition = if (completedPosition.traversal.exhausted) {
            null
        } else {
            completedPosition.copy(pending = pending)
        }
        fallbackSpan.setAttribute("kast.references.pathProbeCount", pathProbes)
        fallbackSpan.setAttribute("kast.references.psiFileProbeCount", psiFileProbes)
        fallbackSpan.setAttribute("kast.references.elementProbeCount", elementProbes)
        fallbackSpan.setAttribute("kast.references.referenceProbeCount", referenceProbes)
        fallbackSpan.setAttribute("kast.references.compilerProviderProbeCount", compilerProviderProbes)
        fallbackSpan.setAttribute("kast.references.candidateFileCount", completedPosition.candidateFilePaths.size)
        fallbackSpan.setAttribute("kast.references.searchedFileCount", completedPosition.searchedFilePaths.size)
        fallbackSpan.setAttribute("kast.references.partialReason", completion.partialReason)
        fallbackSpan.child("kast.idea.findReferences.candidateDiscovery") { candidateSpan ->
            candidateSpan.setAttribute("kast.references.candidateFileCount", completedPosition.candidateFilePaths.size)
            candidateSpan.setAttribute("kast.references.pathProbeCount", pathProbes)
        }
        fallbackSpan.child("kast.idea.findReferences.referenceResolution") { resolutionSpan ->
            resolutionSpan.setAttribute("kast.references.elementProbeCount", elementProbes)
            resolutionSpan.setAttribute("kast.references.resultCount", pageReferences.size)
        }

        ReferenceSearchOutcome(
            source = ReferenceSearchSource.IDEA,
            references = pageReferences,
            consumedEvidence = pageReferences.size,
            observedEvidence = locations.size,
            nextPosition = nextPosition,
            candidateFileCount = completedPosition.candidateFilePaths.size,
            searchedFileCount = completedPosition.searchedFilePaths.size,
            completion = completion,
        )
    }
