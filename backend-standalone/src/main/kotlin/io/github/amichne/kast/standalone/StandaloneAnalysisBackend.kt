package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReference
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CallHierarchyTruncationReason
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
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        require(query.maxTotalCalls > 0) { "maxTotalCalls must be > 0" }
        require(query.maxChildrenPerNode > 0) { "maxChildrenPerNode must be > 0" }
        require(query.timeoutMillis > 0) { "timeoutMillis must be > 0" }

        val file = session.findKtFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        val targetSymbol = analyze(file) { target.toSymbolModel(containingDeclaration = null) }
        val gitCommitSha = workspaceRoot.findGitCommitSha()

        val state = CallHierarchyTraversalState(
            maxTotalCalls = query.maxTotalCalls,
            maxChildrenPerNode = query.maxChildrenPerNode,
            deadlineNanos = System.nanoTime() + query.timeoutMillis.toDuration(DurationUnit.MILLISECONDS).inWholeNanoseconds,
        )
        val root = buildCallHierarchyNode(
            target = target,
            remainingDepth = query.depth,
            direction = query.direction,
            ancestry = setOf(target.symbolKey()),
            state = state,
            fallbackSymbol = targetSymbol,
        )

        CallHierarchyResult(
            root = root,
            totalCalls = state.totalCalls,
            truncated = state.truncationReasons.isNotEmpty(),
            truncationReasons = state.truncationReasons.toSet(),
            gitCommitSha = gitCommitSha,
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

    private fun buildCallHierarchyNode(
        target: PsiElement,
        remainingDepth: Int,
        direction: io.github.amichne.kast.api.CallDirection,
        ancestry: Set<String>,
        state: CallHierarchyTraversalState,
        fallbackSymbol: io.github.amichne.kast.api.Symbol? = null,
    ): io.github.amichne.kast.api.CallNode {
        val symbol = runCatching {
            analyze(target.containingFile as KtFile) { target.toSymbolModel(containingDeclaration = null) }
        }.getOrElse { fallbackSymbol ?: target.toSymbolModel(containingDeclaration = null) }

        if (remainingDepth == 0) {
            val hasMore = when (direction) {
                io.github.amichne.kast.api.CallDirection.OUTGOING -> outgoingCalls(target).isNotEmpty()
                io.github.amichne.kast.api.CallDirection.INCOMING -> incomingCalls(target).isNotEmpty()
            }
            if (hasMore) {
                state.truncate(CallHierarchyTruncationReason.DEPTH_LIMIT)
            }
            return io.github.amichne.kast.api.CallNode(symbol = symbol, children = emptyList())
        }

        val callSites = when (direction) {
            io.github.amichne.kast.api.CallDirection.OUTGOING -> outgoingCalls(target)
            io.github.amichne.kast.api.CallDirection.INCOMING -> incomingCalls(target)
        }.sortedWith(
            compareBy<CallSite>(
                { it.location.filePath },
                { it.location.startOffset },
                { it.location.endOffset },
                { it.symbol.fqName },
            ),
        )

        val children = mutableListOf<io.github.amichne.kast.api.CallNode>()
        for (callSite in callSites) {
            if (state.isTimedOut()) {
                state.truncate(CallHierarchyTruncationReason.TIMEOUT)
                break
            }
            if (children.size >= state.maxChildrenPerNode) {
                state.truncate(CallHierarchyTruncationReason.MAX_CHILDREN_PER_NODE)
                break
            }
            if (state.totalCalls >= state.maxTotalCalls) {
                state.truncate(CallHierarchyTruncationReason.MAX_TOTAL_CALLS)
                break
            }

            state.totalCalls += 1
            val nextKey = callSite.declaration.symbolKey()
            val child = if (nextKey in ancestry) {
                state.truncate(CallHierarchyTruncationReason.CYCLE)
                io.github.amichne.kast.api.CallNode(
                    symbol = callSite.symbol,
                    children = emptyList(),
                )
            } else {
                buildCallHierarchyNode(
                    target = callSite.declaration,
                    remainingDepth = remainingDepth - 1,
                    direction = direction,
                    ancestry = ancestry + nextKey,
                    state = state,
                    fallbackSymbol = callSite.symbol,
                )
            }
            children += child
        }

        return io.github.amichne.kast.api.CallNode(
            symbol = symbol,
            children = children,
        )
    }

    private fun outgoingCalls(target: PsiElement): List<CallSite> {
        val calls = mutableListOf<CallSite>()
        target.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    element.references.forEach { reference ->
                        val declaration = reference.resolve() ?: return@forEach
                        val callable = declaration.enclosingCallableDeclaration() ?: declaration
                        val symbol = analyze(callable.containingFile as KtFile) {
                            callable.toSymbolModel(containingDeclaration = null)
                        }
                        calls += CallSite(
                            declaration = callable,
                            symbol = symbol,
                            location = reference.toKastLocation(),
                        )
                    }
                    super.visitElement(element)
                }
            },
        )
        return calls
    }

    private fun incomingCalls(target: PsiElement): List<CallSite> {
        val calls = mutableListOf<CallSite>()
        session.allKtFiles().forEach { candidateFile ->
            candidateFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        element.references.forEach { reference ->
                            val resolved = reference.resolve()
                            if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                                val caller = reference.element.enclosingCallableDeclaration() ?: return@forEach
                                val symbol = analyze(caller.containingFile as KtFile) {
                                    caller.toSymbolModel(containingDeclaration = null)
                                }
                                calls += CallSite(
                                    declaration = caller,
                                    symbol = symbol,
                                    location = reference.toKastLocation(),
                                )
                            }
                        }
                        super.visitElement(element)
                    }
                },
            )
        }
        return calls
    }

    private fun PsiReference.toKastLocation(): io.github.amichne.kast.api.Location = element.toKastLocation(
        com.intellij.openapi.util.TextRange(
            element.textRange.startOffset + rangeInElement.startOffset,
            element.textRange.startOffset + rangeInElement.endOffset,
        ),
    )

    private fun PsiElement.enclosingCallableDeclaration(): PsiElement? = generateSequence(this) { it.parent }
        .firstOrNull { candidate ->
            candidate is org.jetbrains.kotlin.psi.KtNamedFunction ||
                candidate is org.jetbrains.kotlin.psi.KtSecondaryConstructor ||
                (candidate is KtNamedDeclaration && candidate.nameIdentifier != null) ||
                candidate is com.intellij.psi.PsiMethod
        }

    private fun PsiElement.symbolKey(): String {
        val location = toKastLocation()
        return "${location.filePath}:${location.startOffset}:${location.endOffset}:${javaClass.name}"
    }

    private fun Path.findGitCommitSha(): String? {
        var current: Path? = toAbsolutePath().normalize()
        while (current != null) {
            val gitDir = current.resolve(".git")
            val headPath = gitDir.resolve("HEAD")
            if (java.nio.file.Files.isRegularFile(headPath)) {
                val head = java.nio.file.Files.readString(headPath).trim()
                if (head.startsWith("ref: ")) {
                    val refPath = gitDir.resolve(head.removePrefix("ref: ").trim())
                    if (java.nio.file.Files.isRegularFile(refPath)) {
                        return java.nio.file.Files.readString(refPath).trim().takeIf { it.isNotBlank() }
                    }
                }
                return head.takeIf { it.isNotBlank() }
            }
            current = current.parent
        }
        return null
    }

    private data class CallSite(
        val declaration: PsiElement,
        val symbol: io.github.amichne.kast.api.Symbol,
        val location: io.github.amichne.kast.api.Location,
    )

    private class CallHierarchyTraversalState(
        val maxTotalCalls: Int,
        val maxChildrenPerNode: Int,
        val deadlineNanos: Long,
    ) {
        var totalCalls: Int = 0
        val truncationReasons: MutableSet<CallHierarchyTruncationReason> = linkedSetOf()

        fun truncate(reason: CallHierarchyTruncationReason) {
            truncationReasons += reason
        }

        fun isTimedOut(): Boolean = System.nanoTime() >= deadlineNanos
    }
}
