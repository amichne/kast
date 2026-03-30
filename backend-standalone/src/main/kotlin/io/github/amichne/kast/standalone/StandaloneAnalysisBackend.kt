package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.openapi.util.TextRange
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.CapabilityNotSupportedException
import io.github.amichne.kast.api.Diagnostic
import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.LocalDiskEditApplier
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TextEdit
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
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
        SymbolResult(analyze(file) { target.toSymbol() })
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult = withContext(readDispatcher) {
        val file = session.findKtFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        val references = session.allKtFiles()
            .flatMap { candidateFile -> candidateFile.findReferenceLocations(target) }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        ReferencesResult(
            declaration = if (query.includeDeclaration) analyze(file) { target.toSymbol() } else null,
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

    private fun resolveTarget(
        file: KtFile,
        offset: Int,
    ): PsiElement {
        val leaf = file.findElementAt(offset)
            ?: throw NotFoundException(
                message = "No PSI element was found at the requested offset",
                details = mapOf("offset" to offset.toString()),
            )

        generateSequence(leaf as PsiElement?) { element -> element.parent }.forEach { element ->
            element.references.firstNotNullOfOrNull { reference -> reference.resolve() }?.let { resolved ->
                return resolved
            }

            if (element is PsiNamedElement && !element.name.isNullOrBlank()) {
                return element
            }
        }

        throw NotFoundException("No resolvable symbol was found at the requested offset")
    }

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
                            references += reference.toKastLocation()
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
                            edits += reference.toTextEdit(newName)
                        }
                    }
                    super.visitElement(element)
                }
            },
        )

        return edits
    }

    private fun currentFileHashes(filePaths: Collection<String>): List<FileHash> = LocalDiskEditApplier.currentHashes(filePaths)

    private fun KaDiagnosticWithPsi<*>.toApiDiagnostics(): List<Diagnostic> {
        val ranges = textRanges.ifEmpty { listOf(TextRange(0, psi.textLength)) }
        return ranges.map { range ->
            Diagnostic(
                location = psi.toKastLocation(absoluteRange(range)),
                severity = severity.toApiSeverity(),
                message = defaultMessage,
                code = factoryName,
            )
        }
    }

    private fun KaDiagnosticWithPsi<*>.absoluteRange(relativeRange: TextRange): TextRange {
        val elementStartOffset = psi.textRange.startOffset
        return TextRange(
            elementStartOffset + relativeRange.startOffset,
            elementStartOffset + relativeRange.endOffset,
        )
    }

    private fun KaSeverity.toApiSeverity(): DiagnosticSeverity = when (this) {
        KaSeverity.ERROR -> DiagnosticSeverity.ERROR
        KaSeverity.WARNING -> DiagnosticSeverity.WARNING
        KaSeverity.INFO -> DiagnosticSeverity.INFO
    }

    private fun unsupported(capability: ReadCapability): CapabilityNotSupportedException {
        return CapabilityNotSupportedException(
            capability = capability.name,
            message = "The standalone backend is scaffolded, but $capability is not implemented yet",
        )
    }

    private fun unsupported(capability: MutationCapability): CapabilityNotSupportedException {
        return CapabilityNotSupportedException(
            capability = capability.name,
            message = "The standalone backend is scaffolded, but $capability is not implemented yet",
        )
    }
}
