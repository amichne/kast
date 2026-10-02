package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import java.nio.file.Path

internal sealed interface IntellijModelPathPolicy {
    fun contains(path: Path): Boolean

    data class ExactFile(val file: Path) : IntellijModelPathPolicy {
        override fun contains(path: Path): Boolean = path == file
    }

    data class SourceRoots(
        val roots: List<Path>,
        val ownershipRoots: List<Path>,
    ) : IntellijModelPathPolicy {
        override fun contains(path: Path): Boolean {
            val owners = ownershipRoots.filter(path::startsWith)
            val depth = owners.maxOfOrNull(Path::getNameCount) ?: return false
            return owners.any { it.nameCount == depth && it in roots }
        }
    }
}

internal class ModelOwnedGlobalSearchScope(
    baseScope: GlobalSearchScope,
    private val pathPolicy: IntellijModelPathPolicy,
    private val libraryPolicy: SymbolLibraryPolicy,
    private val nativePath: (VirtualFile) -> IntellijVirtualFilePath,
    private val libraryMembership: (VirtualFile) -> IntellijLibraryMembership,
    private val sourceMembership: (VirtualFile) -> Boolean,
    private val fileAdmission: (Path) -> Boolean,
) : DelegatingGlobalSearchScope(baseScope, pathPolicy) {
    override fun contains(file: VirtualFile): Boolean {
        if (!super.contains(file)) {
            return false
        }
        if (
            libraryPolicy == SymbolLibraryPolicy.INCLUDE && libraryMembership(file) == IntellijLibraryMembership.LIBRARY
        ) {
            return true
        }
        return when (val path = nativePath(file)) {
            is IntellijVirtualFilePath.Absolute ->
                sourceMembership(file) && fileAdmission(path.value) && pathPolicy.contains(path.value)
            IntellijVirtualFilePath.Relative,
            IntellijVirtualFilePath.Unavailable -> false
        }
    }

    override fun isSearchInLibraries(): Boolean = libraryPolicy == SymbolLibraryPolicy.INCLUDE
}
