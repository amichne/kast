package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallNode
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.LocalDiskEditApplier
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
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.psi.KtFile

@OptIn(KaExperimentalApi::class)
class StandaloneAnalysisBackend(
    private val workspaceRoot: Path,
    private val limits: ServerLimits,
    private val session: StandaloneAnalysisSession,
) : AnalysisBackend {
    private val readDispatcher = Dispatchers.IO.limitedParallelism(limits.maxConcurrentRequests)

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
        require(query.depth >= 0) { "depth must be >= 0" }
        require(query.maxTotalCalls >= 0) { "maxTotalCalls must be >= 0" }
        require(query.maxChildrenPerNode >= 1) { "maxChildrenPerNode must be >= 1" }
        val requestedTimeoutMillis = query.timeoutMillis
        require(requestedTimeoutMillis == null || requestedTimeoutMillis > 0) { "timeoutMillis must be > 0 when provided" }
        val timeoutMillis = requestedTimeoutMillis ?: limits.requestTimeoutMillis

        val file = session.findKtFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        val builder = CallHierarchyBuilder(
            query = query,
            callBudget = query.maxTotalCalls,
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
        )

        CallHierarchyResult(
            root = builder.buildNode(target, remainingDepth = query.depth, ancestry = emptySet()),
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

    private inner class CallHierarchyBuilder(
        private val query: CallHierarchyQuery,
        private var callBudget: Int,
        private val deadlineNanos: Long,
    ) {
        fun buildNode(target: PsiElement, remainingDepth: Int, ancestry: Set<String>): CallNode {
            val symbol = target.toSymbol(target.containingDeclarationName())
            val key = symbol.symbolKey()
            if (remainingDepth == 0 || callBudget == 0 || System.nanoTime() >= deadlineNanos) {
                return CallNode(symbol = symbol, children = emptyList())
            }

            val nextAncestry = ancestry + key
            val children = mutableListOf<CallNode>()
            val edges = callEdges(target)
                .asSequence()
                .filter { edge -> query.includeExternalSymbols || edge.symbol.location.filePath.startsWith(workspaceRoot.toString()) }
                .take(query.maxChildrenPerNode)
                .toList()

            for (edge in edges) {
                if (callBudget == 0 || System.nanoTime() >= deadlineNanos) {
                    break
                }

                callBudget -= 1
                val childKey = edge.symbol.symbolKey()
                children += if (childKey in nextAncestry) {
                    CallNode(symbol = edge.symbol, children = emptyList())
                } else {
                    buildNode(edge.element, remainingDepth - 1, nextAncestry)
                }
            }

            return CallNode(symbol = symbol, children = children)
        }

        private fun callEdges(target: PsiElement): List<CallEdge> = when (query.direction) {
            CallDirection.INCOMING -> incomingEdges(target)
            CallDirection.OUTGOING -> outgoingEdges(target)
        }.sortedWith(
            compareBy<CallEdge>({ it.callSite.filePath }, { it.callSite.startOffset }, { it.callSite.endOffset }, { it.symbol.fqName }),
        )

        private fun incomingEdges(target: PsiElement): List<CallEdge> = session.allKtFiles()
            .flatMap { candidateFile -> candidateFile.findCallSitesTo(target) }

        private fun outgoingEdges(target: PsiElement): List<CallEdge> {
            val edges = mutableListOf<CallEdge>()

            target.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        element.references.forEach { reference ->
                            val resolved = reference.resolve() ?: return@forEach
                            if (resolved == target || resolved.isEquivalentTo(target)) {
                                return@forEach
                            }

                            val referenceRange = com.intellij.openapi.util.TextRange(
                                reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                            )
                            edges += CallEdge(
                                element = resolved,
                                symbol = resolved.toSymbol(resolved.containingDeclarationName()),
                                callSite = reference.element.toKastLocation(referenceRange),
                            )
                        }
                        super.visitElement(element)
                    }
                },
            )

            return edges
        }
    }

    private data class CallEdge(
        val element: PsiElement,
        val symbol: io.github.amichne.kast.api.Symbol,
        val callSite: io.github.amichne.kast.api.Location,
    )

    private fun KtFile.findCallSitesTo(target: PsiElement): List<CallEdge> {
        val edges = mutableListOf<CallEdge>()
        accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    element.references.forEach { reference ->
                        val resolved = reference.resolve()
                        if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                            val caller = reference.element.closestNamedCaller() ?: return@forEach
                            val referenceRange = com.intellij.openapi.util.TextRange(
                                reference.element.textRange.startOffset + reference.rangeInElement.startOffset,
                                reference.element.textRange.startOffset + reference.rangeInElement.endOffset,
                            )
                            edges += CallEdge(
                                element = caller,
                                symbol = caller.toSymbol(caller.containingDeclarationName()),
                                callSite = reference.element.toKastLocation(referenceRange),
                            )
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        return edges
    }

    private fun PsiElement.closestNamedCaller(): PsiElement? =
        generateSequence(this) { element -> element.parent }
            .firstOrNull { element -> element is PsiNamedElement && !element.name.isNullOrBlank() }

    private fun PsiElement.containingDeclarationName(): String? =
        generateSequence(parent) { element -> element.parent }
            .filterIsInstance<PsiNamedElement>()
            .firstOrNull { named -> !named.name.isNullOrBlank() }
            ?.name

    private fun PsiElement.toSymbol(containingDeclaration: String?) =
        toSymbolModel(containingDeclaration = containingDeclaration)

    private fun io.github.amichne.kast.api.Symbol.symbolKey(): String =
        "${fqName}|${location.filePath}:${location.startOffset}-${location.endOffset}"

    private fun unsupported(capability: ReadCapability) = io.github.amichne.kast.api.CapabilityNotSupportedException(
        capability = capability.name,
        message = "The standalone backend does not support $capability",
    )
}
