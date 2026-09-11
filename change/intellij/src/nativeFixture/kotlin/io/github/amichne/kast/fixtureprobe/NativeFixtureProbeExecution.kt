package io.github.amichne.kast.fixtureprobe

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
import com.intellij.openapi.vfs.VirtualFile
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
    fun prepareSavedMutation(request: ProbeRequest): ProbeSavePreparation =
        try {
            when {
                request.command !in setOf(ProbeCommand.RESTORE_SAVED, ProbeCommand.UNDO_PRODUCTION_CHANGE) ->
                    ProbeSavePreparation.Resolved(ProbeExecution.Rejected(ProbeFailure.UNKNOWN_COMMAND))
                !gate.valid(project) ->
                    ProbeSavePreparation.Resolved(ProbeExecution.Rejected(ProbeFailure.SANDBOX_REJECTED))
                else ->
                    when (val loaded = load(request)) {
                        is ProbeResult.Rejected ->
                            ProbeSavePreparation.Resolved(ProbeExecution.Rejected(loaded.failure))
                        is ProbeResult.Accepted -> prepareLoadedSave(loaded.value, request)
                    }
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            ProbeSavePreparation.Resolved(ProbeExecution.EffectUncertain(ProbeFailure.NATIVE_UNAVAILABLE))
        }

    private fun prepareLoadedSave(target: ProbeTarget, request: ProbeRequest): ProbeSavePreparation {
        when (val admission = validate(target, request)) {
            is ProbeResult.Accepted -> Unit
            is ProbeResult.Rejected -> return ProbeSavePreparation.Resolved(ProbeExecution.Rejected(admission.failure))
        }
        if (!gate.valid(project))
            return ProbeSavePreparation.Resolved(ProbeExecution.Rejected(ProbeFailure.SANDBOX_REJECTED))
        when (val mutated = mutate(target, request)) {
            is ProbeResult.Accepted -> Unit
            is ProbeResult.Rejected ->
                return ProbeSavePreparation.Resolved(ProbeExecution.EffectUncertain(mutated.failure))
        }
        val document =
            when (val observed = documentEvidence(target)) {
                is ProbeResult.Accepted -> observed.value
                is ProbeResult.Rejected ->
                    return ProbeSavePreparation.Resolved(ProbeExecution.EffectUncertain(observed.failure))
            }
        val expected =
            if (request.command == ProbeCommand.UNDO_PRODUCTION_CHANGE) request.images.preimage
            else request.expectedCurrentSaved
        return ProbeSavePreparation.Pending(
            ProbePendingSave(file = target.file, path = target.path, expected = expected, document = document)
        )
    }

    fun execute(request: ProbeRequest): ProbeExecution =
        try {
            if (request.command in setOf(ProbeCommand.RESTORE_SAVED, ProbeCommand.UNDO_PRODUCTION_CHANGE))
                ProbeExecution.Rejected(ProbeFailure.UNKNOWN_COMMAND)
            else if (!gate.valid(project)) ProbeExecution.Rejected(ProbeFailure.SANDBOX_REJECTED)
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
        if (!gate.valid(project)) return ProbeExecution.Rejected(ProbeFailure.SANDBOX_REJECTED)
        return when (request.command.nativeOperation) {
            ProbeNativeOperation.OBSERVE ->
                when (val observed = evidence(target)) {
                    is ProbeResult.Accepted -> ProbeExecution.Completed(observed.value)
                    is ProbeResult.Rejected -> ProbeExecution.Rejected(observed.failure)
                }
            ProbeNativeOperation.CONTROL -> executeControl(target, request)
            ProbeNativeOperation.MUTATE -> executeMutation(target, request)
        }
    }

    private fun executeMutation(target: ProbeTarget, request: ProbeRequest): ProbeExecution =
        try {
            when (val mutated = mutate(target, request)) {
                is ProbeResult.Accepted -> completedEffect(target, request)
                is ProbeResult.Rejected -> ProbeExecution.EffectUncertain(mutated.failure)
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            ProbeExecution.EffectUncertain(ProbeFailure.NATIVE_UNAVAILABLE)
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
            ProbeCommand.HOLD_INDEXING,
            ProbeCommand.RELEASE_INDEXING,
            ProbeCommand.REIMPORT_GRADLE,
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
                when (
                    val observed =
                        verifyProbeUndoDocument(
                            expected = request.images.preimage,
                            observed = digest(target.document.text),
                            record = ::logProbeUndoDocumentObservation,
                        )
                ) {
                    is ProbeResult.Accepted -> Unit
                    is ProbeResult.Rejected -> return observed
                }
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
                ProbeCommand.HOLD_INDEXING,
                ProbeCommand.RELEASE_INDEXING,
                ProbeCommand.REIMPORT_GRADLE,
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
            ProbeTarget(path = path, file = file, document = document, editor = fileEditor, savedText = text)
        )
    }

    private fun validate(target: ProbeTarget, request: ProbeRequest): ProbeResult<Unit> {
        val state = documentState(target.document)
        return when (request.command) {
            ProbeCommand.OBSERVE,
            ProbeCommand.HOLD_INDEXING,
            ProbeCommand.RELEASE_INDEXING,
            ProbeCommand.REIMPORT_GRADLE,
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
                    request.command == ProbeCommand.UNDO_PRODUCTION_CHANGE -> validateProbeUndo(project, target.editor)
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

    private fun evidence(target: ProbeTarget): ProbeResult<ProbeEvidence> {
        val bytes =
            when (val observed = boundedBytes(target.path)) {
                is ProbeResult.Accepted -> observed.value
                is ProbeResult.Rejected -> return observed
            }
        return when (val document = documentEvidence(target)) {
            is ProbeResult.Accepted -> ProbeResult.Accepted(document.value.withSaved(ProbeDigest.observe(bytes)))
            is ProbeResult.Rejected -> document
        }
    }

    private fun documentEvidence(target: ProbeTarget): ProbeResult<ProbeDocumentEvidence> {
        val state = documentState(target.document)
        val committed = PsiDocumentManager.getInstance(project).isCommitted(target.document)
        val psi =
            if (committed) PsiDocumentManager.getInstance(project).getPsiFile(target.document) as? KtFile else null
        if (committed && psi == null) return ProbeResult.Rejected(ProbeFailure.PSI_UNAVAILABLE)
        val declarations = mutableListOf<ProbeDeclaration>()
        if (psi != null && !collect(psi.declarations, "", declarations))
            return ProbeResult.Rejected(ProbeFailure.PSI_LIMIT_EXCEEDED)
        return ProbeResult.Accepted(
            ProbeDocumentEvidence(
                document = digest(target.document.text),
                documentState = state,
                syntax =
                    if (psi == null) ProbeSyntaxState.UNCOMMITTED
                    else if (PsiTreeUtil.hasErrorElements(psi)) ProbeSyntaxState.ERRORS else ProbeSyntaxState.CLEAN,
                undo = probeUndoState(project, target.editor),
                declarations = declarations,
            )
        )
    }

    private fun documentState(document: Document): ProbeDocumentState =
        ProbeDocumentState.observe(
            FileDocumentManager.getInstance().isDocumentUnsaved(document),
            PsiDocumentManager.getInstance(project).isCommitted(document),
        )
}

private data class ProbeTarget(
    val path: Path,
    val file: VirtualFile,
    val document: Document,
    val editor: FileEditor,
    val savedText: String,
)

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

private enum class ProbeNativeOperation {
    OBSERVE,
    CONTROL,
    MUTATE,
}

private val ProbeCommand.nativeOperation: ProbeNativeOperation
    get() =
        when (this) {
            ProbeCommand.OBSERVE,
            ProbeCommand.AWAIT_SETUP_READY,
            ProbeCommand.AWAIT_REOPEN_READY,
            ProbeCommand.HOLD_INDEXING,
            ProbeCommand.RELEASE_INDEXING,
            ProbeCommand.REIMPORT_GRADLE -> ProbeNativeOperation.OBSERVE
            ProbeCommand.ARM_POST_SAVE_BARRIER,
            ProbeCommand.UNLOAD_PRODUCTION_PLUGIN -> ProbeNativeOperation.CONTROL
            ProbeCommand.DIRTY_UNCOMMITTED,
            ProbeCommand.COMMIT_DOCUMENT,
            ProbeCommand.RESTORE_SAVED,
            ProbeCommand.UNDO_PRODUCTION_CHANGE -> ProbeNativeOperation.MUTATE
        }
