package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Path

internal class IntellijSupplementalDiscoveryQuery(
    private val project: Project,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val clock: IntellijDiscoveryNanoClock = SystemIntellijDiscoveryNanoClock,
) {
    /**
     * Proof transition: `CompiledIntellijSearchScope + SymbolDiscoveryRequest ->
     * IntellijNativeDiscoveryExecution`.
     *
     * Establishes bounded location, file-structure, or indexed-text evidence under one compiled
     * scope and semantic generation. Expected partial coverage remains a closed discovery
     * qualification. Live PSI, VFS, and search helpers remain request-local.
     */
    fun discover(
        compiledScope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
    ): IntellijNativeDiscoveryExecution {
        val collector = SupplementalCollector(request, environmentState, clock)
        when (val target = request.target) {
            is SymbolDiscoveryTarget.Name ->
                return IntellijNativeDiscoveryExecution.Rejected(
                    IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
                )
            is SymbolDiscoveryTarget.Location -> {
                val file = project.findPsiFile(
                    request.scope.lease.workspaceRoot.value,
                    target.file.value,
                    compiledScope.nativeScope,
                ) ?: return collector.unsupported()
                file.declarationAt(target.offset.value)?.let { declaration ->
                    collector.accept(declaration.candidate(request))
                }
            }
            is SymbolDiscoveryTarget.Structure -> {
                val file = project.findPsiFile(
                    request.scope.lease.workspaceRoot.value,
                    target.file.value,
                    compiledScope.nativeScope,
                ) ?: return collector.unsupported()
                for (declaration in file.structureDeclarations()) {
                    if (!collector.accept(declaration.candidate(request))) break
                }
            }
            is SymbolDiscoveryTarget.Text -> {
                PsiSearchHelper.getInstance(project).processElementsWithWord(
                    { element, offsetInElement ->
                        collector.acceptText(element, offsetInElement, target.pattern.value)
                    },
                    compiledScope.nativeScope,
                    target.pattern.value,
                    UsageSearchContext.ANY,
                    true,
                )
            }
        }
        return collector.finish()
    }

    private fun Project.findPsiFile(
        workspaceRoot: String,
        relativePath: String,
        scope: GlobalSearchScope,
    ): PsiFile? {
        val path = Path.of(workspaceRoot).resolve(relativePath).normalize()
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
        if (!scope.contains(virtualFile)) return null
        return PsiManager.getInstance(this).findFile(virtualFile)
    }
}

private class SupplementalCollector(
    private val request: SymbolDiscoveryRequest,
    private val environmentState: () -> IntellijDiscoveryEnvironmentState,
    private val clock: IntellijDiscoveryNanoClock,
) {
    private val startedAt = clock.now()
    private val candidates = linkedSetOf<SymbolDiscoveryCandidate>()
    private val qualifications = linkedSetOf<SymbolDiscoveryQualification>()
    private var encodedBytes = 0L
    private var workUnits = 0L
    private var halted = false

    fun accept(candidate: Refinement<SymbolDiscoveryCandidate, *>): Boolean = when (candidate) {
        is Refinement.Refined -> accept(candidate.value)
        is Refinement.Rejected -> {
            qualifications += SymbolDiscoveryQualification.UNSUPPORTED_ITEM
            true
        }
    }

    fun acceptText(
        element: PsiElement,
        offsetInElement: Int,
        query: String,
    ): Boolean {
        if (!observe()) return false
        val file = PsiUtilCore.getVirtualFile(element.containingFile) ?: return true
        val start = element.textRange.startOffset + offsetInElement
        return accept(
            SymbolDiscoveryCandidate.fromBoundary(
                SymbolDiscoveryKind.TEXT,
                query,
                request.scope.lease,
                file.nioPath(),
                file.url,
                start,
                start + query.length,
            ),
        )
    }

    private fun accept(candidate: SymbolDiscoveryCandidate): Boolean {
        if (!observe()) return false
        if (candidate in candidates) return true
        if (workUnits >= request.budget.resources.workUnitLimit.value) {
            qualifications += SymbolDiscoveryQualification.WORK_LIMIT_REACHED
            halted = true
            return false
        }
        workUnits += 1L
        if (candidates.size >= request.budget.resources.resultLimit.value) {
            qualifications += SymbolDiscoveryQualification.RESULT_LIMIT_REACHED
            halted = true
            return false
        }
        val candidateBytes = candidate.projectedUtf8Size().value
        if (candidateBytes > request.budget.returnedBytes.value - encodedBytes) {
            qualifications += SymbolDiscoveryQualification.BYTE_LIMIT_REACHED
            halted = true
            return false
        }
        candidates += candidate
        encodedBytes += candidateBytes
        return true
    }

    private fun observe(): Boolean {
        ProgressManager.checkCanceled()
        if (halted) return false
        when (environmentState()) {
            IntellijDiscoveryEnvironmentState.READY -> Unit
            IntellijDiscoveryEnvironmentState.DUMB -> {
                qualifications += SymbolDiscoveryQualification.DUMB_MODE_TRANSITION
                halted = true
            }
            IntellijDiscoveryEnvironmentState.DISPOSED -> {
                qualifications += SymbolDiscoveryQualification.PROVIDER_FAILURE
                halted = true
            }
        }
        if (clock.now() - startedAt >= request.elapsedLimitNanoseconds().value) {
            qualifications += SymbolDiscoveryQualification.TIME_LIMIT_REACHED
            halted = true
        }
        return !halted
    }

    fun unsupported(): IntellijNativeDiscoveryExecution {
        qualifications += SymbolDiscoveryQualification.UNSUPPORTED_ITEM
        return finish()
    }

    fun finish(): IntellijNativeDiscoveryExecution {
        val ordered = candidates.sorted()
        val batch = when (
            val created = SymbolDiscoveryBatch.create(
                request,
                ordered,
                encodedBytes.byteCount(),
                workUnits.workCount(),
                SymbolDiscoveryTimings(
                    (clock.now() - startedAt).coerceAtLeast(0L).elapsedCount(),
                    0L.elapsedCount(),
                ),
            )
        ) {
            is Refinement.Refined -> created.value
            is Refinement.Rejected -> return IntellijNativeDiscoveryExecution.Rejected(
                IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
            )
        }
        val outcome = if (qualifications.isEmpty()) {
            SymbolDiscoveryOutcome.Complete(batch)
        } else {
            val admitted = SymbolDiscoveryQualifications.from(qualifications)
            val values = (admitted as? Refinement.Refined)?.value
                ?: return IntellijNativeDiscoveryExecution.Rejected(
                    IntellijNativeDiscoveryRejection.INTERNAL_INVARIANT,
                )
            SymbolDiscoveryOutcome.Qualified(batch, values)
        }
        return IntellijNativeDiscoveryExecution.Produced(outcome)
    }
}

private fun PsiFile.declarationAt(offset: Int): KtNamedDeclaration? =
    generateSequence(findElementAt(offset)) { it.parent }
        .filterIsInstance<KtNamedDeclaration>()
        .firstOrNull()

private fun PsiFile.structureDeclarations(): List<KtNamedDeclaration> =
    PsiTreeUtil.collectElementsOfType(this, KtNamedDeclaration::class.java)
        .filter { it.parent is KtFile || it.parent is KtClassBody }
        .sortedBy { it.textRange.startOffset }

private fun KtNamedDeclaration.candidate(
    request: SymbolDiscoveryRequest,
): Refinement<SymbolDiscoveryCandidate, *> {
    val file = containingFile?.virtualFile
        ?: return Refinement.Rejected(Unit)
    return SymbolDiscoveryCandidate.fromBoundary(
        discoveryKind(),
        name.orEmpty(),
        request.scope.lease,
        file.nioPath(),
        file.url,
        textRange.startOffset,
    )
}

private fun KtNamedDeclaration.discoveryKind(): SymbolDiscoveryKind = when (this) {
    is KtClassOrObject -> SymbolDiscoveryKind.CLASS
    else -> SymbolDiscoveryKind.SYMBOL
}

private fun VirtualFile.nioPath(): Path? = runCatching { Path.of(path) }.getOrNull()

private fun Long.byteCount(): SymbolDiscoveryByteCount =
    (SymbolDiscoveryByteCount.parse(this) as Refinement.Refined).value

private fun Long.workCount(): SymbolDiscoveryWorkCount =
    (SymbolDiscoveryWorkCount.parse(this) as Refinement.Refined).value

private fun Long.elapsedCount(): SymbolDiscoveryElapsedNanoseconds =
    (SymbolDiscoveryElapsedNanoseconds.parse(this) as Refinement.Refined).value
