package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.usageView.UsageInfo
import io.github.amichne.kast.api.AnalysisBackend
import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.ApplyEditsResult
import io.github.amichne.kast.api.BackendCapabilities
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyResult
import io.github.amichne.kast.api.ConflictException
import io.github.amichne.kast.api.Diagnostic
import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.DiagnosticsResult
import io.github.amichne.kast.api.EditPlanValidator
import io.github.amichne.kast.api.FileHash
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.HealthResponse
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.MutationCapability
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.PartialApplyException
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.ReferencesResult
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.RenameResult
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolResult
import io.github.amichne.kast.api.TextEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.CancellablePromise
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import java.util.concurrent.Callable
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(KaExperimentalApi::class)
class IntelliJAnalysisBackend(
    private val project: Project,
    private val limits: ServerLimits,
) : AnalysisBackend {
    private val readExecutor = Dispatchers.IO.limitedParallelism(limits.maxConcurrentRequests).asExecutor()
    private val writeMutex = Mutex()

    override suspend fun capabilities(): BackendCapabilities = BackendCapabilities(
        backendName = "intellij",
        backendVersion = "0.1.0",
        workspaceRoot = project.basePath.orEmpty(),
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

    override suspend fun resolveSymbol(query: SymbolQuery): SymbolResult = readInSmartMode {
        val file = requirePsiFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        SymbolResult(target.toSymbol(file))
    }

    override suspend fun findReferences(query: ReferencesQuery): ReferencesResult = readInSmartMode {
        val file = requirePsiFile(query.position.filePath)
        val target = resolveTarget(file, query.position.offset)
        val references = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project))
            .findAll()
            .map { reference ->
                val range = reference.element.textRange.shiftRight(reference.rangeInElement.startOffset)
                reference.element.toLocation(range)
            }
            .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

        ReferencesResult(
            declaration = if (query.includeDeclaration) target.toSymbol(file) else null,
            references = references,
        )
    }

    override suspend fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult {
        return super.callHierarchy(query)
    }

    override suspend fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult = readInSmartMode {
        val diagnostics = query.filePaths.sorted().flatMap { filePath ->
            val file = requirePsiFile(filePath)
            when (file) {
                is KtFile -> analyze(file) {
                    file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                        .flatMap { diagnostic -> diagnostic.toApiDiagnostics() }
                }.ifEmpty { file.psiParseErrors() }
                else -> file.psiParseErrors()
            }
        }.sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset }, { it.code ?: "" }))

        DiagnosticsResult(diagnostics = diagnostics)
    }

    override suspend fun rename(query: RenameQuery): RenameResult {
        val target = readInSmartMode {
            val file = requirePsiFile(query.position.filePath)
            SmartPointerManager.getInstance(project)
                .createSmartPsiElementPointer(resolveTarget(file, query.position.offset))
                .element
                ?: throw NotFoundException("No resolvable symbol was found at the requested offset")
        }
        val processor = readInSmartMode {
            RenameProcessor(project, target, query.newName, false, false).apply {
                setSearchInComments(false)
                setSearchTextOccurrences(false)
            }
        }

        return withContext(Dispatchers.IO.limitedParallelism(1)) {
            val preparedRenames = LinkedHashMap<PsiElement, String>()
            invokeOnEdt {
                ReadAction.run<RuntimeException> {
                    processor.prepareRenaming(target, query.newName, preparedRenames)
                    preparedRenames.forEach { (element, newName) ->
                        if (element != target) {
                            processor.addElement(element, newName)
                        }
                    }
                }
            }
            val usages = readInSmartMode {
                processor.findUsages().filterNot { it.isNonCodeUsage }
            }

            readInSmartMode {
                val renamedElements = linkedSetOf(target).apply {
                    addAll(preparedRenames.keys)
                }
                val classifiedUsages = RenameProcessor.classifyUsages(
                    renamedElements,
                    usages,
                )
                val declarationEdits = renamedElements.map { element ->
                    element.declarationEdit(processor.getNewName(element))
                }
                val usageEdits = renamedElements.flatMap { element ->
                    classifiedUsages[element].orEmpty().mapNotNull { usage ->
                        usage.toTextEdit(processor.getNewName(element))
                    }
                }

                val edits = (declarationEdits + usageEdits)
                    .distinctBy { Triple(it.filePath, it.startOffset, it.endOffset) }
                    .sortedWith(compareBy({ it.filePath }, { it.startOffset }))
                val fileHashes = currentFileHashes(edits.map(TextEdit::filePath))
                RenameResult(
                    edits = edits,
                    fileHashes = fileHashes,
                    affectedFiles = fileHashes.map(FileHash::filePath),
                )
            }
        }
    }

    override suspend fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult = writeMutex.withLock {
        val validated = EditPlanValidator.validate(query.edits, query.fileHashes)
        val snapshots = readInSmartMode {
            validated.associateWith { plan ->
                currentText(plan.filePath)
            }
        }

        snapshots.forEach { (plan, content) ->
            val actualHash = FileHashing.sha256(content)
            if (actualHash != plan.expectedHash) {
                throw ConflictException(
                    message = "The file changed after the edit plan was created",
                    details = mapOf(
                        "filePath" to plan.filePath,
                        "expectedHash" to plan.expectedHash,
                        "actualHash" to actualHash,
                    ),
                )
            }
        }

        val appliedFiles = mutableListOf<String>()
        val appliedEdits = mutableListOf<TextEdit>()

        validated.forEach { plan ->
            try {
                applyFileEdits(plan.filePath, plan.edits)
                appliedFiles += plan.filePath
                appliedEdits += plan.edits.sortedBy { it.startOffset }
            } catch (exception: Exception) {
                throw PartialApplyException(
                    details = mapOf(
                        "failedFile" to plan.filePath,
                        "appliedFiles" to appliedFiles.joinToString(","),
                        "reason" to (exception.message ?: exception::class.java.simpleName),
                    ),
                )
            }
        }

        ApplyEditsResult(
            applied = appliedEdits,
            affectedFiles = appliedFiles.sorted(),
        )
    }

    private suspend fun <T> readInSmartMode(action: () -> T): T = awaitPromise(
        ReadAction.nonBlocking(Callable { action() })
            .inSmartMode(project)
            .withDocumentsCommitted(project)
            .expireWith(project)
            .submit(readExecutor),
    )

    private fun requirePsiFile(filePath: String): PsiFile {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: throw NotFoundException(
                message = "The requested file does not exist in the IntelliJ project",
                details = mapOf("filePath" to filePath),
            )
        return PsiManager.getInstance(project).findFile(virtualFile)
            ?: throw NotFoundException(
                message = "The requested file could not be resolved to a PSI file",
                details = mapOf("filePath" to filePath),
            )
    }

    private fun resolveTarget(
        file: PsiFile,
        offset: Int,
    ): PsiElement {
        val leaf = file.findElementAt(offset)
            ?: throw NotFoundException(
                message = "No PSI element was found at the requested offset",
                details = mapOf("offset" to offset.toString()),
            )

        generateSequence(leaf as PsiElement?) { element -> element.parent }.forEach { element ->
            element.references.firstNotNullOfOrNull { it.resolve() }?.let { resolved ->
                return resolved
            }

            if (element is PsiNamedElement && !element.name.isNullOrBlank()) {
                return element
            }
        }

        throw NotFoundException("No resolvable symbol was found at the requested offset")
    }

    private fun currentFileHashes(filePaths: Collection<String>): List<FileHash> = filePaths
        .distinct()
        .sorted()
        .map { filePath ->
            FileHash(
                filePath = filePath,
                hash = FileHashing.sha256(currentText(filePath)),
            )
        }

    private fun currentText(filePath: String): String {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: throw NotFoundException("The requested file does not exist", mapOf("filePath" to filePath))
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        return document?.text ?: Path.of(filePath).readText()
    }

    private suspend fun applyFileEdits(
        filePath: String,
        edits: List<TextEdit>,
    ) {
        withContext(Dispatchers.IO.limitedParallelism(1)) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: throw NotFoundException("The requested file does not exist", mapOf("filePath" to filePath))
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: throw NotFoundException("The requested file does not have a document", mapOf("filePath" to filePath))
            WriteCommandAction.runWriteCommandAction(project) {
                edits.sortedByDescending { it.startOffset }.forEach { edit ->
                    document.replaceString(edit.startOffset, edit.endOffset, edit.newText)
                }
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            }
        }
    }

    private fun PsiElement.declarationEdit(newName: String): TextEdit {
        val range = when (this) {
            is KtNamedDeclaration -> nameIdentifier?.textRange ?: textRange
            is PsiNameIdentifierOwner -> nameIdentifier?.textRange ?: textRange
            else -> textRange
        }

        return TextEdit(
            filePath = containingFile.virtualFile.path,
            startOffset = range.startOffset,
            endOffset = range.endOffset,
            newText = newName,
        )
    }

    private fun UsageInfo.toTextEdit(newName: String): TextEdit? {
        if (isNonCodeUsage) {
            return null
        }

        val usageElement = element ?: reference?.element ?: return null
        val absoluteRange = when {
            reference != null -> {
                val usageReference = reference ?: return null
                usageElement.textRange.shiftRight(usageReference.rangeInElement.startOffset).let { range ->
                    TextRange(range.startOffset, range.startOffset + usageReference.rangeInElement.length)
                }
            }
            rangeInElement != null -> {
                val usageRange = rangeInElement ?: return null
                usageElement.textRange.shiftRight(usageRange.startOffset).let { range ->
                    TextRange(range.startOffset, range.startOffset + usageRange.length)
                }
            }
            segment != null -> {
                val usageSegment = segment ?: return null
                TextRange(usageSegment.startOffset, usageSegment.endOffset)
            }
            else -> usageElement.textRange
        }

        return TextEdit(
            filePath = usageElement.containingFile.virtualFile.path,
            startOffset = absoluteRange.startOffset,
            endOffset = absoluteRange.endOffset,
            newText = newName,
        )
    }

    private fun PsiElement.toSymbol(file: PsiFile): Symbol {
        return Symbol(
            fqName = fqName(),
            kind = kind(),
            location = toLocation(nameRange()),
            type = typeDescription(),
            containingDeclaration = file.packageNameOrNull(),
        )
    }

    private fun PsiElement.nameRange(): TextRange = when (this) {
        is KtNamedDeclaration -> nameIdentifier?.textRange ?: textRange
        is PsiNameIdentifierOwner -> nameIdentifier?.textRange ?: textRange
        else -> textRange
    }

    private fun PsiElement.toLocation(range: TextRange): Location {
        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
            ?: throw NotFoundException("Unable to create a document for the PSI file")
        val safeStartOffset = range.startOffset.coerceIn(0, document.textLength)
        val safeEndOffset = range.endOffset.coerceIn(safeStartOffset, document.textLength)
        val safeRange = TextRange(safeStartOffset, safeEndOffset)
        val lineIndex = document.getLineNumber(safeRange.startOffset)
        val previewStart = document.getLineStartOffset(lineIndex)
        val previewEnd = document.getLineEndOffset(lineIndex)

        return Location(
            filePath = containingFile.virtualFile.path,
            startOffset = safeRange.startOffset,
            endOffset = safeRange.endOffset,
            startLine = lineIndex + 1,
            startColumn = safeRange.startOffset - previewStart + 1,
            preview = document.getText(TextRange(previewStart, previewEnd)).trimEnd(),
        )
    }

    private fun PsiElement.fqName(): String = when (this) {
        is KtNamedDeclaration -> fqName?.asString() ?: name ?: "<anonymous>"
        is PsiClass -> qualifiedName ?: name ?: "<anonymous>"
        is PsiMethod -> "${containingClass?.qualifiedName ?: "<local>"}#${name}"
        is PsiField -> "${containingClass?.qualifiedName ?: "<local>"}.${name}"
        is PsiNamedElement -> name ?: "<anonymous>"
        else -> text
    }

    private fun PsiElement.kind(): SymbolKind = when (this) {
        is KtClass -> SymbolKind.CLASS
        is KtObjectDeclaration -> SymbolKind.OBJECT
        is KtNamedFunction -> SymbolKind.FUNCTION
        is KtProperty -> SymbolKind.PROPERTY
        is KtParameter -> SymbolKind.PARAMETER
        is PsiClass -> if (isInterface) SymbolKind.INTERFACE else SymbolKind.CLASS
        is PsiMethod -> SymbolKind.FUNCTION
        is PsiField -> SymbolKind.PROPERTY
        else -> SymbolKind.UNKNOWN
    }

    private fun PsiElement.typeDescription(): String? = when (this) {
        is KtNamedFunction -> typeReference?.text
        is KtProperty -> typeReference?.text
        is PsiMethod -> returnType?.presentableText
        is PsiField -> type.presentableText
        else -> null
    }

    private fun PsiFile.packageNameOrNull(): String? = when (this) {
        is org.jetbrains.kotlin.psi.KtFile -> packageFqName.asString().ifBlank { null }
        else -> null
    }

    private fun PsiFile.psiParseErrors(): List<Diagnostic> = PsiTreeUtil.collectElementsOfType(this, PsiErrorElement::class.java)
        .map { error ->
            Diagnostic(
                location = error.toLocation(error.textRange),
                severity = DiagnosticSeverity.ERROR,
                message = error.errorDescription,
                code = "PSI_PARSE_ERROR",
            )
        }

    private fun KaDiagnosticWithPsi<*>.toApiDiagnostics(): List<Diagnostic> {
        val ranges = textRanges.ifEmpty { listOf(TextRange(0, psi.textLength)) }
        return ranges.map { range ->
            Diagnostic(
                location = psi.toLocation(absoluteRange(range)),
                severity = severity.toApiSeverity(),
                message = defaultMessage,
                code = factoryName,
            )
        }
    }

    private fun KaDiagnosticWithPsi<*>.absoluteRange(relativeRange: TextRange): TextRange {
        val fileLength = psi.containingFile.textLength
        return when {
            relativeRange.endOffset <= psi.textLength -> {
                val elementStartOffset = psi.textRange.startOffset
                TextRange(
                    elementStartOffset + relativeRange.startOffset,
                    elementStartOffset + relativeRange.endOffset,
                )
            }
            relativeRange.endOffset <= fileLength -> relativeRange
            else -> TextRange(
                relativeRange.startOffset.coerceIn(0, fileLength),
                relativeRange.endOffset.coerceIn(relativeRange.startOffset.coerceIn(0, fileLength), fileLength),
            )
        }
    }

    private fun KaSeverity.toApiSeverity(): DiagnosticSeverity = when (this) {
        KaSeverity.ERROR -> DiagnosticSeverity.ERROR
        KaSeverity.WARNING -> DiagnosticSeverity.WARNING
        KaSeverity.INFO -> DiagnosticSeverity.INFO
    }

    private suspend fun <T> awaitPromise(promise: CancellablePromise<T>): T = suspendCancellableCoroutine { continuation ->
        promise.onSuccess { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
        promise.onError { throwable ->
            if (!continuation.isActive) {
                return@onError
            }
            if (throwable is ProcessCanceledException) {
                continuation.cancel(throwable)
            } else {
                continuation.resumeWithException(throwable)
            }
        }
        continuation.invokeOnCancellation {
            promise.cancel()
        }
    }

    private suspend fun invokeOnEdt(action: () -> Unit) = suspendCancellableCoroutine { continuation ->
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (!continuation.isActive) {
                return@invokeLater
            }

            runCatching(action).onSuccess {
                continuation.resume(Unit)
            }.onFailure { throwable ->
                continuation.resumeWithException(throwable)
            }
        }
    }
}
