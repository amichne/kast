package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.change.apply.AppliedSourceWrite
import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.apply.MutationDurabilityBarrier
import io.github.amichne.kast.change.apply.MutationPreconditionAtIntellijBoundary
import io.github.amichne.kast.change.apply.ObservedAbsentMutationSource
import io.github.amichne.kast.change.apply.SourceObservationFailure
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.change.apply.SourceWriteFailure
import io.github.amichne.kast.change.apply.SourceWriteResult
import io.github.amichne.kast.change.contract.ChangeIntent
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.psi.KtPsiFactory

internal sealed interface IntellijAddFileAbsenceObservation {
    data object TargetPresent : IntellijAddFileAbsenceObservation

    data class Observed(
        val result: SourceObservationResult.Observed,
    ) : IntellijAddFileAbsenceObservation

    data class Rejected(
        val failure: SourceObservationFailure,
    ) : IntellijAddFileAbsenceObservation
}

private class IntellijAddFilePreparation(
    val parent: VirtualFile,
    val fileName: String,
    val path: Path,
)

/** Intent-specific IntelliJ primitive for one exact absent Kotlin file. */
internal class IntellijAddFileSourcePrimitive(
    private val project: Project,
) {
    private val protocol = IntellijAddFileWriteProtocol()

    /**
     * Proof transition: `WorkspaceFile -> IntellijAddFileAbsenceObservation`.
     *
     * Observed establishes exact physical absence and parent-derived creation access. TargetPresent
     * preserves the weaker state for existing-source observation. [SourceObservationFailure]
     * closes unavailable parents. Raw filesystem and VFS values remain inside this call.
     */
    fun observeIfAbsent(
        source: SymbolDiscoveryFileIdentity.Workspace,
    ): IntellijAddFileAbsenceObservation {
        val path = Path.of(source.path.value)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return IntellijAddFileAbsenceObservation.TargetPresent
        }
        val parentPath = path.parent ?: return IntellijAddFileAbsenceObservation.Rejected(
            SourceObservationFailure.TARGET_NOT_FOUND,
        )
        val parent = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parentPath)
            ?: return IntellijAddFileAbsenceObservation.Rejected(
                SourceObservationFailure.TARGET_NOT_FOUND,
            )
        if (!parent.isValid || !parent.isDirectory) {
            return IntellijAddFileAbsenceObservation.Rejected(
                SourceObservationFailure.TARGET_INVALIDATED,
            )
        }
        val access = if (parent.isWritable && Files.isWritable(parentPath)) {
            SourceWriteAccess.Writable
        } else {
            SourceWriteAccess.ReadOnly
        }
        return IntellijAddFileAbsenceObservation.Observed(
            SourceObservationResult.Observed(
                ObservedAbsentMutationSource.fromPhysicalBoundary(source, access),
            ),
        )
    }

    /**
     * Proof transition: `(MutationAuthority, MutationDurabilityBarrier) -> SourceWriteResult`.
     *
     * Applied establishes in-memory staging against exact absence, applied-write durability,
     * singleton physical creation, and exact postimage observation. [SourceWriteFailure] closes
     * expected platform failure. Live IntelliJ values remain inside this call.
     */
    fun write(
        authority: MutationAuthority,
        durability: MutationDurabilityBarrier,
    ): SourceWriteResult {
        val prepared = when (val result = prepare(authority)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return SourceWriteResult.RejectedBeforeMutation(
                result.failure,
            )
        }
        val changedPaths = ConcurrentHashMap.newKeySet<String>()
        val lifetime = Disposer.newDisposable("kast-clean-slate-add-file")
        VirtualFileManager.getInstance().addVirtualFileListener(
            object : VirtualFileListener {
                override fun fileCreated(event: VirtualFileEvent) {
                    changedPaths += event.file.path
                }

                override fun contentsChanged(event: VirtualFileEvent) {
                    changedPaths += event.file.path
                }
            },
            lifetime,
        )
        return try {
            val input = IntellijAddFileInput(
                authority.source.path.value,
                authority.postimageTextAtIntellijBoundary(),
            )
            resolve(
                authority,
                protocol.execute(
                    input,
                    durability,
                    LiveIntellijAddFileStagingSession(project, prepared, changedPaths),
                ),
            )
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            SourceWriteResult.RecoveryRequired(SourceWriteFailure.MUTATION_FAILED)
        } finally {
            Disposer.dispose(lifetime)
        }
    }

    /**
     * Proof transition: `(MutationAuthority, ByteArray) -> AddDeclarationRollbackResult`.
     *
     * RolledBack establishes that the exact AddFile target is absent, deleting it only when its
     * bytes still equal the authority postimage and the durable recovery marker proves prior
     * absence. [AddDeclarationRollbackFailure] closes divergent or unavailable targets.
     */
    fun rollback(
        authority: MutationAuthority,
        recoveryMarker: ByteArray,
    ): AddDeclarationRollbackResult {
        if (
            authority.preconditionAtIntellijBoundary() !=
            MutationPreconditionAtIntellijBoundary.Absent ||
            recoveryMarker.isNotEmpty()
        ) {
            return rejectedRollback(AddDeclarationRollbackFailure.CONTENT_DIVERGED)
        }
        val path = Path.of(authority.source.path.value)
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return AddDeclarationRollbackResult.RolledBack
        }
        val current = try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            return rejectedRollback(AddDeclarationRollbackFailure.TARGET_UNAVAILABLE)
        }
        if (!current.contentEquals(authority.postimageBytesAtIntellijBoundary())) {
            return rejectedRollback(AddDeclarationRollbackFailure.CONTENT_DIVERGED)
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: return rejectedRollback(AddDeclarationRollbackFailure.TARGET_UNAVAILABLE)
        return try {
            onEdt {
                WriteCommandAction.writeCommandAction(project)
                    .withName("Kast rollback AddFile")
                    .compute<Unit, RuntimeException> { file.delete(this) }
            }
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                AddDeclarationRollbackResult.RolledBack
            } else {
                rejectedRollback(AddDeclarationRollbackFailure.WRITE_REJECTED)
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            rejectedRollback(AddDeclarationRollbackFailure.WRITE_REJECTED)
        }
    }

    /**
     * Proof transition: `MutationAuthority -> Refinement<IntellijAddFilePreparation,
     * SourceWriteFailure>`.
     *
     * Establishes exact physical absence, a valid writable parent, an AddFile authority, and a
     * syntax-valid detached Kotlin postimage. [SourceWriteFailure] closes stale or unsupported
     * state. Raw PSI, paths, and VFS values remain inside the request-local capability.
     */
    private fun prepare(
        authority: MutationAuthority,
    ): Refinement<IntellijAddFilePreparation, SourceWriteFailure> = try {
        ProgressManager.checkCanceled()
        if (DumbService.getInstance(project).isDumb) {
            Refinement.Rejected(SourceWriteFailure.DUMB_MODE)
        } else {
            ReadAction.compute<Refinement<IntellijAddFilePreparation, SourceWriteFailure>, RuntimeException> {
                prepareRead(authority)
            }
        }
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        Refinement.Rejected(SourceWriteFailure.TARGET_INVALIDATED)
    }

    /**
     * Proof transition: `MutationAuthority -> Refinement<IntellijAddFilePreparation,
     * SourceWriteFailure>` inside one smart read action.
     *
     * Establishes exact absence, writable parent identity, AddFile intent, and a Kotlin PSI tree
     * without syntax errors. [SourceWriteFailure] closes stale, unavailable, unsupported, or
     * malformed state. Live PSI and VFS values remain in the returned request-local capability.
     */
    private fun prepareRead(
        authority: MutationAuthority,
    ): Refinement<IntellijAddFilePreparation, SourceWriteFailure> {
        val intent = authority.intent as? ChangeIntent.AddFile
            ?: return Refinement.Rejected(SourceWriteFailure.MUTATION_FAILED)
        if (
            authority.preconditionAtIntellijBoundary() !=
            MutationPreconditionAtIntellijBoundary.Absent
        ) {
            return Refinement.Rejected(SourceWriteFailure.PREIMAGE_CHANGED)
        }
        val path = Path.of(authority.source.path.value)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Refinement.Rejected(SourceWriteFailure.PREIMAGE_CHANGED)
        }
        val parentPath = path.parent
            ?: return Refinement.Rejected(SourceWriteFailure.TARGET_NOT_FOUND)
        val parent = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(parentPath)
            ?: return Refinement.Rejected(SourceWriteFailure.TARGET_NOT_FOUND)
        if (!parent.isValid || !parent.isDirectory) {
            return Refinement.Rejected(SourceWriteFailure.TARGET_INVALIDATED)
        }
        if (!parent.isWritable || !Files.isWritable(parentPath)) {
            return Refinement.Rejected(SourceWriteFailure.TARGET_READ_ONLY)
        }
        val parsed = KtPsiFactory(project, false).createFile(path.fileName.toString(), intent.content.value)
        if (PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java) != null) {
            return Refinement.Rejected(SourceWriteFailure.MUTATION_FAILED)
        }
        return Refinement.Refined(
            IntellijAddFilePreparation(parent, path.fileName.toString(), path),
        )
    }

    private fun resolve(
        authority: MutationAuthority,
        result: IntellijWriteProtocolResult,
    ): SourceWriteResult = when (result) {
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

    private fun rejectedRollback(failure: AddDeclarationRollbackFailure) =
        AddDeclarationRollbackResult.Rejected(failure)
}

private class LiveIntellijAddFileStagingSession(
    private val project: Project,
    private val prepared: IntellijAddFilePreparation,
    private val changedPaths: Set<String>,
) : IntellijAddFileStagingSession {
    override fun physicalState(): IntellijAddFilePhysicalState {
        if (Files.notExists(prepared.path, LinkOption.NOFOLLOW_LINKS)) {
            return IntellijAddFilePhysicalState.Absent
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(prepared.path)))
                .toString()
        } catch (_: Exception) {
            return IntellijAddFilePhysicalState.Rejected(SourceWriteFailure.OBSERVATION_FAILED)
        }
        return IntellijAddFilePhysicalState.Present(text)
    }

    override fun stage(postimageText: String): IntellijAddFileStageResult =
        IntellijAddFileStageResult.Staged(IntellijStagedAddFile(postimageText))

    override fun clearStage(staged: IntellijStagedAddFile): IntellijSessionStepResult =
        IntellijSessionStepResult.Completed

    override fun save(staged: IntellijStagedAddFile): IntellijSessionStepResult {
        if (physicalState() != IntellijAddFilePhysicalState.Absent) {
            return IntellijSessionStepResult.Rejected(SourceWriteFailure.PREIMAGE_CHANGED)
        }
        return try {
            onEdt {
                WriteCommandAction.writeCommandAction(project)
                    .withName("Kast AddFile")
                    .compute<VirtualFile, RuntimeException> {
                        prepared.parent.createChildData(this, prepared.fileName).also { created ->
                            VfsUtil.saveText(created, staged.postimageText)
                        }
                    }
            }
            IntellijSessionStepResult.Completed
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            IntellijSessionStepResult.Rejected(SourceWriteFailure.SAVE_FAILED)
        }
    }

    override fun observe(): IntellijPhysicalSourceObservation = try {
        IntellijPhysicalSourceObservation.Observed(
            Files.readAllBytes(prepared.path),
            changedPaths.toSet(),
        )
    } catch (_: Exception) {
        IntellijPhysicalSourceObservation.Rejected(SourceWriteFailure.OBSERVATION_FAILED)
    }
}
