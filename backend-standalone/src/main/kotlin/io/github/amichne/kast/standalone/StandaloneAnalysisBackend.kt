package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyPersistence
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CallHierarchyStats
import io.github.amichne.kast.api.CallNode
import io.github.amichne.kast.api.CallNodeTruncation
import io.github.amichne.kast.api.CallNodeTruncationReason
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.LocalDiskEditApplier
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.RuntimeState
import io.github.amichne.kast.api.RuntimeStatusResponse
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TextEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

@OptIn(KaExperimentalApi::class)
class StandaloneAnalysisBackend(
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val session: StandaloneAnalysisSession,
) : AnalysisBackend {
    private val readDispatcher = Dispatchers.IO.limitedParallelism(limits.maxConcurrentRequests)
    private val json = Json { prettyPrint = true }

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = "standalone",
        backendVersion = "0.1.0",
        workspaceRoot = workspaceRoot.toString(),
        readCapabilities = setOf(
            ReadCapability.RESOLVE_SYMBOL,
            ReadCapability.FIND_REFERENCES,
            ReadCapability.CALL_HIERARCHY,
            ReadCapability.DIAGNOSTICS,
        ),
        mutationCapabilities = setOf(
            MutationCapability.RENAME,
            MutationCapability.APPLY_EDITS,
        ),
        limits = limits,
    )

    override suspend fun runtimeStatus(): RuntimeStatusResponse {
        val capabilities = capabilities()
        return RuntimeStatusResponse(
            state = RuntimeState.READY,
            healthy = true,
            active = true,
            indexing = false,
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
            message = "Standalone analysis session is initialized",
        )
    }

    override suspend fun health(): HealthResponse {
        val capabilities = capabilities()
        return HealthResponse(
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
        )
    }

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult = withContext(readDispatcher) {
        val file = session.findKtFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        SymbolResult(analyze(file) { target.toSymbolModel(containingDeclaration = null) })
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult = withContext(readDispatcher) {
        val file = session.findKtFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        val references = session.allKtFiles()
            .flatMap { candidateFile -> candidateFile.findReferenceLocations(target) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        ReferencesResult(
            declaration = if (query.includeDeclaration) analyze(file) { target.toSymbolModel(containingDeclaration = null) } else null,
            references = references,
        )
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult = withContext(readDispatcher) {
        val file = session.findKtFile(query.position.filePath)
        val rootTarget = resolveTarget(file, query.position.offset)
        val budget = CallHierarchyBudget(
            maxTotalCalls = query.maxTotalCalls,
            maxChildrenPerNode = query.maxChildrenPerNode,
            timeoutMillis = query.timeoutMillis ?: limits.requestTimeoutMillis,
        )

        val root = buildCallNode(
            target = rootTarget,
            parentCallSite = null,
            direction = query.direction,
            depthRemaining = query.depth,
            pathKeys = emptySet(),
            budget = budget,
            currentDepth = 0,
        )
        val stats = budget.toStats()
        val persistence = if (query.persistToGitShaCache) persistCallHierarchy(query, root, stats) else null

        CallHierarchyResult(
            root = root,
            stats = stats,
            persistence = persistence,
        )
    }

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult = withContext(readDispatcher) {
        val diagnostics = query.filePaths
            .sorted()
            .flatMap { filePath ->
                val file = session.findKtFile(filePath)
                analyze(file) {
                    file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                }.flatMap { diagnostic -> diagnostic.toApiDiagnostics() }
            }
            .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" }))

        DiagnosticsResult(diagnostics = diagnostics)
    }

    override suspend fun rename(query: RenameQuery): RenameResult = withContext(readDispatcher) {
        val file = session.findKtFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        val edits = (listOf(target.declarationEdit(query.newName)) + session.allKtFiles()
            .flatMap { candidateFile -> candidateFile.referenceEdits(target, query.newName) })
            .distinctBy { edit -> Triple(edit.filePath, edit.startOffset, edit.endOffset) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
        val fileHashes = currentFileHashes(edits.map(TextEdit::filePath))

        RenameResult(
            edits = edits,
            fileHashes = fileHashes,
            affectedFiles = fileHashes.map(FileHash::filePath),
        )
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult = LocalDiskEditApplier.apply(query)

    private fun KtFile.findReferenceLocations(target: PsiElement): List<io.github.amichne.kast.api.Location> {
        val references = mutableListOf<io.github.amichne.kast.api.Location>()

        // The standalone Analysis API session does not register the ReferencesSearch extension point,
        // so resolve references directly across the loaded PSI files.
        accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    element.references.forEach { reference ->
                        val resolved = reference.resolve()
                        if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                            references += reference.element.toKastLocation(
                                com.intellij.openapi.util.TextRange(
                                    reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                    reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                                )
                            )
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        return references
    }

    private fun KtFile.referenceEdits(
        target: PsiElement,
        newName: String,
    ): List<TextEdit> {
        val edits = mutableListOf<TextEdit>()

        accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    element.references.forEach { reference ->
                        val resolved = reference.resolve()
                        if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                            val elementStart = reference.element.textRange.startOffset
                            edits += TextEdit(
                                filePath = reference.element.containingFile.virtualFile?.path
                                    ?: reference.element.containingFile.viewProvider.virtualFile.path,
                                startOffset = elementStart + reference.rangeInElement.startOffset,
                                endOffset = elementStart + reference.rangeInElement.endOffset,
                                newText = newName,
                            )
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        return edits
    }

    private fun currentFileHashes(filePaths: Collection<String>): List<FileHash> = LocalDiskEditApplier.currentHashes(filePaths)

    private fun buildCallNode(
        target: PsiElement,
        parentCallSite: Location?,
        direction: CallDirection,
        depthRemaining: Int,
        pathKeys: Set<String>,
        budget: CallHierarchyBudget,
        currentDepth: Int,
    ): CallNode {
        val symbol = target.toSymbolModel(containingDeclaration = null)
        val nodeKey = symbolIdentityKey(target, symbol)
        budget.observeDepth(currentDepth)
        budget.nodes += 1

        if (budget.timeoutReached()) {
            return CallNode(
                symbol = symbol,
                callSite = parentCallSite,
                truncation = CallNodeTruncation(CallNodeTruncationReason.TIMEOUT, "Traversal timeout reached"),
                children = emptyList(),
            ).also { budget.truncatedNodes += 1 }
        }
        if (depthRemaining == 0) {
            return CallNode(symbol = symbol, callSite = parentCallSite, children = emptyList())
        }

        val edges = findCallEdges(target, direction)
        val children = mutableListOf<CallNode>()
        var truncation: CallNodeTruncation? = null

        for ((index, edge) in edges.withIndex()) {
            if (budget.timeoutReached()) {
                truncation = CallNodeTruncation(CallNodeTruncationReason.TIMEOUT, "Traversal timeout reached")
                budget.timeoutHit = true
                break
            }
            if (budget.edges >= budget.maxTotalCalls) {
                truncation = CallNodeTruncation(
                    CallNodeTruncationReason.MAX_TOTAL_CALLS,
                    "Reached maxTotalCalls=${budget.maxTotalCalls}",
                )
                budget.maxTotalCallsHit = true
                break
            }
            if (children.size >= budget.maxChildrenPerNode) {
                truncation = CallNodeTruncation(
                    CallNodeTruncationReason.MAX_CHILDREN_PER_NODE,
                    "Reached maxChildrenPerNode=${budget.maxChildrenPerNode}",
                )
                budget.maxChildrenHit = true
                break
            }

            budget.edges += 1
            val childKey = symbolIdentityKey(edge.target, edge.symbol)
            val child = if (childKey in pathKeys || childKey == nodeKey) {
                budget.nodes += 1
                budget.truncatedNodes += 1
                CallNode(
                    symbol = edge.symbol,
                    callSite = edge.callSite,
                    truncation = CallNodeTruncation(
                        CallNodeTruncationReason.CYCLE,
                        "Cycle detected on symbol=$childKey",
                    ),
                    children = emptyList(),
                )
            } else {
                buildCallNode(
                    target = edge.target,
                    parentCallSite = edge.callSite,
                    direction = direction,
                    depthRemaining = depthRemaining - 1,
                    pathKeys = pathKeys + nodeKey,
                    budget = budget,
                    currentDepth = currentDepth + 1,
                )
            }
            children += child

            if (index == edges.lastIndex) {
                // keep deterministic iteration even when no truncation happened
                continue
            }
        }

        if (truncation != null) {
            budget.truncatedNodes += 1
        }
        return CallNode(
            symbol = symbol,
            callSite = parentCallSite,
            truncation = truncation,
            children = children,
        )
    }

    private fun findCallEdges(
        target: PsiElement,
        direction: CallDirection,
    ): List<CallEdge> = when (direction) {
        CallDirection.INCOMING -> incomingCallEdges(target)
        CallDirection.OUTGOING -> outgoingCallEdges(target)
    }.sortedWith(
        compareBy<CallEdge>(
            { it.callSite.filePath },
            { it.callSite.startOffset },
            { it.callSite.endOffset },
            { it.symbol.fqName },
            { it.symbol.kind.name },
        ),
    )

    private fun incomingCallEdges(target: PsiElement): List<CallEdge> {
        val edges = mutableListOf<CallEdge>()
        session.allKtFiles().forEach { candidateFile ->
            candidateFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        element.references.forEach { reference ->
                            val resolved = reference.resolve()
                            if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                                val caller = reference.element.parentsWithSelf()
                                    .filterIsInstance<PsiNamedElement>()
                                    .firstOrNull { !it.name.isNullOrBlank() }
                                    ?: return@forEach
                                val callerSymbol = caller.toSymbolModel(containingDeclaration = null)
                                val callSite = reference.element.toKastLocation(
                                    com.intellij.openapi.util.TextRange(
                                        reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                        reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                                    ),
                                )
                                edges += CallEdge(target = caller, symbol = callerSymbol, callSite = callSite)
                            }
                        }
                        super.visitElement(element)
                    }
                },
            )
        }
        return edges
    }

    private fun outgoingCallEdges(target: PsiElement): List<CallEdge> {
        val declaration = target.parentsWithSelf()
            .filterIsInstance<KtNamedDeclaration>()
            .firstOrNull()
            ?: return emptyList()
        val edges = mutableListOf<CallEdge>()
        declaration.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    element.references.forEach { reference ->
                        val resolved = reference.resolve() ?: return@forEach
                        val symbol = resolved.toSymbolModel(containingDeclaration = null)
                        val callSite = reference.element.toKastLocation(
                            com.intellij.openapi.util.TextRange(
                                reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                            ),
                        )
                        edges += CallEdge(target = resolved, symbol = symbol, callSite = callSite)
                    }
                    super.visitElement(element)
                }
            },
        )
        return edges
    }

    private fun symbolIdentityKey(
        target: PsiElement,
        symbol: io.github.amichne.kast.api.Symbol,
    ): String = buildString {
        append(symbol.fqName)
        append('|')
        append(target.containingFile.virtualFile?.path ?: symbol.location.filePath)
        append(':')
        append(symbol.location.startOffset)
        append('-')
        append(symbol.location.endOffset)
    }

    private fun PsiElement.parentsWithSelf(): Sequence<PsiElement> = generateSequence(this) { it.parent }

    private fun persistCallHierarchy(
        query: CallHierarchyQuery,
        root: CallNode,
        stats: CallHierarchyStats,
    ): CallHierarchyPersistence? {
        val gitSha = resolveGitSha() ?: return null
        val cacheRoot = workspaceRoot.resolve(".kast").resolve("call-hierarchy").resolve(gitSha)
        Files.createDirectories(cacheRoot)
        val cacheKey = FileHashing.sha256(
            listOf(
                query.position.filePath,
                query.position.offset.toString(),
                query.direction.name,
                query.depth.toString(),
                query.maxTotalCalls.toString(),
                query.maxChildrenPerNode.toString(),
                query.timeoutMillis?.toString() ?: "null",
            ).joinToString("|"),
        )
        val cacheFile = cacheRoot.resolve("$cacheKey.json")
        val payload = CallHierarchyResult(root = root, stats = stats, schemaVersion = io.github.amichne.kast.api.SCHEMA_VERSION)
        Files.writeString(cacheFile, json.encodeToString(CallHierarchyResult.serializer(), payload))
        return CallHierarchyPersistence(
            gitSha = gitSha,
            cacheFilePath = cacheFile.toString(),
        )
    }

    private fun resolveGitSha(): String? = runCatching {
        val process = ProcessBuilder("git", "-C", workspaceRoot.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.matches(Regex("^[0-9a-fA-F]{40}$"))) output else null
    }.getOrNull()

    private fun unsupported(capability: ReadCapability) = io.github.amichne.kast.api.CapabilityNotSupportedException(
        capability = capability.name,
        message = "The standalone backend does not support $capability",
    )

    private data class CallEdge(
        val target: PsiElement,
        val symbol: io.github.amichne.kast.api.Symbol,
        val callSite: Location,
    )

    private class CallHierarchyBudget(
        val maxTotalCalls: Int,
        val maxChildrenPerNode: Int,
        timeoutMillis: Long,
    ) {
        private val startedAtNanos = System.nanoTime()
        private val timeoutNanos = timeoutMillis * 1_000_000
        var nodes: Int = 0
        var edges: Int = 0
        var truncatedNodes: Int = 0
        var maxDepthReached: Int = 0
        var timeoutHit: Boolean = false
        var maxTotalCallsHit: Boolean = false
        var maxChildrenHit: Boolean = false

        fun observeDepth(depth: Int) {
            if (depth > maxDepthReached) {
                maxDepthReached = depth
            }
        }

        fun timeoutReached(): Boolean {
            if (timeoutHit) {
                return true
            }
            val elapsed = System.nanoTime() - startedAtNanos
            if (elapsed >= timeoutNanos) {
                timeoutHit = true
            }
            return timeoutHit
        }

        fun toStats(): CallHierarchyStats = CallHierarchyStats(
            totalNodes = nodes,
            totalEdges = edges,
            truncatedNodes = truncatedNodes,
            maxDepthReached = maxDepthReached,
            timeoutReached = timeoutHit,
            maxTotalCallsReached = maxTotalCallsHit,
            maxChildrenPerNodeReached = maxChildrenHit,
        )
    }
}
