package io.github.amichne.kast.fixtureprobe

import com.intellij.idea.ActionsBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

internal class NativeFixtureProbeExecution(
    private val project: Project,
    private val gate: ProbeSandbox,
    private val controls: NativeFixtureProbeControls,
) {
    fun execute(request: ProbeRequest): ProbeExecution =
        try {
            if (!gate.valid(project)) ProbeExecution.Rejected(ProbeFailure.SANDBOX_REJECTED)
            else
                when (val loaded = load(request)) {
                    is ProbeResult.Rejected -> ProbeExecution.Rejected(loaded.failure)
                    is ProbeResult.Accepted -> executeLoaded(loaded.value, request)
                }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            ProbeExecution.Rejected(ProbeFailure.NATIVE_UNAVAILABLE)
        }

    private fun executeLoaded(target: ProbeTarget, request: ProbeRequest): ProbeExecution {
        when (val admission = validate(target, request)) {
            is ProbeResult.Accepted -> Unit
            is ProbeResult.Rejected -> return ProbeExecution.Rejected(admission.failure)
        }
        if (
            request.command == ProbeCommand.OBSERVE ||
                request.command == ProbeCommand.AWAIT_SETUP_READY ||
                request.command == ProbeCommand.AWAIT_REOPEN_READY
        )
            return when (val observed = evidence(target)) {
                is ProbeResult.Accepted -> ProbeExecution.Completed(observed.value)
                is ProbeResult.Rejected -> ProbeExecution.Rejected(observed.failure)
            }
        if (!gate.valid(project)) return ProbeExecution.Rejected(ProbeFailure.SANDBOX_REJECTED)
        if (
            request.command == ProbeCommand.ARM_POST_SAVE_BARRIER ||
                request.command == ProbeCommand.UNLOAD_PRODUCTION_PLUGIN
        ) {
            return executeControl(target, request)
        }
        return try {
            when (val mutated = mutate(target, request)) {
                is ProbeResult.Accepted -> completedEffect(target, request)
                is ProbeResult.Rejected -> ProbeExecution.EffectUncertain(mutated.failure)
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            ProbeExecution.EffectUncertain(ProbeFailure.NATIVE_UNAVAILABLE)
        }
    }

    private fun executeControl(target: ProbeTarget, request: ProbeRequest): ProbeExecution {
        val observed =
            when (val result = evidence(target)) {
                is ProbeResult.Accepted -> result.value
                is ProbeResult.Rejected -> return ProbeExecution.Rejected(result.failure)
            }
        return controls.execute(request, target.document, observed)
    }

    private fun mutate(target: ProbeTarget, request: ProbeRequest): ProbeResult<Unit> {
        when (request.command) {
            ProbeCommand.AWAIT_SETUP_READY,
            ProbeCommand.AWAIT_REOPEN_READY,
            ProbeCommand.OBSERVE,
            ProbeCommand.ARM_POST_SAVE_BARRIER,
            ProbeCommand.UNLOAD_PRODUCTION_PLUGIN -> return ProbeResult.Rejected(ProbeFailure.UNKNOWN_COMMAND)
            ProbeCommand.DIRTY_UNCOMMITTED ->
                WriteCommandAction.runWriteCommandAction(
                    project,
                    FIXTURE_COMMAND,
                    null,
                    Runnable { target.document.insertString(target.document.textLength, DIRTY_MARKER) },
                )
            ProbeCommand.COMMIT_DOCUMENT -> PsiDocumentManager.getInstance(project).commitDocument(target.document)
            ProbeCommand.RESTORE_SAVED -> {
                WriteCommandAction.runWriteCommandAction(
                    project,
                    FIXTURE_COMMAND,
                    null,
                    Runnable {
                        target.document.setText(target.savedText)
                        PsiDocumentManager.getInstance(project).commitDocument(target.document)
                    },
                )
                FileDocumentManager.getInstance().saveDocumentAsIs(target.document)
            }
            ProbeCommand.UNDO_PRODUCTION_CHANGE -> {
                UndoManager.getInstance(project).undo(target.editor)
                if (digest(target.document.text) != request.images.preimage)
                    return ProbeResult.Rejected(ProbeFailure.UNDO_IMAGE_MISMATCH)
                PsiDocumentManager.getInstance(project).commitDocument(target.document)
                FileDocumentManager.getInstance().saveDocumentAsIs(target.document)
            }
        }
        return ProbeResult.Accepted(Unit)
    }

    private fun completedEffect(target: ProbeTarget, request: ProbeRequest): ProbeExecution {
        val observed =
            when (val result = evidence(target)) {
                is ProbeResult.Accepted -> result.value
                is ProbeResult.Rejected -> return ProbeExecution.EffectUncertain(result.failure)
            }
        val expected =
            if (request.command == ProbeCommand.UNDO_PRODUCTION_CHANGE) request.images.preimage
            else request.expectedCurrentSaved
        if (observed.saved != expected) return ProbeExecution.EffectUncertain(ProbeFailure.SAVE_REJECTED)
        val state =
            when (request.command) {
                ProbeCommand.DIRTY_UNCOMMITTED -> ProbeDocumentState.DIRTY_UNCOMMITTED
                ProbeCommand.COMMIT_DOCUMENT -> ProbeDocumentState.DIRTY_COMMITTED
                ProbeCommand.RESTORE_SAVED,
                ProbeCommand.UNDO_PRODUCTION_CHANGE,
                ProbeCommand.AWAIT_SETUP_READY,
                ProbeCommand.AWAIT_REOPEN_READY,
                ProbeCommand.OBSERVE,
                ProbeCommand.ARM_POST_SAVE_BARRIER,
                ProbeCommand.UNLOAD_PRODUCTION_PLUGIN -> ProbeDocumentState.SAVED_COMMITTED
            }
        return if (observed.documentState == state) ProbeExecution.Completed(observed)
        else ProbeExecution.EffectUncertain(ProbeFailure.DOCUMENT_STATE_REJECTED)
    }

    private fun load(request: ProbeRequest): ProbeResult<ProbeTarget> {
        val path = gate.project.resolve("src/main/kotlin/Fixture.kt")
        if (!Files.isRegularFile(path) || path.toRealPath() != path)
            return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
        val bytes =
            when (val observed = boundedBytes(path)) {
                is ProbeResult.Accepted -> observed.value
                is ProbeResult.Rejected -> return observed
            }
        if (ProbeDigest.observe(bytes) != request.expectedCurrentSaved)
            return ProbeResult.Rejected(ProbeFailure.SAVED_IMAGE_CHANGED)
        val text =
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                return ProbeResult.Rejected(ProbeFailure.SOURCE_ENCODING_REJECTED)
            }
        val file =
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
        val document =
            FileDocumentManager.getInstance().getDocument(file)
                ?: return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
        val editor =
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), false)
                ?: return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
        val fileEditor =
            FileEditorManager.getInstance(project).getAllEditors(file).firstOrNull {
                it is com.intellij.openapi.fileEditor.TextEditor && it.editor === editor
            } ?: return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
        return ProbeResult.Accepted(
            ProbeTarget(path = path, document = document, editor = fileEditor, savedText = text)
        )
    }

    private fun validate(target: ProbeTarget, request: ProbeRequest): ProbeResult<Unit> {
        val state = documentState(target.document)
        return when (request.command) {
            ProbeCommand.OBSERVE,
            ProbeCommand.AWAIT_SETUP_READY,
            ProbeCommand.AWAIT_REOPEN_READY -> ProbeResult.Accepted(Unit)
            ProbeCommand.DIRTY_UNCOMMITTED,
            ProbeCommand.UNDO_PRODUCTION_CHANGE,
            ProbeCommand.ARM_POST_SAVE_BARRIER,
            ProbeCommand.UNLOAD_PRODUCTION_PLUGIN ->
                when {
                    target.document.text != target.savedText ->
                        ProbeResult.Rejected(ProbeFailure.DOCUMENT_IMAGE_CHANGED)
                    state != ProbeDocumentState.SAVED_COMMITTED ->
                        ProbeResult.Rejected(ProbeFailure.DOCUMENT_STATE_REJECTED)
                    request.command == ProbeCommand.UNDO_PRODUCTION_CHANGE -> validateUndo(target.editor)
                    else -> ProbeResult.Accepted(Unit)
                }
            ProbeCommand.COMMIT_DOCUMENT,
            ProbeCommand.RESTORE_SAVED ->
                when {
                    target.document.text != target.savedText + DIRTY_MARKER ->
                        ProbeResult.Rejected(ProbeFailure.DOCUMENT_IMAGE_CHANGED)
                    state != ProbeDocumentState.DIRTY_UNCOMMITTED && state != ProbeDocumentState.DIRTY_COMMITTED ->
                        ProbeResult.Rejected(ProbeFailure.DOCUMENT_STATE_REJECTED)
                    else -> ProbeResult.Accepted(Unit)
                }
        }
    }

    private fun validateUndo(editor: FileEditor): ProbeResult<Unit> {
        val manager = UndoManager.getInstance(project)
        return when {
            !manager.isUndoAvailable(editor) -> ProbeResult.Rejected(ProbeFailure.UNDO_UNAVAILABLE)
            undoState(editor) != ProbeUndoState.PRODUCTION_CHANGE ->
                ProbeResult.Rejected(ProbeFailure.UNDO_COMMAND_MISMATCH)
            manager.isNextUndoAskConfirmation(editor) -> ProbeResult.Rejected(ProbeFailure.UNDO_CONFIRMATION_REQUIRED)
            else -> ProbeResult.Accepted(Unit)
        }
    }

    private fun evidence(target: ProbeTarget): ProbeResult<ProbeEvidence> {
        val bytes =
            when (val observed = boundedBytes(target.path)) {
                is ProbeResult.Accepted -> observed.value
                is ProbeResult.Rejected -> return observed
            }
        val state = documentState(target.document)
        val committed = PsiDocumentManager.getInstance(project).isCommitted(target.document)
        val psi =
            if (committed) PsiDocumentManager.getInstance(project).getPsiFile(target.document) as? KtFile else null
        if (committed && psi == null) return ProbeResult.Rejected(ProbeFailure.PSI_UNAVAILABLE)
        val declarations = mutableListOf<ProbeDeclaration>()
        if (psi != null && !collect(psi.declarations, "", declarations))
            return ProbeResult.Rejected(ProbeFailure.PSI_LIMIT_EXCEEDED)
        return ProbeResult.Accepted(
            ProbeEvidence(
                saved = ProbeDigest.observe(bytes),
                document = digest(target.document.text),
                documentState = state,
                syntax =
                    if (psi == null) ProbeSyntaxState.UNCOMMITTED
                    else if (PsiTreeUtil.hasErrorElements(psi)) ProbeSyntaxState.ERRORS else ProbeSyntaxState.CLEAN,
                undo = undoState(target.editor),
                declarations = declarations,
            )
        )
    }

    private fun undoState(editor: FileEditor): ProbeUndoState {
        val manager = UndoManager.getInstance(project)
        if (!manager.isUndoAvailable(editor)) return ProbeUndoState.UNAVAILABLE
        val label = manager.getUndoActionNameAndDescription(editor)
        return if (
            label.first == ActionsBundle.message("action.undo.text", PRODUCTION_COMMAND).trim() &&
                label.second == ActionsBundle.message("action.undo.description", PRODUCTION_COMMAND).trim()
        )
            ProbeUndoState.PRODUCTION_CHANGE
        else ProbeUndoState.OTHER
    }

    private fun documentState(document: Document): ProbeDocumentState =
        ProbeDocumentState.observe(
            FileDocumentManager.getInstance().isDocumentUnsaved(document),
            PsiDocumentManager.getInstance(project).isCommitted(document),
        )
}

private data class ProbeTarget(val path: Path, val document: Document, val editor: FileEditor, val savedText: String)

private fun digest(text: String): ProbeDigest = ProbeDigest.observe(text.toByteArray(StandardCharsets.UTF_8))

private fun boundedBytes(path: Path): ProbeResult<ByteArray> =
    Files.newInputStream(path).use { source ->
        val bytes = source.readNBytes(MAXIMUM_SOURCE_BYTES + 1)
        if (bytes.size <= MAXIMUM_SOURCE_BYTES) ProbeResult.Accepted(bytes)
        else ProbeResult.Rejected(ProbeFailure.SOURCE_TOO_LARGE)
    }

private fun collect(source: List<KtDeclaration>, container: String, output: MutableList<ProbeDeclaration>): Boolean {
    for (declaration in source) {
        val name = declaration.name ?: return false
        if (
            name.length > MAXIMUM_NAME_LENGTH ||
                container.length > MAXIMUM_CONTAINER_LENGTH ||
                output.size >= MAXIMUM_DECLARATIONS
        )
            return false
        val kind = declarationKind(declaration)
        output += ProbeDeclaration(name, container, kind)
        if (
            declaration is KtClassOrObject &&
                !collect(declaration.declarations, if (container.isEmpty()) name else "$container.$name", output)
        )
            return false
    }
    return true
}

private fun declarationKind(declaration: KtDeclaration): ProbeDeclarationKind =
    when (declaration) {
        is KtClass -> ProbeDeclarationKind.CLASS
        is KtObjectDeclaration -> ProbeDeclarationKind.OBJECT
        is KtNamedFunction -> ProbeDeclarationKind.FUNCTION
        is KtProperty -> ProbeDeclarationKind.PROPERTY
        is KtTypeAlias -> ProbeDeclarationKind.TYPE_ALIAS
        else -> ProbeDeclarationKind.OTHER
    }

private const val MAXIMUM_NAME_LENGTH = 128
private const val MAXIMUM_CONTAINER_LENGTH = 512
