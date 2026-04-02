package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CallNode
import io.github.amichne.kast.api.CallNodeExpansion
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.LocalDiskEditApplier
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.PersistedCallHierarchySnapshot
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
import java.nio.charset.StandardCharsets
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
import org.jetbrains.kotlin.psi.KtReferenceExpression

@OptIn(KaExperimentalApi::class)
class StandaloneAnalysisBackend(
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val session: StandaloneAnalysisSession,
) : AnalysisBackend {
    private val readDispatcher = Dispatchers.IO.limitedParallelism(limits.maxConcurrentRequests)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

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
        val effectiveTimeoutMillis = query.timeoutMillis?.coerceAtMost(limits.requestTimeoutMillis)
            ?: limits.requestTimeoutMillis
        val timeoutDeadlineNanos = System.nanoTime() + effectiveTimeoutMillis * 1_000_000
        val file = session.findKtFile(query.position.filePath)
        val rootTarget = resolveTarget(file, query.position.offset)
        val stats = CallHierarchyStats()
        val rootNode = buildHierarchyNode(
            symbolElement = rootTarget,
            direction = query.direction,
            remainingDepth = query.depth,
            query = query,
            stats = stats,
            ancestorSymbols = setOf(rootTarget.symbolIdentity()),
            callSite = null,
            timeoutDeadlineNanos = timeoutDeadlineNanos,
        )
        val resultWithoutSnapshot = CallHierarchyResult(
            root = rootNode,
            totalNodes = stats.totalNodes,
            totalEdges = stats.totalEdges,
        )
        resultWithoutSnapshot.copy(
            persistedSnapshot = persistCallHierarchySnapshot(query, resultWithoutSnapshot),
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

    private fun buildHierarchyNode(
        symbolElement: PsiElement,
        direction: io.github.amichne.kast.api.CallDirection,
        remainingDepth: Int,
        query: CallHierarchyQuery,
        stats: CallHierarchyStats,
        ancestorSymbols: Set<String>,
        callSite: Location?,
        timeoutDeadlineNanos: Long,
    ): CallNode {
        stats.totalNodes += 1
        if (System.nanoTime() >= timeoutDeadlineNanos) {
            return CallNode(
                symbol = symbolElement.toSymbolModel(containingDeclaration = null),
                callSite = callSite,
                expansion = CallNodeExpansion.TIMEOUT_TRUNCATED,
                children = emptyList(),
            )
        }
        if (remainingDepth == 0) {
            return CallNode(
                symbol = symbolElement.toSymbolModel(containingDeclaration = null),
                callSite = callSite,
                expansion = CallNodeExpansion.MAX_DEPTH,
                children = emptyList(),
            )
        }

        val callRelations = when (direction) {
            io.github.amichne.kast.api.CallDirection.INCOMING -> findIncomingCalls(symbolElement)
            io.github.amichne.kast.api.CallDirection.OUTGOING -> findOutgoingCalls(symbolElement)
        }.sortedWith(
            compareBy<CallRelation>(
                { it.callSite.filePath },
                { it.callSite.startOffset },
                { it.callSite.endOffset },
                { it.callee.toSymbolModel(containingDeclaration = null).fqName },
                { it.callee.containingFile.virtualFile.path },
                { it.callee.textRange.startOffset },
            ),
        )

        var expansion = CallNodeExpansion.EXPANDED
        val children = mutableListOf<CallNode>()
        for (relation in callRelations) {
            if (children.size >= query.maxChildrenPerNode) {
                expansion = CallNodeExpansion.MAX_CHILDREN_TRUNCATED
                break
            }
            if (stats.totalEdges >= query.maxTotalCalls) {
                expansion = CallNodeExpansion.MAX_TOTAL_CALLS_TRUNCATED
                break
            }
            val nextSymbol = if (direction == io.github.amichne.kast.api.CallDirection.INCOMING) relation.caller else relation.callee
            val symbolIdentity = nextSymbol.symbolIdentity()
            val child = if (ancestorSymbols.contains(symbolIdentity)) {
                stats.totalNodes += 1
                CallNode(
                    symbol = nextSymbol.toSymbolModel(containingDeclaration = null),
                    callSite = relation.callSite,
                    expansion = CallNodeExpansion.CYCLE_TRUNCATED,
                    children = emptyList(),
                )
            } else {
                buildHierarchyNode(
                    symbolElement = nextSymbol,
                    direction = direction,
                    remainingDepth = remainingDepth - 1,
                    query = query,
                    stats = stats,
                    ancestorSymbols = ancestorSymbols + symbolIdentity,
                    callSite = relation.callSite,
                    timeoutDeadlineNanos = timeoutDeadlineNanos,
                )
            }
            stats.totalEdges += 1
            children += child
        }

        return CallNode(
            symbol = symbolElement.toSymbolModel(containingDeclaration = null),
            callSite = callSite,
            expansion = expansion,
            children = children,
        )
    }

    private fun findIncomingCalls(target: PsiElement): List<CallRelation> = session.allKtFiles()
        .flatMap { candidateFile ->
            val calls = mutableListOf<CallRelation>()
            candidateFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (element is KtReferenceExpression) {
                            element.references.forEach { reference ->
                                val resolved = reference.resolve()
                                if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                                    val caller = element.parentsWithSelf()
                                        .firstOrNull { parent -> parent is KtNamedDeclaration }
                                        ?: return@forEach
                                    calls += CallRelation(
                                        caller = caller,
                                        callee = target,
                                        callSite = reference.element.toKastLocation(
                                            com.intellij.openapi.util.TextRange(
                                                reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                                reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                        super.visitElement(element)
                    }
                },
            )
            calls
        }

    private fun findOutgoingCalls(caller: PsiElement): List<CallRelation> {
        val declaration = caller as? KtNamedDeclaration ?: return emptyList()
        val relations = mutableListOf<CallRelation>()
        declaration.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is KtReferenceExpression) {
                        element.references.forEach { reference ->
                            val resolved = reference.resolve() ?: return@forEach
                            val callee = resolved as? KtNamedDeclaration ?: return@forEach
                            relations += CallRelation(
                                caller = declaration,
                                callee = callee,
                                callSite = reference.element.toKastLocation(
                                    com.intellij.openapi.util.TextRange(
                                        reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                        reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                                    ),
                                ),
                            )
                        }
                    }
                    super.visitElement(element)
                }
            },
        )
        return relations
    }

    private fun persistCallHierarchySnapshot(
        query: CallHierarchyQuery,
        result: CallHierarchyResult,
    ): PersistedCallHierarchySnapshot? {
        if (!query.persistToWorkspace) {
            return null
        }
        val relativeDirectory = Path.of(".kast", "call-hierarchy")
        val gitSha = gitHeadSha()
        val identityPayload = json.encodeToString(CallHierarchyQuery.serializer(), query)
        val snapshotHash = FileHashing.sha256(identityPayload)
        val relativePath = relativeDirectory
            .resolve(gitSha ?: "no-git-sha")
            .resolve("$snapshotHash.json")
        val outputFile = workspaceRoot.resolve(relativePath)
        Files.createDirectories(outputFile.parent)
        Files.writeString(outputFile, json.encodeToString(CallHierarchyResult.serializer(), result), StandardCharsets.UTF_8)
        return PersistedCallHierarchySnapshot(
            gitSha = gitSha,
            relativePath = relativePath.toString(),
        )
    }

    private fun gitHeadSha(): String? = runCatching {
        val process = ProcessBuilder("git", "-C", workspaceRoot.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.isNotBlank()) output else null
    }.getOrNull()

    private fun PsiElement.parentsWithSelf(): Sequence<PsiElement> = generateSequence(this) { it.parent }
    private fun PsiElement.symbolIdentity(): String {
        val location = toKastLocation()
        val fqName = toSymbolModel(containingDeclaration = null).fqName
        return "$fqName@${location.filePath}:${location.startOffset}-${location.endOffset}"
    }

    private fun unsupported(capability: ReadCapability) = io.github.amichne.kast.api.CapabilityNotSupportedException(
        capability = capability.name,
        message = "The standalone backend does not support $capability",
    )
}

private data class CallRelation(
    val caller: PsiElement,
    val callee: PsiElement,
    val callSite: Location,
)

private data class CallHierarchyStats(
    var totalNodes: Int = 0,
    var totalEdges: Int = 0,
)
