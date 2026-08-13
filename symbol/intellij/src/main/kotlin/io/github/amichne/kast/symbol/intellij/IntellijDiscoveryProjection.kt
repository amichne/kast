package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.util.PsiUtilCore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateFailure
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest

internal sealed interface IntellijDiscoveryItemFileResult {
    data class Found(
        val file: VirtualFile,
    ) : IntellijDiscoveryItemFileResult

    data object Unsupported : IntellijDiscoveryItemFileResult
}

internal fun interface IntellijDiscoveryItemFile {
    /**
     * Proof transition: NavigationItem to IntellijDiscoveryItemFileResult.
     *
     * Establishes one request-local backing [VirtualFile] or the closed
     * [IntellijDiscoveryItemFileResult.Unsupported] state before scope containment is evaluated.
     * Live values may be extracted only by the native discovery collector.
     */
    fun find(item: NavigationItem): IntellijDiscoveryItemFileResult
}

internal fun interface IntellijDiscoveryCandidateProjector {
    fun project(
        request: SymbolDiscoveryRequest,
        item: NavigationItem,
        file: VirtualFile,
    ): Refinement<SymbolDiscoveryCandidate, SymbolDiscoveryCandidateFailure>
}

internal object IntellijPsiDiscoveryItemFile : IntellijDiscoveryItemFile {
    override fun find(item: NavigationItem): IntellijDiscoveryItemFileResult {
        val file = when (item) {
            is PsiFileSystemItem -> item.virtualFile
            else -> item.psiElement()?.let(PsiUtilCore::getVirtualFile)
        }
        return if (file == null) {
            IntellijDiscoveryItemFileResult.Unsupported
        } else {
            IntellijDiscoveryItemFileResult.Found(file)
        }
    }
}

internal object IntellijPsiDiscoveryCandidateProjector : IntellijDiscoveryCandidateProjector {
    /**
     * Proof transition:
     * SymbolDiscoveryRequest + NavigationItem + VirtualFile to
     * Refinement<SymbolDiscoveryCandidate, SymbolDiscoveryCandidateFailure>.
     *
     * Establishes a bounded detached name, exact workspace path or external virtual-file URL, and
     * a non-negative declaration offset for class and symbol candidates.
     * [SymbolDiscoveryCandidateFailure] is the closed expected failure. Live IntelliJ values and
     * raw paths, URLs, names, and offsets are extracted only inside this request-local projection.
     */
    override fun project(
        request: SymbolDiscoveryRequest,
        item: NavigationItem,
        file: VirtualFile,
    ): Refinement<SymbolDiscoveryCandidate, SymbolDiscoveryCandidateFailure> {
        val rawOffset = when (request.kind) {
            SymbolDiscoveryKind.FILE -> {
                if (item !is PsiFile) {
                    return Refinement.Rejected(
                        SymbolDiscoveryCandidateFailure.INVALID_FILE_LOCATION,
                    )
                }
                null
            }
            SymbolDiscoveryKind.CLASS,
            SymbolDiscoveryKind.SYMBOL,
                -> {
                val element = item.psiElement()
                if (element == null) {
                    return Refinement.Rejected(
                        SymbolDiscoveryCandidateFailure.DECLARATION_CANDIDATE_MISSING_OFFSET,
                    )
                }
                element.textOffset
            }
        }
        val classifiedPath = nativePath(file)
        return SymbolDiscoveryCandidate.fromBoundary(
            kind = request.kind,
            rawName = item.name.orEmpty(),
            lease = request.scope.lease,
            nativePath = when (classifiedPath) {
                is IntellijVirtualFilePath.Absolute -> classifiedPath.value
                IntellijVirtualFilePath.Relative,
                IntellijVirtualFilePath.Unavailable,
                    -> null
            },
            virtualFileUrl = file.url,
            rawOffset = rawOffset,
        )
    }
}

private fun NavigationItem.psiElement(): PsiElement? = when (this) {
    is PsiElement -> this
    is PsiElementNavigationItem -> targetElement
    else -> null
}
