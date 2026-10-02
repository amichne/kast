package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import java.nio.file.Path
import org.jetbrains.kotlin.idea.KotlinFileType

/** Cheap path, ownership, source-root and file-type proofs precede inventory and compiler refinement. */
internal class IntellijDeclarationSourceAdmission(
    private val scope: CompiledIntellijSearchScope,
    private val request: SymbolDiscoveryRequest,
) {
    private val root = Path.of(request.scope.lease.workspaceRoot.value)
    private val requestedDirectory = request.constraints.directory?.let { root.resolve(it.directory.value).normalize() }
    private val selectedRoots by lazy { scope.sourceRoots.map { Path.of(it.sourceRoot.value) }.toSet() }
    private val ownershipRoots by lazy { scope.ownershipRoots.map { Path.of(it.sourceRoot.value) }.toSet() }

    fun relevantDirectory(path: Path): Boolean {
        val requested = requestedDirectory
        if (requested != null && !path.startsWith(requested) && !requested.startsWith(path)) return false
        if (excludedDescendantDirectory(path)) return false
        val owner = generateSequence(path) { it.parent }.firstOrNull { it in ownershipRoots }
        return owner == null || owner in selectedRoots || selectedRoots.any { it.startsWith(path) }
    }

    private fun excludedDescendantDirectory(path: Path): Boolean {
        val requested = requestedDirectory ?: return false
        if (request.constraints.directory!!.containment != SymbolDiscoveryContainment.DIRECT) return false
        return path.startsWith(requested) && path != requested
    }

    private fun relevantFile(path: Path): Boolean =
        requestedDirectory == null ||
            when (request.constraints.directory!!.containment) {
                SymbolDiscoveryContainment.DIRECT -> path.parent == requestedDirectory
                SymbolDiscoveryContainment.DESCENDANTS -> path.startsWith(requestedDirectory)
            }

    fun detach(file: VirtualFile): SemanticFilePartition? {
        val path = Path.of(file.path)
        if (!admitted(file, path)) return null
        val identity =
            if (path == root && file.isDirectory) identity(path, file.url)
            else
                when (
                    val parsed =
                        SymbolDiscoveryFileIdentity.fromBoundary(request.scope.lease.workspaceRoot, path, file.url)
                ) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return null
                }
        return if (file.isDirectory) SemanticFilePartition.Directory(identity) else SemanticFilePartition.File(identity)
    }

    private fun admitted(file: VirtualFile, path: Path): Boolean {
        if (file.isDirectory) return relevantDirectory(path)
        return relevantFile(path) && file.fileType == KotlinFileType.INSTANCE && scope.nativeScope.contains(file)
    }

    fun identity(path: Path, url: String): SymbolDiscoveryFileIdentity =
        if (path == root) SymbolDiscoveryFileIdentity.External(DetachedVirtualFileUrl.parse(url).discoveryRefined())
        else
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(request.scope.lease.workspaceRoot, path).discoveryRefined()
            )
}
