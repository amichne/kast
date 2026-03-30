package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
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
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TextEdit
import io.github.amichne.kast.common.declarationEdit
import io.github.amichne.kast.common.resolveTarget
import io.github.amichne.kast.common.toApiDiagnostics
import io.github.amichne.kast.common.toKastLocation
import io.github.amichne.kast.common.toSymbolModel
import java.nio.file.Path
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
            ReadCapability.DIAGNOSTICS,
        ),
        mutationCapabilities = setOf(
            MutationCapability.RENAME,
            MutationCapability.APPLY_EDITS,
        ),
        limits = limits,
    )

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

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult {
        throw unsupported(ReadCapability.CALL_HIERARCHY)
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

    private fun unsupported(capability: ReadCapability) = io.github.amichne.kast.api.CapabilityNotSupportedException(
        capability = capability.name,
        message = "The standalone backend does not support $capability",
    )
}
