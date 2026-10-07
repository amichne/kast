package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.security.MessageDigest

internal typealias SemanticCapture<Value> = Refinement<Value, SemanticDependencyCaptureFailure>

internal fun captureRejected(cause: SemanticDependencyCaptureFailure) = Refinement.Rejected(cause)

/** File observations and memoized hashes live only inside one native read attempt. */
internal class SemanticNativeFiles(
    private val project: Project,
    private val limits: ReadLimits,
    private val budget: DependencyCaptureBudget,
) {
    private val hashes = mutableMapOf<String, WorkspaceSourceContentHash>()
    private val trees = mutableMapOf<String, WorkspaceSourceContentHash>()

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

    private fun tree(root: VirtualFile): SemanticCapture<WorkspaceSourceContentHash> {
        trees[root.url]?.let {
            return Refinement.Refined(it)
        }
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
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return read
        }
        return Refinement.Refined(digest.finish().also { trees[root.url] = it })
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
        val children = file.children
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
        if (documents.isFileModified(file))
            return captureRejected(SemanticDependencyCaptureFailure.SOURCE_DOCUMENT_DIRTY)
        val document = documents.getCachedDocument(file)
        if (document != null && !PsiDocumentManager.getInstance(project).isCommitted(document))
            return captureRejected(SemanticDependencyCaptureFailure.SOURCE_DOCUMENT_UNCOMMITTED)
        return consume(file)
    }

    fun hash(file: VirtualFile): SemanticCapture<WorkspaceSourceContentHash> {
        hashes[file.url]?.let {
            return Refinement.Refined(it)
        }
        if (hashes.size >= limits[ReadLimitParameter.DISCOVERY_FILES].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream.use { input ->
            val buffer = ByteArray(FILE_CHUNK_BYTES)
            while (true) {
                when (val spent = budget.step()) {
                    is Refinement.Rejected -> return spent
                    is Refinement.Refined -> Unit
                }
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return Refinement.Refined(parsedDigest(digest).also { hashes[file.url] = it })
    }
}

private const val FILE_CHUNK_BYTES = 8192
