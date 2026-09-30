package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryActiveInput
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.jetbrains.kotlin.psi.KtFile

/** Reacquires the next unread leaf and carries each established detached candidate forward exactly once. */
internal class IntellijDeclarationFileScanner(private val state: IntellijDeclarationDiscoveryState) {
    fun scan(file: KtFile) {
        val offset = (state.active as SymbolDiscoveryActiveInput.Scanning).nextOffset.value
        if (offset >= file.textLength) {
            state.finishFile()
            return
        }
        var leaf = file.findElementAt(offset)
        while (leaf != null) {
            if (!consumeLeaf(leaf, file)) return
            leaf = PsiTreeUtil.nextLeaf(leaf, true)
            if (leaf == null) {
                state.finishFile()
                return
            }
            if (!state.resultFits()) return
        }
        state.finishFile()
    }

    private fun consumeLeaf(leaf: PsiElement, file: KtFile): Boolean {
        if (!state.observe() || !state.consume()) return false
        state.examinedLeaves++
        val candidate = project(leaf, file)
        if (candidate != null && !append(candidate)) return false
        state.active =
            (state.active as SymbolDiscoveryActiveInput.Scanning).copy(
                nextOffset = SymbolDiscoverySourceOffset.parse(leaf.textRange.endOffset).discoveryRefined()
            )
        return state.advance()
    }

    private fun project(leaf: PsiElement, file: KtFile): SymbolDiscoveryCandidate? {
        val declaration = leaf.discoveryDeclaration(state.request) ?: return null
        state.observation.count(IntellijReadCounter.CANDIDATES_COLLECTED, IntellijReadContributor.SCOPED_DECLARATIONS)
        val started = state.allowance.now()
        val projected = IntellijPsiDiscoveryCandidateProjector.project(state.request, declaration, file.virtualFile)
        state.projectionNanos += state.elapsed(started)
        return when (projected) {
            is Refinement.Refined -> projected.value
            is Refinement.Rejected -> {
                state.qualifications += SymbolDiscoveryQualification.UNSUPPORTED_ITEM
                null
            }
        }
    }

    private fun append(candidate: SymbolDiscoveryCandidate): Boolean {
        val size = candidate.projectedUtf8Size().value
        if (size > state.request.budget.returnedBytes.value - state.bytes) {
            state.qualifications += SymbolDiscoveryQualification.BYTE_LIMIT_REACHED
            if (state.candidates.isEmpty()) state.block = SymbolDiscoveryBlockCause.ITEM_BYTE_LIMIT
            return false
        }
        state.candidates += candidate
        state.observation.count(IntellijReadCounter.CANDIDATES_PROJECTED, IntellijReadContributor.SCOPED_DECLARATIONS)
        state.bytes += size
        return true
    }
}
