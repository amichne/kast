package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
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
import com.intellij.psi.PsiManager
import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.apply.AppliedSourceWrite
import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.apply.MutationDurabilityBarrier
import io.github.amichne.kast.change.apply.MutationPreconditionAtIntellijBoundary
import io.github.amichne.kast.change.apply.MutationSourceCaptureFailure
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationFailure
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.change.apply.SourceWriteFailure
import io.github.amichne.kast.change.apply.SourceWriteResult
import io.github.amichne.kast.change.contract.ChangeIntent
import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Sole clean-slate IntelliJ observer, normal writer, and authority-bound recovery adapter. */
class IntellijChangeSourceAdapter(
    private val project: Project,
) : AddDeclarationSourceObserver, AddDeclarationSourceWriter, AddDeclarationSourceRollback {
    private val protocol = IntellijSourceWriteProtocol()
    private val addFile = IntellijAddFileSourcePrimitive(project)
    private val existingRollback = IntellijExistingSourceRollback(project)

    /**
     * Proof transition: `WorkspaceFile -> SourceObservationResult`.
     *
     * Observed establishes a valid Kotlin file, exact physical bytes, and current writability.
     * [SourceObservationFailure] closes expected platform failures. Raw paths, PSI, documents, and
     * bytes exist only inside this adapter call.
     */
    override fun observe(
        source: SymbolDiscoveryFileIdentity.Workspace,
    ): SourceObservationResult = try {
        ProgressManager.checkCanceled()
        if (DumbService.getInstance(project).isDumb) {
            rejectedObservation(SourceObservationFailure.DUMB_MODE)
        } else {
            ReadAction.computeBlocking<SourceObservationResult, RuntimeException> {
                observeReady(source)
            }
        }
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        rejectedObservation(SourceObservationFailure.SOURCE_BYTES_UNAVAILABLE)
    }

    /**
     * Proof transition: `(MutationAuthority, MutationDurabilityBarrier) -> SourceWriteResult`.
     *
     * Applied establishes the authority's exact singleton postimage after applied-write durability
     * and physical save. [SourceWriteFailure] closes expected platform failures. Raw authority
     * extraction and all live IntelliJ values remain inside this call.
     */
    override fun write(
        authority: MutationAuthority,
        durability: MutationDurabilityBarrier,
    ): SourceWriteResult = when (authority.intent) {
        is ChangeIntent.AddFile -> addFile.write(authority, durability)
        is ChangeIntent.AddDeclaration,
        is ChangeIntent.RenameSymbol,
        is ChangeIntent.ReplaceDeclaration,
            -> writeExisting(authority, durability)
    }

    private fun writeExisting(
        authority: MutationAuthority,
        durability: MutationDurabilityBarrier,
    ): SourceWriteResult {
        val prepared = when (val result = prepare(authority)) {
            is IntellijSourcePreparation.Ready -> result
            is IntellijSourcePreparation.Rejected ->
                return SourceWriteResult.RejectedBeforeMutation(result.failure)
        }
        val changedPaths = ConcurrentHashMap.newKeySet<String>()
        val lifetime = Disposer.newDisposable("kast-clean-slate-semantic-change")
        val files = FileDocumentManager.getInstance()
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    files.getFile(event.document)?.path?.let(changedPaths::add)
                }
            },
            lifetime,
        )
        return try {
            val input = when (val result = authority.toIntellijInput()) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return SourceWriteResult.RejectedBeforeMutation(
                    result.failure,
                )
            }
            when (val result = protocol.execute(
                input,
                durability,
                LiveIntellijDocumentSession(project, prepared, input, changedPaths),
            )) {
                is IntellijWriteProtocolResult.Applied -> when (val observed =
                    AppliedSourceWrite.observe(authority, result.bytes, result.changedPaths)
                ) {
                    is Refinement.Refined -> SourceWriteResult.Applied(observed.value)
                    is Refinement.Rejected ->
                        SourceWriteResult.RecoveryRequired(SourceWriteFailure.OBSERVATION_FAILED)
                }
                is IntellijWriteProtocolResult.RejectedBeforeMutation ->
                    SourceWriteResult.RejectedBeforeMutation(result.failure)
                is IntellijWriteProtocolResult.RejectedAfterRollback ->
                    SourceWriteResult.RejectedAfterRollback(result.failure)
                is IntellijWriteProtocolResult.RecoveryRequired ->
                    SourceWriteResult.RecoveryRequired(result.failure)
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            SourceWriteResult.RecoveryRequired(SourceWriteFailure.MUTATION_FAILED)
        } finally {
            Disposer.dispose(lifetime)
        }
    }

    /**
     * Proof transition: `(MutationAuthority, AppliedWritesDurable) ->
     * AddDeclarationRollbackResult`.
     *
     * RolledBack establishes the exact authority preimage and overwrites only its exact postimage
     * or accepts an already restored preimage. `AddDeclarationRollbackFailure` closes expected
     * mismatch and platform failures. Raw recovery bytes leave only inside this adapter call.
     */
    override fun rollback(
        authority: MutationAuthority,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): AddDeclarationRollbackResult {
        val planned = record.preparation.plannedWrites
        if (
            record.binding != authority.binding ||
            planned.size != 1 ||
            planned.single().source.value != authority.source.path.value
        ) {
            return AddDeclarationRollbackResult.Rejected(
                AddDeclarationRollbackFailure.CONTENT_DIVERGED,
            )
        }
        val preimage = planned.single().preimage.decodeAtRecoveryBoundary()
        return when (val expected = authority.preconditionAtIntellijBoundary()) {
            MutationPreconditionAtIntellijBoundary.Absent -> addFile.rollback(
                authority,
                preimage,
            )
            is MutationPreconditionAtIntellijBoundary.Existing -> rollbackExisting(
                authority, expected, preimage,
            )
        }
    }

    private fun rollbackExisting(
        authority: MutationAuthority,
        expected: MutationPreconditionAtIntellijBoundary.Existing,
        preimage: ByteArray,
    ): AddDeclarationRollbackResult = existingRollback.rollback(authority, expected, preimage)

    /**
     * Proof transition: `WorkspaceFile -> SourceObservationResult`.
     *
     * Establishes a live valid Kotlin file with a document, exact physical bytes, and closed
     * writability state. [SourceObservationFailure] closes every expected platform rejection. Raw
     * platform objects and bytes remain inside this observation boundary.
     */
    private fun observeReady(
        source: SymbolDiscoveryFileIdentity.Workspace,
    ): SourceObservationResult {
        when (val absence = addFile.observeIfAbsent(source)) {
            IntellijAddFileAbsenceObservation.TargetPresent -> Unit
            is IntellijAddFileAbsenceObservation.Observed -> return absence.result
            is IntellijAddFileAbsenceObservation.Rejected -> return rejectedObservation(
                absence.failure,
            )
        }
        val path = Path.of(source.path.value)
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
                   ?: return rejectedObservation(SourceObservationFailure.TARGET_NOT_FOUND)
        if (!file.isValid) return rejectedObservation(SourceObservationFailure.TARGET_INVALIDATED)
        val target = PsiManager.getInstance(project).findFile(file) as? KtFile
                     ?: return rejectedObservation(SourceObservationFailure.TARGET_NOT_KOTLIN)
        if (!target.isValid) return rejectedObservation(SourceObservationFailure.TARGET_INVALIDATED)
        if (FileDocumentManager.getInstance().getDocument(file) == null) {
            return rejectedObservation(SourceObservationFailure.DOCUMENT_UNAVAILABLE)
        }
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            return rejectedObservation(SourceObservationFailure.SOURCE_BYTES_UNAVAILABLE)
        }
        val access = if (file.isWritable) SourceWriteAccess.Writable else SourceWriteAccess.ReadOnly
        return when (val captured = ObservedMutationSource.capture(source, bytes, access)) {
            is Refinement.Refined -> SourceObservationResult.Observed(captured.value)
            is Refinement.Rejected -> rejectedObservation(
                when (captured.failure) {
                    MutationSourceCaptureFailure.INVALID_UTF8,
                    MutationSourceCaptureFailure.SOURCE_HASH_UNREPRESENTABLE,
                        -> SourceObservationFailure.INVALID_SOURCE_CONTENT
                },
            )
        }
    }

    /**
     * Proof transition: `MutationAuthority -> IntellijSourcePreparation`.
     *
     * Ready establishes cancellation, smart-mode, and read-action preparation for the exact
     * authority. [SourceWriteFailure] closes expected rejection. Raw platform state remains inside
     * the returned request-local capability and never crosses the public writer boundary.
     */
    private fun prepare(authority: MutationAuthority): IntellijSourcePreparation = try {
        ProgressManager.checkCanceled()
        if (DumbService.getInstance(project).isDumb) {
            IntellijSourcePreparation.Rejected(SourceWriteFailure.DUMB_MODE)
        } else {
            ReadAction.compute<IntellijSourcePreparation, RuntimeException> {
                prepareRead(authority)
            }
        }
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        IntellijSourcePreparation.Rejected(SourceWriteFailure.TARGET_INVALIDATED)
    }

    /**
     * Proof transition: `MutationAuthority -> IntellijSourcePreparation`.
     *
     * Ready establishes an exact valid writable Kotlin file, available document, byte-identical
     * preimage, compiler-grounded anchor range, and parseable declaration. [SourceWriteFailure]
     * closes expected rejection. Raw PSI and document extraction is confined to this adapter.
     */
    private fun prepareRead(authority: MutationAuthority): IntellijSourcePreparation {
        val path = Path.of(authority.source.path.value)
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
                   ?: return IntellijSourcePreparation.Rejected(SourceWriteFailure.TARGET_NOT_FOUND)
        if (!file.isValid) {
            return IntellijSourcePreparation.Rejected(SourceWriteFailure.TARGET_INVALIDATED)
        }
        if (!file.isWritable) {
            return IntellijSourcePreparation.Rejected(SourceWriteFailure.TARGET_READ_ONLY)
        }
        val target = PsiManager.getInstance(project).findFile(file) as? KtFile
                     ?: return IntellijSourcePreparation.Rejected(SourceWriteFailure.TARGET_NOT_KOTLIN)
        val document = FileDocumentManager.getInstance().getDocument(file)
                       ?: return IntellijSourcePreparation.Rejected(SourceWriteFailure.DOCUMENT_UNAVAILABLE)
        val preimage = when (val expected = authority.preconditionAtIntellijBoundary()) {
            MutationPreconditionAtIntellijBoundary.Absent -> return IntellijSourcePreparation
                .Rejected(SourceWriteFailure.PREIMAGE_CHANGED)
            is MutationPreconditionAtIntellijBoundary.Existing -> expected.text
        }
        if (document.text != preimage) {
            return IntellijSourcePreparation.Rejected(SourceWriteFailure.PREIMAGE_CHANGED)
        }
        when (val intent = authority.intent) {
            is ChangeIntent.AddFile -> return IntellijSourcePreparation.Rejected(
                SourceWriteFailure.MUTATION_FAILED,
            )
            is ChangeIntent.AddDeclaration -> {
                val anchor = target.declarations.filter { declaration ->
                    declaration.textRange.startOffset == intent.target.range.startInclusive &&
                        declaration.textRange.endOffset == intent.target.range.endExclusive
                }
                if (anchor.size != 1) {
                    return IntellijSourcePreparation.Rejected(SourceWriteFailure.TARGET_INVALIDATED)
                }
                try {
                    KtPsiFactory(project, false)
                        .createDeclaration<org.jetbrains.kotlin.psi.KtDeclaration>(
                            intent.declaration.value,
                        )
                } catch (cancellation: ProcessCanceledException) {
                    throw cancellation
                } catch (_: Exception) {
                    return IntellijSourcePreparation.Rejected(SourceWriteFailure.MUTATION_FAILED)
                }
            }
            is ChangeIntent.RenameSymbol -> {
                if (authority.mutationsAtIntellijBoundary().any { mutation ->
                        mutation !is SourceTextMutation.Replace ||
                            mutation.range.endExclusive > document.textLength ||
                            document.getText(
                                com.intellij.openapi.util.TextRange(
                                    mutation.range.startInclusive,
                                    mutation.range.endExclusive,
                                ),
                            ) != mutation.expected.value
                    }
                ) {
                    return IntellijSourcePreparation.Rejected(SourceWriteFailure.TARGET_INVALIDATED)
                }
            }
            is ChangeIntent.ReplaceDeclaration -> {
                val selected = intent.target.target
                val declarations = target.declarations.filter { declaration ->
                    declaration.textRange.startOffset == selected.range.startInclusive &&
                        declaration.textRange.endOffset == selected.range.endExclusive &&
                        declaration.text == intent.target.expected.value
                }
                if (declarations.size != 1) {
                    return IntellijSourcePreparation.Rejected(SourceWriteFailure.TARGET_INVALIDATED)
                }
                try {
                    KtPsiFactory(project, false)
                        .createDeclaration<org.jetbrains.kotlin.psi.KtDeclaration>(
                            intent.replacement.value,
                        )
                } catch (cancellation: ProcessCanceledException) {
                    throw cancellation
                } catch (_: Exception) {
                    return IntellijSourcePreparation.Rejected(SourceWriteFailure.MUTATION_FAILED)
                }
            }
        }
        return IntellijSourcePreparation.Ready(file, target, document)
    }

    /**
     * Proof transition: `MutationAuthority -> Refinement<IntellijMutationInput,
     * SourceWriteFailure>`.
     *
     * Establishes an existing-source preimage plus only exact in-file transformations for the
     * document protocol. [SourceWriteFailure] closes absent or whole-file creation authority.
     * Raw text extraction remains inside this adapter boundary.
     */
    private fun MutationAuthority.toIntellijInput(): Refinement<
        IntellijMutationInput,
        SourceWriteFailure,
    > {
        val preimage = when (val expected = preconditionAtIntellijBoundary()) {
            MutationPreconditionAtIntellijBoundary.Absent -> return Refinement.Rejected(
                SourceWriteFailure.PREIMAGE_CHANGED,
            )
            is MutationPreconditionAtIntellijBoundary.Existing -> expected.text
        }
        val mutations = mutationsAtIntellijBoundary().map { mutation ->
            when (mutation) {
                is SourceTextMutation.CreateFile -> return Refinement.Rejected(
                    SourceWriteFailure.MUTATION_FAILED,
                )
                is SourceTextMutation.InsertAfterDeclaration -> IntellijTextMutation(
                    mutation.anchor.endExclusive,
                    mutation.anchor.endExclusive,
                    "\n\n${mutation.declaration.value}",
                )
                is SourceTextMutation.Replace -> IntellijTextMutation(
                    mutation.range.startInclusive,
                    mutation.range.endExclusive,
                    mutation.replacement.value,
                )
                is SourceTextMutation.ReplaceDeclaration -> IntellijTextMutation(
                    mutation.range.startInclusive,
                    mutation.range.endExclusive,
                    mutation.replacement.value,
                )
            }
        }
        return Refinement.Refined(
            IntellijMutationInput(
                source.path.value,
                preimage,
                postimageTextAtIntellijBoundary(),
                mutations,
            ),
        )
    }

    private fun rejectedObservation(failure: SourceObservationFailure) =
        SourceObservationResult.Rejected(failure)

    private fun rejectedRollback(failure: AddDeclarationRollbackFailure) =
        AddDeclarationRollbackResult.Rejected(failure)
}
