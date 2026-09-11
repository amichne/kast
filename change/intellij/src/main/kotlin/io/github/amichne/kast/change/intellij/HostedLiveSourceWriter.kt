package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import io.github.amichne.kast.change.apply.LiveAppliedSourceWrite
import io.github.amichne.kast.change.apply.LiveMutationAuthority
import io.github.amichne.kast.change.apply.LiveSourceWriteResult
import io.github.amichne.kast.change.apply.MutationDurabilityBarrier
import io.github.amichne.kast.change.apply.SourceWriteFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPreWriteObservation
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.psi.KtFile

/** Live authority adapter around the shared document mutation, durability, and explicit-save protocol. */
class HostedLiveSourceWriter(private val project: Project) {
    fun write(
        authority: LiveMutationAuthority,
        observation: HostedPreWriteObservation,
        durability: MutationDurabilityBarrier,
    ): LiveSourceWriteResult {
        if (observation.reference != authority.plan.basis.observation.reference) {
            observation.close()
            return LiveSourceWriteResult.RejectedBeforeMutation(SourceWriteFailure.PREIMAGE_CHANGED)
        }
        observation.use {
            val input =
                when (
                    val projected =
                        projectIntellijMutationInput(
                            path = authority.source.path.value,
                            preimage = authority.preimageTextAtIntellijBoundary(),
                            postimage = authority.postimageTextAtIntellijBoundary(),
                            sourceMutations = authority.mutationsAtIntellijBoundary(),
                        )
                ) {
                    is Refinement.Refined -> projected.value
                    is Refinement.Rejected -> return LiveSourceWriteResult.RejectedBeforeMutation(projected.failure)
                }
            val prepared =
                ReadAction.computeBlocking<IntellijSourcePreparation, RuntimeException> { prepare(authority, input) }
            return when (prepared) {
                is IntellijSourcePreparation.Rejected -> LiveSourceWriteResult.RejectedBeforeMutation(prepared.failure)
                is IntellijSourcePreparation.Ready ->
                    execute(
                        authority = authority,
                        observation = observation,
                        durability = durability,
                        input = input,
                        prepared = prepared,
                    )
            }
        }
    }

    private fun execute(
        authority: LiveMutationAuthority,
        observation: HostedPreWriteObservation,
        durability: MutationDurabilityBarrier,
        input: IntellijMutationInput,
        prepared: IntellijSourcePreparation.Ready,
    ): LiveSourceWriteResult {
        val paths = ConcurrentHashMap.newKeySet<String>()
        val lifetime = Disposer.newDisposable("kast-live-source-write")
        val files = FileDocumentManager.getInstance()
        EditorFactory.getInstance()
            .eventMulticaster
            .addDocumentListener(
                object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        files.getFile(event.document)?.path?.let(paths::add)
                    }
                },
                lifetime,
            )
        return try {
            when (
                val result =
                    IntellijSourceWriteProtocol()
                        .execute(
                            input,
                            durability,
                            LiveIntellijDocumentSession(
                                project = project,
                                prepared = prepared,
                                input = input,
                                changedPaths = paths,
                                freshness = IntellijWriteFreshness.Hosted(observation),
                            ),
                        )
            ) {
                is IntellijWriteProtocolResult.Applied ->
                    when (val observed = LiveAppliedSourceWrite.observe(authority, result.bytes, result.changedPaths)) {
                        is Refinement.Refined -> LiveSourceWriteResult.Applied(observed.value)
                        is Refinement.Rejected ->
                            LiveSourceWriteResult.RecoveryRequired(SourceWriteFailure.OBSERVATION_FAILED)
                    }
                is IntellijWriteProtocolResult.RejectedBeforeMutation ->
                    LiveSourceWriteResult.RejectedBeforeMutation(result.failure)
                is IntellijWriteProtocolResult.RejectedAfterRollback ->
                    LiveSourceWriteResult.RejectedAfterRollback(result.failure)
                is IntellijWriteProtocolResult.RecoveryRequired ->
                    LiveSourceWriteResult.RecoveryRequired(result.failure)
            }
        } finally {
            Disposer.dispose(lifetime)
        }
    }

    private fun prepare(authority: LiveMutationAuthority, input: IntellijMutationInput): IntellijSourcePreparation {
        if (project.isDisposed) return rejected(SourceWriteFailure.TARGET_INVALIDATED)
        val file =
            LocalFileSystem.getInstance().findFileByNioFile(Path.of(input.sourcePath))
                ?: return rejected(SourceWriteFailure.TARGET_NOT_FOUND)
        if (!file.isValid) return rejected(SourceWriteFailure.TARGET_INVALIDATED)
        if (!file.isWritable) return rejected(SourceWriteFailure.TARGET_READ_ONLY)
        val target =
            PsiManager.getInstance(project).findFile(file) as? KtFile
                ?: return rejected(SourceWriteFailure.TARGET_NOT_KOTLIN)
        val document =
            FileDocumentManager.getInstance().getDocument(file)
                ?: return rejected(SourceWriteFailure.DOCUMENT_UNAVAILABLE)
        if (document.text != input.preimageText) return rejected(SourceWriteFailure.PREIMAGE_CHANGED)
        val range = authority.plan.target.range
        if (
            target.declarations.count {
                it.textRange.startOffset == range.startInclusive && it.textRange.endOffset == range.endExclusive
            } != 1
        ) {
            return rejected(SourceWriteFailure.TARGET_INVALIDATED)
        }
        return IntellijSourcePreparation.Ready(file, target, document)
    }

    private fun rejected(failure: SourceWriteFailure) = IntellijSourcePreparation.Rejected(failure)
}
