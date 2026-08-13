package io.github.amichne.kast.change.apply.intellij

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.codeStyle.CodeStyleManager
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyCommand
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyExecutor
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyPreconditionFailure
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyRecoveryFailure
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyUncertainFailure
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyResult
import io.github.amichne.kast.change.contract.AddDeclarationApplyObservation
import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

class IntellijAddDeclarationApplyExecutor(
    private val project: Project,
    private val beforePreparation: () -> Unit = ProgressManager::checkCanceled,
    private val beforeWriteCommand: () -> Unit = {},
) : AddDeclarationApplyExecutor {
    /**
     * Proof transition: `AddDeclarationApplyCommand -> AddDeclarationApplyResult`.
     *
     * Applied establishes the pinned KIP-030 PSI protocol, exact approved after image, explicit
     * save, observed write set, and global undo observation. Expected precondition and recovery
     * failures are disjoint closed outcomes. Raw paths, PSI, documents, bytes, and platform state
     * are extracted only inside this IntelliJ physical boundary.
     */
    override suspend fun apply(command: AddDeclarationApplyCommand): AddDeclarationApplyResult {
        return try {
            applyGuarded(command)
        } catch (_: Exception) {
            outcomeUnknown(AddDeclarationApplyUncertainFailure.WRITE_COMMAND_FAILED)
        }
    }

    private suspend fun applyGuarded(
        command: AddDeclarationApplyCommand,
    ): AddDeclarationApplyResult {
        try {
            beforePreparation()
        } catch (_: ProcessCanceledException) {
            return rejectedBefore(AddDeclarationApplyPreconditionFailure.CANCELLED)
        }
        val build = ApplicationInfo.getInstance().build
        if (admitIntellijRuntime(
                productCode = build.productCode,
                build = build.asStringWithoutProductCode(),
                supportedProductCode = SUPPORTED_PRODUCT_CODE,
                supportedBuild = SUPPORTED_RUNTIME_BUILD,
            ) is IntellijRuntimeAdmission.Unsupported
        ) {
            return rejectedBefore(AddDeclarationApplyPreconditionFailure.UNSUPPORTED_RUNTIME)
        }
        val prepared = when (val result = prepare(command)) {
            is IntellijAddDeclarationPreparation.Ready -> result
            is IntellijAddDeclarationPreparation.Rejected -> return rejectedBefore(result.failure)
        }
        try {
            beforeWriteCommand()
        } catch (_: ProcessCanceledException) {
            return rejectedBefore(AddDeclarationApplyPreconditionFailure.CANCELLED)
        }
        return execute(command, prepared)
    }

    /**
     * Proof transition:
     * `AddDeclarationApplyCommand -> IntellijAddDeclarationPreparation`.
     *
     * Ready proves the pinned target, smart/writable state, exact preimage, valid declaration, and
     * an exact representable append-only postimage before EDT admission. Expected failure is closed
     * by `AddDeclarationApplyPreconditionFailure`; raw PSI and documents stay in this adapter.
     */
    private suspend fun prepare(
        command: AddDeclarationApplyCommand,
    ): IntellijAddDeclarationPreparation = readAction {
        try {
            ProgressManager.checkCanceled()
        } catch (_: ProcessCanceledException) {
            return@readAction rejectedPreparation(AddDeclarationApplyPreconditionFailure.CANCELLED)
        }
        if (DumbService.getInstance(project).isDumb) {
            return@readAction rejectedPreparation(AddDeclarationApplyPreconditionFailure.DUMB_MODE)
        }
        val path = Path.of(command.plan.target.targetPath.value)
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path)
                          ?: return@readAction rejectedPreparation(
                              AddDeclarationApplyPreconditionFailure.TARGET_NOT_FOUND,
                          )
        if (!virtualFile.isValid) {
            return@readAction rejectedPreparation(
                AddDeclarationApplyPreconditionFailure.TARGET_INVALIDATED,
            )
        }
        if (!virtualFile.isWritable) {
            return@readAction rejectedPreparation(AddDeclarationApplyPreconditionFailure.TARGET_READ_ONLY)
        }
        val target = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
                     ?: return@readAction rejectedPreparation(
                         AddDeclarationApplyPreconditionFailure.TARGET_NOT_KOTLIN,
                     )
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                       ?: return@readAction rejectedPreparation(
                           AddDeclarationApplyPreconditionFailure.TARGET_DOCUMENT_UNAVAILABLE,
                       )
        val currentPhysicalBytes = try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            return@readAction rejectedPreparation(
                AddDeclarationApplyPreconditionFailure.TARGET_BYTES_UNAVAILABLE,
            )
        }
        val sourceImages = when (val result = ExactIntellijSourceImages.admit(
            expectedPreimage = command.plan.expectedFile.preimage,
            expectedPostimage = command.plan.expectedFile.postimage,
            currentPhysicalBytes = currentPhysicalBytes,
            normalizedDocumentText = document.text,
        )) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return@readAction rejectedPreparation(
                when (result.failure) {
                    ExactIntellijSourceImagesFailure.PREIMAGE_BYTES_MISMATCH,
                    ExactIntellijSourceImagesFailure.NORMALIZED_DOCUMENT_MISMATCH,
                    -> AddDeclarationApplyPreconditionFailure.TARGET_PREIMAGE_MISMATCH
                    ExactIntellijSourceImagesFailure.INVALID_UTF8 ->
                        AddDeclarationApplyPreconditionFailure.APPROVED_POSTIMAGE_UNREPRESENTABLE
                },
            )
        }
        val append = when (val result = exactAppend(
            sourceImages.normalizedPreimage,
            sourceImages.normalizedPostimage,
            command.plan.intent.proposedDeclaration.value,
        )) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return@readAction rejectedPreparation(
                AddDeclarationApplyPreconditionFailure.APPROVED_POSTIMAGE_UNREPRESENTABLE,
            )
        }
        val declaration = try {
            KtPsiFactory(project, false).createDeclaration<KtDeclaration>(
                command.plan.intent.proposedDeclaration.value,
            )
        } catch (_: ProcessCanceledException) {
            return@readAction rejectedPreparation(AddDeclarationApplyPreconditionFailure.CANCELLED)
        } catch (_: Exception) {
            return@readAction rejectedPreparation(
                AddDeclarationApplyPreconditionFailure.DECLARATION_INVALID,
            )
        }
        IntellijAddDeclarationPreparation.Ready(
            target = target,
            declaration = declaration,
            document = document,
            sourceImages = sourceImages,
            prefixWhitespace = append.prefixWhitespace,
        )
    }

    private fun execute(
        command: AddDeclarationApplyCommand,
        prepared: IntellijAddDeclarationPreparation.Ready,
    ): AddDeclarationApplyResult {
        val changedPaths = ConcurrentHashMap.newKeySet<String>()
        val fileDocuments = FileDocumentManager.getInstance()
        val listenerLifetime = Disposer.newDisposable("kast-add-declaration-apply-observer")
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    fileDocuments.getFile(event.document)?.path?.let(changedPaths::add)
                }
            },
            listenerLifetime,
        )
        val progress = AtomicReference(IntellijApplyAttemptProgress.NOT_BEGUN)
        val commandExecution = try {
            runOnEdt {
                when (val final = finalPrecondition(prepared)) {
                    IntellijFinalPrecondition.Ready -> Unit
                    is IntellijFinalPrecondition.Rejected ->
                        return@runOnEdt IntellijCommandExecution.RejectedBeforeMutation(
                            final.failure,
                        )
                }
                try {
                    val platformResult = WriteCommandAction.writeCommandAction(project, prepared.target)
                        .withName(COMMAND_NAME)
                        .withGroupId(COMMAND_GROUP)
                        .compute<IntellijCommandExecution, RuntimeException> {
                            val formattedCopy = CodeStyleManager.getInstance(project).reformat(
                                prepared.declaration.copy(),
                                true,
                            ) as KtDeclaration
                            if (formattedCopy.text != prepared.declaration.text) {
                                return@compute IntellijCommandExecution.RejectedBeforeMutation(
                                    AddDeclarationApplyPreconditionFailure.APPROVED_POSTIMAGE_UNREPRESENTABLE,
                                )
                            }
                            progress.set(IntellijApplyAttemptProgress.MAY_HAVE_BEGUN)
                            if (prepared.prefixWhitespace.isNotEmpty()) {
                                prepared.target.add(
                                    PsiParserFacade.getInstance(project).createWhiteSpaceFromText(
                                        prepared.prefixWhitespace,
                                    ),
                                )
                                progress.set(IntellijApplyAttemptProgress.BEGUN)
                            }
                            val added = prepared.target.add(formattedCopy) as KtDeclaration
                            progress.set(IntellijApplyAttemptProgress.BEGUN)
                            CodeStyleManager.getInstance(project).reformat(added, true)
                            prepared.target.add(
                                PsiParserFacade.getInstance(project).createWhiteSpaceFromText("\n"),
                            )
                            PsiDocumentManager.getInstance(project).commitDocument(prepared.document)
                            if (prepared.document.text != prepared.sourceImages.normalizedPostimage.text) {
                                return@compute IntellijCommandExecution.RecoveryRequiredAfterMutation(
                                    AddDeclarationApplyRecoveryFailure.WRITE_COMMAND_FAILED,
                                )
                            }
                            progress.set(IntellijApplyAttemptProgress.COMMAND_COMPLETED)
                            IntellijCommandExecution.CommandCompleted
                        }
                    platformResult ?: IntellijCommandExecution.RejectedBeforeMutation(
                        AddDeclarationApplyPreconditionFailure.WRITE_COMMAND_NOT_ENTERED,
                    )
                } catch (_: ProcessCanceledException) {
                    commandFailure(progress.get())
                } catch (_: Exception) {
                    commandFailure(progress.get())
                }
            }
        } catch (_: Exception) {
            commandFailure(progress.get())
        }
        try {
            when (commandExecution) {
                is IntellijCommandExecution.RejectedBeforeMutation ->
                    return rejectedBefore(commandExecution.failure)
                is IntellijCommandExecution.MutationOutcomeUnknown ->
                    return outcomeUnknown(commandExecution.failure)
                is IntellijCommandExecution.RecoveryRequiredAfterMutation ->
                    return recoveryRequiredAfter(commandExecution.failure)
                IntellijCommandExecution.NotInvoked -> return rejectedBefore(
                    AddDeclarationApplyPreconditionFailure.WRITE_COMMAND_NOT_ENTERED,
                )
                IntellijCommandExecution.CommandCompleted -> Unit
            }
            val platformObservation = try {
                observeAfterCommandOnEdt(project, fileDocuments, prepared.document, changedPaths)
            } catch (_: Exception) {
                return recoveryRequiredAfter(AddDeclarationApplyRecoveryFailure.OBSERVATION_INVALID)
            }
            if (platformObservation is IntellijAfterCommandObservation.SaveIncomplete) {
                return recoveryRequiredAfter(AddDeclarationApplyRecoveryFailure.DOCUMENT_SAVE_INCOMPLETE)
            }
            val observed = platformObservation as IntellijAfterCommandObservation.Observed
            val physicalBytes = try {
                Files.readAllBytes(Path.of(command.plan.target.targetPath.value))
            } catch (_: Exception) {
                return recoveryRequiredAfter(AddDeclarationApplyRecoveryFailure.DOCUMENT_SAVE_INCOMPLETE)
            }
            val afterImage = when (val result = exactImage(physicalBytes)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return recoveryRequiredAfter(
                    AddDeclarationApplyRecoveryFailure.OBSERVATION_INVALID,
                )
            }
            if (prepared.sourceImages.admitPostimage(physicalBytes) is Refinement.Rejected) {
                return recoveryRequiredAfter(AddDeclarationApplyRecoveryFailure.OBSERVATION_INVALID)
            }
            val observation = when (val result = AddDeclarationApplyObservation.observe(
                plan = command.plan,
                changedDocumentPaths = observed.changedPaths,
                afterImage = afterImage,
                undoAvailability = observed.undoAvailability,
            )) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return recoveryRequiredAfter(
                    AddDeclarationApplyRecoveryFailure.OBSERVATION_INVALID,
                )
            }
            return AddDeclarationApplyResult.Applied(observation)
        } finally {
            Disposer.dispose(listenerLifetime)
        }
    }

    /**
     * Proof transition:
     * `IntellijAddDeclarationPreparation.Ready -> IntellijFinalPrecondition`.
     *
     * Ready re-establishes validity, writability, smart mode, exact preimage, and cancellation on
     * EDT immediately before command entry. Rejected closes expected failure with
     * `AddDeclarationApplyPreconditionFailure`; raw platform state remains in this adapter.
     */
    private fun finalPrecondition(
        prepared: IntellijAddDeclarationPreparation.Ready,
    ): IntellijFinalPrecondition {
        val physicalBytes = try {
            Files.readAllBytes(Path.of(prepared.target.virtualFile.path))
        } catch (_: Exception) {
            return IntellijFinalPrecondition.Rejected(
                AddDeclarationApplyPreconditionFailure.TARGET_BYTES_UNAVAILABLE,
            )
        }
        return when {
        prepared.sourceImages.admitPreimage(physicalBytes) is Refinement.Rejected ->
            IntellijFinalPrecondition.Rejected(
                AddDeclarationApplyPreconditionFailure.TARGET_PREIMAGE_MISMATCH,
            )
        !prepared.target.isValid || !prepared.declaration.isValid ->
            IntellijFinalPrecondition.Rejected(AddDeclarationApplyPreconditionFailure.TARGET_INVALIDATED)
        !prepared.target.virtualFile.isWritable -> IntellijFinalPrecondition.Rejected(
            AddDeclarationApplyPreconditionFailure.TARGET_READ_ONLY,
        )
        DumbService.getInstance(project).isDumb -> IntellijFinalPrecondition.Rejected(
            AddDeclarationApplyPreconditionFailure.DUMB_MODE,
        )
        prepared.document.text != prepared.sourceImages.normalizedPreimage.text ->
            IntellijFinalPrecondition.Rejected(
                AddDeclarationApplyPreconditionFailure.TARGET_PREIMAGE_MISMATCH,
            )
        else -> try {
            ProgressManager.checkCanceled()
            IntellijFinalPrecondition.Ready
        } catch (_: ProcessCanceledException) {
            IntellijFinalPrecondition.Rejected(AddDeclarationApplyPreconditionFailure.CANCELLED)
        }
        }
    }

    private fun runOnEdt(action: () -> IntellijCommandExecution): IntellijCommandExecution {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        val result = AtomicReference<IntellijCommandExecution>(IntellijCommandExecution.NotInvoked)
        application.invokeAndWait { result.set(action()) }
        return result.get()
    }

    private fun rejectedBefore(
        failure: AddDeclarationApplyPreconditionFailure,
    ): AddDeclarationApplyResult.RejectedBeforeMutation =
        AddDeclarationApplyResult.RejectedBeforeMutation(failure)

    private fun outcomeUnknown(
        failure: AddDeclarationApplyUncertainFailure,
    ): AddDeclarationApplyResult.MutationOutcomeUnknown =
        AddDeclarationApplyResult.MutationOutcomeUnknown(failure)
    private fun recoveryRequiredAfter(
        failure: AddDeclarationApplyRecoveryFailure,
    ): AddDeclarationApplyResult.RecoveryRequiredAfterMutation =
        AddDeclarationApplyResult.RecoveryRequiredAfterMutation(failure)
    private fun rejectedPreparation(
        failure: AddDeclarationApplyPreconditionFailure,
    ): IntellijAddDeclarationPreparation.Rejected = IntellijAddDeclarationPreparation.Rejected(failure)

    companion object {
        const val COMMAND_NAME: String = "Kast add declaration"
        const val COMMAND_GROUP: String = "kast.add-declaration"
        const val SUPPORTED_PRODUCT_CODE: String = "IC"
        const val SUPPORTED_RUNTIME_BUILD: String = "261.25134.95"
    }
}
