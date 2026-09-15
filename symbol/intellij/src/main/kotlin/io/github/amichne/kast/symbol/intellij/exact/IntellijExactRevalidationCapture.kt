package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.workspace.contract.*
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import java.security.MessageDigest

/** Request-local capture deduplicates file reads; no platform object is retained in its entries. */
class IntellijExactRevalidationCapture(
    private val model: WorkspaceSearchScopeModel,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
    private val workLimit: Long = 256,
    private val limits: io.github.amichne.kast.kernel.ReadLimits = io.github.amichne.kast.kernel.ReadLimits.Default,
) {
    private data class Capture(val owner: ModelOwnedSourceRoot, val identity: ExactRevalidationTextIdentity)

    private val entries = mutableMapOf<SymbolDiscoveryFileIdentity, Refinement<Capture, ExactRevalidationRejection>>()
    private var work = 0L

    internal fun capture(file: SymbolDiscoveryFileIdentity, psi: PsiFile) {
        if (entries.containsKey(file)) return
        val result =
            try {
                read(file, psi)
            } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) {
                throw cancelled
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                reject(ExactRevalidationRejection.CAPTURE_UNAVAILABLE)
            } catch (failure: RuntimeException) {
                observation.unexpected(
                    io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure.capture(
                        io.github.amichne.kast.workspace.intellij.read.IntellijReadStage.EXACT_REFINEMENT,
                        failure,
                        limits,
                    )
                )
                reject(ExactRevalidationRejection.CAPTURE_UNAVAILABLE)
            }
        if (entries.size < MAX_FILES) entries[file] = result
        if (result is Refinement.Rejected) observation.count(IntellijReadCounter.REVALIDATION_CAPTURE_REJECTED)
    }

    fun locator(selector: SymbolSelector): Refinement<ExactRevalidationLocator, ExactRevalidationRejection> =
        when (val captured = entries[selector.file]) {
            null -> Refinement.Rejected(ExactRevalidationRejection.CAPTURE_UNAVAILABLE)
            is Refinement.Rejected -> captured
            is Refinement.Refined ->
                ExactRevalidationLocator.capture(selector, captured.value.owner, captured.value.identity)
        }

    internal fun check(locator: ExactRevalidationLocator, psi: PsiFile): Refinement<Unit, ExactRevalidationRejection> =
        when (val current = read(locator.evidence.file, psi)) {
            is Refinement.Rejected -> current
            is Refinement.Refined ->
                when {
                    current.value.owner != locator.owner ->
                        Refinement.Rejected(ExactRevalidationRejection.OWNER_MISMATCH)
                    current.value.identity != locator.text ->
                        Refinement.Rejected(ExactRevalidationRejection.CONTENT_CHANGED)
                    else -> {
                        entries[locator.evidence.file] = current
                        Refinement.Refined(Unit)
                    }
                }
        }

    private fun read(file: SymbolDiscoveryFileIdentity, psi: PsiFile): Refinement<Capture, ExactRevalidationRejection> {
        if (file !is SymbolDiscoveryFileIdentity.Workspace)
            return reject(ExactRevalidationRejection.UNSUPPORTED_DECLARATION)
        if (entries.size >= MAX_FILES) return reject(ExactRevalidationRejection.CAPACITY)
        val path = Path.of(file.path.value)
        val owners = model.sourceRoots.filter { path.startsWith(Path.of(it.sourceRoot.value)) }
        if (owners.size != 1) return reject(ExactRevalidationRejection.OWNER_MISMATCH)
        val owner = owners.single()
        if (owner.provenance != WorkspaceSourceRootProvenance.AUTHORED)
            return reject(ExactRevalidationRejection.UNSUPPORTED_DECLARATION)
        val virtual = psi.virtualFile ?: return reject(ExactRevalidationRejection.DECLARATION_MISSING)
        val documents = FileDocumentManager.getInstance()
        val document = documents.getCachedDocument(virtual)
        if (
            documents.isFileModified(virtual) ||
                document != null && !PsiDocumentManager.getInstance(psi.project).isCommitted(document)
        )
            return reject(ExactRevalidationRejection.CONTENT_UNCOMMITTED)
        val size = virtual.length
        // Reserve room for the one-byte EOF probe. Only actual consumed bytes are charged below.
        val maximumCost = 1 + (size + CHUNK_BYTES) / CHUNK_BYTES
        if (
            size < 0 ||
                size > MAX_FILE_BYTES ||
                psi.textLength > MAX_FILE_BYTES ||
                document != null && document.textLength > MAX_FILE_BYTES ||
                maximumCost > workLimit - work
        )
            return reject(ExactRevalidationRejection.CAPACITY)
        work++
        observation.count(IntellijReadCounter.REVALIDATION_WORK_CHARGED)
        var consumedBytes = 0
        val bytes =
            when (
                val read =
                    virtual.inputStream.use { stream ->
                        readExactSavedBytes(
                            stream,
                            size.toInt(),
                            bytesRead = { count ->
                                val previousUnits = (consumedBytes + CHUNK_BYTES - 1) / CHUNK_BYTES
                                consumedBytes += count
                                val charged = (consumedBytes + CHUNK_BYTES - 1) / CHUNK_BYTES - previousUnits
                                work += charged
                                if (charged > 0)
                                    observation.count(IntellijReadCounter.REVALIDATION_WORK_CHARGED, amount = charged)
                            },
                            cancellationCheck = ProgressManager::checkCanceled,
                        )
                    }
            ) {
                is ExactSavedBytesRead.Read -> read.bytes
                is ExactSavedBytesRead.Rejected -> return reject(read.reason)
            }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        observation.count(IntellijReadCounter.REVALIDATION_FILES_HASHED)
        observation.count(IntellijReadCounter.REVALIDATION_BYTES_HASHED, amount = bytes.size)
        // Charset overload performs IntelliJ BOM/newline conversion without mutating VirtualFile charset metadata.
        val saved = LoadTextUtil.getTextByBinaryPresentation(bytes, virtual.charset)
        when (val matched = admitExactSavedPsiText(saved, psi.text, document?.immutableCharSequence)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return matched
        }
        return when (val identity = ExactRevalidationTextIdentity.parse(hash)) {
            is Refinement.Refined -> Refinement.Refined(Capture(owner, identity.value))
            is Refinement.Rejected -> identity
        }
    }

    companion object {
        const val MAX_FILES = 64
        const val MAX_FILE_BYTES = 1024 * 1024
        const val CHUNK_BYTES = 4096
    }
}

private fun reject(reason: ExactRevalidationRejection): Refinement.Rejected<ExactRevalidationRejection> =
    Refinement.Rejected(reason)
