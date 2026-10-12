package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call
import java.nio.file.Path

internal sealed interface IntellijModelPathPolicy {
    fun contains(path: Path, observation: IntellijReadObservation = IntellijReadObservation.None): Boolean

    data class ExactFile(val file: Path) : IntellijModelPathPolicy {
        override fun contains(path: Path, observation: IntellijReadObservation): Boolean = path == file
    }

    data class SourceRoots(
        val roots: List<Path>,
        val ownershipRoots: List<Path>,
    ) : IntellijModelPathPolicy {
        private val selectedPaths = roots.toSet()
        private val importedPaths = ownershipRoots.toSet()

        override fun contains(path: Path, observation: IntellijReadObservation): Boolean {
            for (ancestor in generateSequence(path) { it.parent }) {
                observation.count(IntellijReadCounter.DISCOVERY_PATH_OWNERSHIP_PROBES)
                if (ancestor in importedPaths) return ancestor in selectedPaths
            }
            return false
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
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) : DelegatingGlobalSearchScope(baseScope, pathPolicy) {
    override fun contains(file: VirtualFile): Boolean =
        observation.call(IntellijReadCall.DISCOVERY_SCOPE_FILE_MEMBERSHIP) {
            containsObserved(file).also { admitted ->
                observation.count(
                    if (admitted) IntellijReadCounter.DISCOVERY_SCOPE_FILES_ADMITTED
                    else IntellijReadCounter.DISCOVERY_SCOPE_FILES_EXCLUDED
                )
            }
        }

    private fun containsObserved(file: VirtualFile): Boolean {
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
                observation.call(IntellijReadCall.DISCOVERY_SCOPE_SOURCE_MEMBERSHIP) { sourceMembership(file) } &&
                    fileAdmission(path.value) &&
                    observation.call(IntellijReadCall.DISCOVERY_SCOPE_PATH_OWNERSHIP) {
                        pathPolicy.contains(path.value, observation)
                    }
            IntellijVirtualFilePath.Relative,
            IntellijVirtualFilePath.Unavailable -> false
        }
    }

    override fun isSearchInLibraries(): Boolean = libraryPolicy == SymbolLibraryPolicy.INCLUDE
}
