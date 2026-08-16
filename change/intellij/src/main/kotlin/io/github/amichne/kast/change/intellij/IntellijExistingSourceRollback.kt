package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.apply.MutationPreconditionAtIntellijBoundary
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtFile

/** Exact recovery primitive for a source that existed before mutation. */
internal class IntellijExistingSourceRollback(
    private val project: Project,
) {
    /**
     * Proof transition: `(MutationAuthority, ExistingPrecondition, ByteArray) ->
     * AddDeclarationRollbackResult`.
     *
     * RolledBack establishes that the physical file equals the exact durable recovery preimage,
     * overwriting only the authority's exact postimage. [AddDeclarationRollbackFailure] closes
     * divergent or unavailable state. Raw recovery bytes remain inside this adapter boundary.
     */
    fun rollback(
        authority: MutationAuthority,
        expected: MutationPreconditionAtIntellijBoundary.Existing,
        preimage: ByteArray,
    ): AddDeclarationRollbackResult {
        if (!preimage.contentEquals(expected.text.toByteArray(StandardCharsets.UTF_8))) {
            return rejected(AddDeclarationRollbackFailure.CONTENT_DIVERGED)
        }
        val path = Path.of(authority.source.path.value)
        val current = try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            return rejected(AddDeclarationRollbackFailure.TARGET_UNAVAILABLE)
        }
        if (current.contentEquals(preimage)) return AddDeclarationRollbackResult.RolledBack
        if (!current.contentEquals(authority.postimageBytesAtIntellijBoundary())) {
            return rejected(AddDeclarationRollbackFailure.CONTENT_DIVERGED)
        }
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
            ?: return rejected(AddDeclarationRollbackFailure.TARGET_UNAVAILABLE)
        val target = ReadAction.compute<KtFile?, RuntimeException> {
            PsiManager.getInstance(project).findFile(file) as? KtFile
        } ?: return rejected(AddDeclarationRollbackFailure.TARGET_UNAVAILABLE)
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return rejected(AddDeclarationRollbackFailure.TARGET_UNAVAILABLE)
        return try {
            onEdt {
                WriteCommandAction.writeCommandAction(project, target)
                    .withName("Kast rollback semantic change")
                    .compute<Unit, RuntimeException> {
                        document.setText(expected.text)
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    }
                FileDocumentManager.getInstance().saveDocument(document)
            }
            if (Files.readAllBytes(path).contentEquals(preimage)) {
                AddDeclarationRollbackResult.RolledBack
            } else {
                rejected(AddDeclarationRollbackFailure.WRITE_REJECTED)
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            rejected(AddDeclarationRollbackFailure.WRITE_REJECTED)
        }
    }

    private fun rejected(failure: AddDeclarationRollbackFailure) =
        AddDeclarationRollbackResult.Rejected(failure)
}
