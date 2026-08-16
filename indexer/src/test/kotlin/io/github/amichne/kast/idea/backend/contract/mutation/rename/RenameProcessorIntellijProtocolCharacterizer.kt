package io.github.amichne.kast.idea.backend.contract.mutation.rename

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.usageView.UsageInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class RenameProcessorStrategy {
    TARGET_ONLY,
    EXPLICIT_RELATED_ELEMENTS,
}

internal enum class RenameProcessorProtocolPhase {
    TARGET_SELECTED,
    PRE_RUN_REFERENCE_SEARCH,
    DURING_RUN_USAGE_SEARCH,
    PROCESSOR_COMMAND_COMPLETED,
}

internal enum class RenameProcessorProtocolLimitation {
    CANCELLED,
    CONFLICT,
    SILENT_ABORT,
    UNDECLARED_RELATED_RENAME,
    DECLARED_RELATED_RENAME_MISSING,
}

@JvmInline
internal value class RenameProcessorCommandDuration internal constructor(
    val nanoseconds: Long,
)

@JvmInline
internal value class RenameProcessorUsageCount internal constructor(
    val value: Int,
)

internal data class RenameProcessorProtocolEvidence(
    val strategy: RenameProcessorStrategy,
    val targetPath: String,
    val targetNameBefore: String,
    val targetNameAfter: String,
    val preRunReferencePaths: Set<String>,
    val affectedFilePaths: Set<String>,
    val phases: Set<RenameProcessorProtocolPhase>,
    val usageCount: RenameProcessorUsageCount,
    val commandDuration: RenameProcessorCommandDuration,
)

internal sealed interface RenameProcessorCharacterizationResult {
    data class Supported(
        val evidence: RenameProcessorProtocolEvidence,
    ) : RenameProcessorCharacterizationResult

    data class Unsupported(
        val strategy: RenameProcessorStrategy,
        val limitation: RenameProcessorProtocolLimitation,
        val phases: Set<RenameProcessorProtocolPhase>,
        val affectedFilePaths: Set<String>,
    ) : RenameProcessorCharacterizationResult
}

internal data class DeclaredRelatedRename(
    val element: PsiNamedElement,
    val newName: String,
)

internal class RenameProcessorIntellijProtocolCharacterizer(
    private val project: Project,
    private val beforeProcessorRun: suspend () -> Unit,
    private val cancellationProbe: () -> Unit = ProgressManager::checkCanceled,
    private val duringRunSearch: () -> Unit = ProgressManager::checkCanceled,
    private val processorRunner: (RenameProcessor) -> Unit = RenameProcessor::run,
) {
    suspend fun characterize(
        target: PsiNamedElement,
        newName: String,
        strategy: RenameProcessorStrategy = RenameProcessorStrategy.TARGET_ONLY,
        declaredRelatedRenames: List<DeclaredRelatedRename> = emptyList(),
        protectedUnrelatedDeclarations: List<PsiNamedElement> = emptyList(),
    ): RenameProcessorCharacterizationResult {
        val targetNameBefore = requireNotNull(readAction { target.name })
        val targetPath = readAction { target.containingFile.virtualFile.path }
        val protectedNamesBefore = readAction {
            protectedUnrelatedDeclarations.associateWith { element ->
                requireNotNull(element.name)
            }
        }
        val phases = linkedSetOf(RenameProcessorProtocolPhase.TARGET_SELECTED)
        val preRunReferencePaths = readAction {
            ReferencesSearch.search(target).findAll()
                .mapTo(linkedSetOf()) { reference ->
                    reference.element.containingFile.virtualFile.path
                }
        }
        phases += RenameProcessorProtocolPhase.PRE_RUN_REFERENCE_SEARCH
        val affectedFilePaths = linkedSetOf<String>()
        beforeProcessorRun()
        val listenerLifetime = Disposer.newDisposable("rename-processor-protocol-listener")
        val fileDocuments = FileDocumentManager.getInstance()
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    fileDocuments.getFile(event.document)?.let { file ->
                        affectedFilePaths += file.path
                    }
                }
            },
            listenerLifetime,
        )
        val usageCount = AtomicInteger()
        val startedAt = System.nanoTime()
        val cancellationLog = ExactProcessorCancellationLoggedErrorProcessor(
            phases,
            affectedFilePaths,
        )
        try {
            cancellationProbe()
            LoggedErrorProcessor.executeWith<RuntimeException>(cancellationLog) {
                ApplicationManager.getApplication().invokeAndWait {
                    val processor = ObservedRenameProcessor(
                        project = project,
                        target = target,
                        newName = newName,
                        onUsageSearch = {
                            phases += RenameProcessorProtocolPhase.DURING_RUN_USAGE_SEARCH
                            duringRunSearch()
                        },
                        onUsagesFound = usageCount::set,
                    )
                    declaredRelatedRenames.forEach { related ->
                        processor.addElement(related.element, related.newName)
                    }
                    processorRunner(processor)
                    phases += RenameProcessorProtocolPhase.PROCESSOR_COMMAND_COMPLETED
                }
            }
        } catch (_: ProcessCanceledException) {
            return unsupported(
                strategy,
                RenameProcessorProtocolLimitation.CANCELLED,
                phases,
                affectedFilePaths,
            )
        } catch (_: BaseRefactoringProcessor.ConflictsInTestsException) {
            return unsupported(
                strategy,
                RenameProcessorProtocolLimitation.CONFLICT,
                phases,
                affectedFilePaths,
            )
        } catch (failure: RuntimeException) {
            if (cancellationLog.wasObserved && failure.isExactCancellationDialogWrapper()) {
                return unsupported(
                    strategy,
                    RenameProcessorProtocolLimitation.CANCELLED,
                    phases,
                    affectedFilePaths,
                )
            }
            if (failure.cause !is BaseRefactoringProcessor.ConflictsInTestsException) {
                throw failure
            }
            return unsupported(
                strategy,
                RenameProcessorProtocolLimitation.CONFLICT,
                phases,
                affectedFilePaths,
            )
        } finally {
            Disposer.dispose(listenerLifetime)
        }
        if (cancellationLog.wasObserved) {
            return unsupported(
                strategy,
                RenameProcessorProtocolLimitation.CANCELLED,
                phases,
                affectedFilePaths,
            )
        }
        val duration = RenameProcessorCommandDuration(
            (System.nanoTime() - startedAt).coerceAtLeast(1L),
        )
        val targetNameAfter = readAction { target.name }
        if (targetNameAfter != newName || affectedFilePaths.isEmpty()) {
            return unsupported(
                strategy,
                RenameProcessorProtocolLimitation.SILENT_ABORT,
                phases,
                affectedFilePaths,
            )
        }
        val unrelatedRenameOccurred = readAction {
            protectedNamesBefore.any { (element, nameBefore) ->
                element.name != nameBefore
            }
        }
        if (unrelatedRenameOccurred) {
            return unsupported(
                strategy,
                RenameProcessorProtocolLimitation.UNDECLARED_RELATED_RENAME,
                phases,
                affectedFilePaths,
            )
        }
        val declaredRenameMissing = readAction {
            declaredRelatedRenames.any { related ->
                related.element.name != related.newName
            }
        }
        if (declaredRenameMissing) {
            return unsupported(
                strategy,
                RenameProcessorProtocolLimitation.DECLARED_RELATED_RENAME_MISSING,
                phases,
                affectedFilePaths,
            )
        }
        return RenameProcessorCharacterizationResult.Supported(
            RenameProcessorProtocolEvidence(
                strategy = strategy,
                targetPath = targetPath,
                targetNameBefore = targetNameBefore,
                targetNameAfter = requireNotNull(targetNameAfter),
                preRunReferencePaths = preRunReferencePaths,
                affectedFilePaths = affectedFilePaths.toSet(),
                phases = phases.toSet(),
                usageCount = RenameProcessorUsageCount(usageCount.get()),
                commandDuration = duration,
            ),
        )
    }

    private fun unsupported(
        strategy: RenameProcessorStrategy,
        limitation: RenameProcessorProtocolLimitation,
        phases: Set<RenameProcessorProtocolPhase>,
        affectedFilePaths: Set<String>,
    ): RenameProcessorCharacterizationResult.Unsupported =
        RenameProcessorCharacterizationResult.Unsupported(
            strategy = strategy,
            limitation = limitation,
            phases = phases.toSet(),
            affectedFilePaths = affectedFilePaths.toSet(),
        )
}

private class ExactProcessorCancellationLoggedErrorProcessor(
    private val phases: Set<RenameProcessorProtocolPhase>,
    private val affectedFilePaths: Set<String>,
) : LoggedErrorProcessor() {
    private val observed = AtomicBoolean()

    val wasObserved: Boolean
        get() = observed.get()

    override fun processError(
        category: String,
        message: String,
        details: Array<out String>,
        t: Throwable?,
    ): Set<Action> {
        val isExactCancellation =
            category == BASE_REFACTORING_PROCESSOR_LOG_CATEGORY &&
                message == EXPECTED_TEST_MODE_CANCELLATION_MESSAGE &&
                details.isEmpty() &&
                t.isExactProcessorCancellationWrapper() &&
                RenameProcessorProtocolPhase.DURING_RUN_USAGE_SEARCH in phases &&
                affectedFilePaths.isEmpty() &&
                observed.compareAndSet(false, true)
        return if (isExactCancellation) Action.NONE else super.processError(category, message, details, t)
    }
}

private fun Throwable?.isExactProcessorCancellationWrapper(): Boolean {
    val wrapper = this ?: return false
    if (wrapper.javaClass != IllegalStateException::class.java) return false
    val cancellation = wrapper.cause ?: return false
    if (cancellation.javaClass != ProcessCanceledException::class.java) return false
    return wrapper.message == cancellation.toString() &&
        cancellation.message == null &&
        cancellation.cause == null &&
        wrapper.stackTrace.firstOrNull()?.let { frame ->
            frame.className == BaseRefactoringProcessor::class.java.name &&
                frame.methodName == "doRun"
        } == true
}

private fun RuntimeException.isExactCancellationDialogWrapper(): Boolean {
    if (javaClass != RuntimeException::class.java) return false
    val dialogFailure = cause ?: return false
    if (dialogFailure.javaClass != RuntimeException::class.java) return false
    return message == dialogFailure.toString() &&
        dialogFailure.message == INDEX_CORRUPTION_DIALOG_MESSAGE &&
        dialogFailure.cause == null &&
        stackTrace.firstOrNull()?.let { frame ->
            frame.className == LATER_INVOCATOR_CLASS_NAME &&
                frame.methodName == "invokeAndWait"
        } == true &&
        dialogFailure.stackTrace.firstOrNull()?.let { frame ->
            frame.className == TEST_DIALOG_CLASS_NAME &&
                frame.methodName == "lambda\$static\$0"
        } == true &&
        dialogFailure.stackTrace.any { frame ->
            frame.className == BaseRefactoringProcessor::class.java.name &&
                frame.methodName == "doRun"
        }
}

private const val BASE_REFACTORING_PROCESSOR_LOG_CATEGORY =
    "#com.intellij.refactoring.BaseRefactoringProcessor"
private const val EXPECTED_TEST_MODE_CANCELLATION_MESSAGE = "PCE was not expected here"
private const val INDEX_CORRUPTION_DIALOG_MESSAGE =
    "Index corruption detected. Please retry the refactoring - indexes will be rebuilt automatically"
private const val LATER_INVOCATOR_CLASS_NAME =
    "com.intellij.openapi.application.impl.LaterInvocator"
private const val TEST_DIALOG_CLASS_NAME = "com.intellij.openapi.ui.TestDialog"

private class ObservedRenameProcessor(
    project: Project,
    target: PsiNamedElement,
    newName: String,
    private val onUsageSearch: () -> Unit,
    private val onUsagesFound: (Int) -> Unit,
) : RenameProcessor(project, target, newName, false, false) {
    override fun findUsages(): Array<UsageInfo> {
        onUsageSearch()
        return super.findUsages().also { usages ->
            onUsagesFound(usages.size)
        }
    }
}
