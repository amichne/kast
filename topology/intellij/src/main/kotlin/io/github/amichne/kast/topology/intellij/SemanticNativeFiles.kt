package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.call
import java.security.MessageDigest

internal typealias SemanticCapture<Value> = Refinement<Value, SemanticDependencyCaptureFailure>

internal fun captureRejected(cause: SemanticDependencyCaptureFailure) = Refinement.Rejected(cause)

/** File observations and memoized hashes live only inside one native read attempt. */
internal class SemanticNativeFiles(
    private val project: Project,
    private val limits: ReadLimits,
    private val budget: DependencyCaptureBudget,
    private val memo: SemanticNativeFileMemo = SemanticNativeFileMemo(),
) {

    fun roots(roots: List<VirtualFile>, digest: SemanticInputDigest): SemanticCapture<Unit> {
        if (roots.isEmpty()) return captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
        if (roots.size > limits[ReadLimitParameter.MODEL_CLASSPATH_ENTRIES_PER_MODULE].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        digest.text(roots.size.toString())
        for (root in roots) {
            digest.text("ROOT")
            digest.text(root.url)
            when (val observed = tree(root)) {
                is Refinement.Refined -> digest.text(observed.value.value)
                is Refinement.Rejected -> return observed
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun tree(root: VirtualFile): SemanticCapture<WorkspaceSourceContentHash> =
        memo.tree(SemanticNativeFileIdentity(root.url), budget) {
            val digest = SemanticInputDigest()
            when (
                val read =
                    walk(root) { file ->
                        digest.text("FILE")
                        digest.text(file.url)
                        when (val hash = hash(file)) {
                            is Refinement.Refined -> {
                                digest.text(hash.value.value)
                                Refinement.Refined(Unit)
                            }
                            is Refinement.Rejected -> hash
                        }
                    }
            ) {
                is Refinement.Refined -> Refinement.Refined(digest.finish())
                is Refinement.Rejected -> read
            }
        }

    fun walk(root: VirtualFile, consume: (VirtualFile) -> SemanticCapture<Unit>): SemanticCapture<Unit> {
        val pending = ArrayDeque<VirtualFile>().apply { add(root) }
        var count = 0
        while (pending.isNotEmpty()) {
            when (val spent = budget.step()) {
                is Refinement.Rejected -> return spent
                is Refinement.Refined -> Unit
            }
            if (++count > limits[ReadLimitParameter.DISCOVERY_FILES].value)
                return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
            val file = pending.removeLast()
            budget.observation.count(IntellijReadCounter.DEPENDENCY_TREE_ENTRIES_VISITED)
            when (val admitted = validate(file)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            val result = if (file.isDirectory) children(file, pending, count) else consumeClean(file, consume)
            if (result is Refinement.Rejected) return result
        }
        return Refinement.Refined(Unit)
    }

    private fun validate(file: VirtualFile): SemanticCapture<Unit> =
        when {
            !file.isValid -> captureRejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
            file.fileSystem.protocol !in setOf("file", "jar", "jrt") ->
                captureRejected(SemanticDependencyCaptureFailure.INPUT_PROVIDER_UNSUPPORTED)
            file.url.length > limits[ReadLimitParameter.MODEL_CLASSPATH_URL_CHARACTERS].value ->
                captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
            else -> Refinement.Refined(Unit)
        }

    private fun children(file: VirtualFile, pending: ArrayDeque<VirtualFile>, count: Int): SemanticCapture<Unit> {
        val children = budget.observation.call(IntellijReadCall.VFS_CHILDREN) { file.children }
        if (children.size.toLong() + pending.size + count > limits[ReadLimitParameter.DISCOVERY_FILES].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        pending.addAll(children.sortedByDescending { it.url })
        return Refinement.Refined(Unit)
    }

    private fun consumeClean(
        file: VirtualFile,
        consume: (VirtualFile) -> SemanticCapture<Unit>,
    ): SemanticCapture<Unit> {
        val documents = FileDocumentManager.getInstance()
        if (budget.observation.call(IntellijReadCall.DOCUMENT_DIRTY_CHECK) { documents.isFileModified(file) })
            return captureRejected(SemanticDependencyCaptureFailure.SOURCE_DOCUMENT_DIRTY)
        val document =
            budget.observation.call(IntellijReadCall.DOCUMENT_CACHE_LOOKUP) { documents.getCachedDocument(file) }
        if (
            document != null &&
                !budget.observation.call(IntellijReadCall.DOCUMENT_COMMIT_CHECK) {
                    PsiDocumentManager.getInstance(project).isCommitted(document)
                }
        )
            return captureRejected(SemanticDependencyCaptureFailure.SOURCE_DOCUMENT_UNCOMMITTED)
        return consume(file)
    }

    fun hash(file: VirtualFile): SemanticCapture<WorkspaceSourceContentHash> =
        memo.hash(SemanticNativeFileIdentity(file.url), budget) {
            budget.observation
                .call(IntellijReadCall.VFS_OPEN_STREAM) { file.inputStream }
                .use { input -> hashSemanticInput(input, budget) }
        }
}

/** Actual read calls (including EOF) and returned bytes survive partial capture and rejection. */
internal fun hashSemanticInput(
    input: java.io.InputStream,
    budget: DependencyCaptureBudget,
): SemanticCapture<WorkspaceSourceContentHash> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(FILE_CHUNK_BYTES)
    while (true) {
        when (val spent = budget.step()) {
            is Refinement.Rejected -> return spent
            is Refinement.Refined -> Unit
        }
        val size = budget.observation.call(IntellijReadCall.FILE_STREAM_READ) { input.read(buffer) }
        if (size < 0) break
        budget.observation.count(IntellijReadCounter.DEPENDENCY_HASH_BYTES_READ, amount = size)
        digest.update(buffer, 0, size)
    }
    budget.observation.count(IntellijReadCounter.DEPENDENCY_HASHES_COMPLETED)
    return Refinement.Refined(parsedDigest(digest))
}

private const val FILE_CHUNK_BYTES = 8192
