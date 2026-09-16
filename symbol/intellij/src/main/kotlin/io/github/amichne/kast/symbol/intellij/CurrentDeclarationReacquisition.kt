package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.ExactRevalidationCompilation
import io.github.amichne.kast.symbol.contract.ExactRevalidationLocator
import io.github.amichne.kast.symbol.contract.ExactRevalidationPolicy
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.sameDeclaration
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTypeAliasShortNameIndex
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/** Exact file, scope, name and declaration family constrain native indexes before bounded candidate collection. */
internal fun confirmCurrentDeclaration(
    project: Project,
    locator: ExactRevalidationLocator,
    scope: CompiledIntellijSearchScope,
    capture: IntellijExactRevalidationCapture,
    observation: IntellijReadObservation,
    budget: ResourceBudget,
    charge: () -> Unit,
): ExactRevalidationCompilation =
    CurrentDeclarationRead(project, locator, scope, capture, observation, budget, charge).confirm()

private class CurrentDeclarationRead(
    private val project: Project,
    private val locator: ExactRevalidationLocator,
    private val scope: CompiledIntellijSearchScope,
    private val capture: IntellijExactRevalidationCapture,
    private val observation: IntellijReadObservation,
    private val budget: ResourceBudget,
    private val charge: () -> Unit,
) {
    private val started = System.nanoTime()
    private val captureStart = capture.chargedWork
    private val expected = locator.evidence

    fun confirm(): ExactRevalidationCompilation {
        if (
            expected.qualifiedIdentity == ExactDeclarationQualifiedIdentity.Unavailable ||
                expected.kind == CompilerSymbolKind.CONSTRUCTOR
        )
            return rejectedCurrent(ExactRevalidationRejection.UNSUPPORTED_DECLARATION)
        val file =
            expected.file as? SymbolDiscoveryFileIdentity.Workspace
                ?: return rejectedCurrent(ExactRevalidationRejection.UNSUPPORTED_DECLARATION)
        val psi =
            when (val admitted = admitFile(file)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejectedCurrent(admitted.failure)
            }
        val candidates =
            when (val admitted = collectCandidates(psi)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejectedCurrent(admitted.failure)
            }
        return confirmCandidates(file, candidates)
    }

    private fun admitFile(file: SymbolDiscoveryFileIdentity.Workspace): Refinement<KtFile, ExactRevalidationRejection> {
        val virtual =
            VirtualFileManager.getInstance().findFileByNioPath(Path.of(file.path.value))
                ?: return Refinement.Rejected(ExactRevalidationRejection.DECLARATION_MISSING)
        if (!scope.nativeScope.contains(virtual)) return Refinement.Rejected(ExactRevalidationRejection.SCOPE_REJECTED)
        val psi =
            PsiManager.getInstance(project).findFile(virtual) as? KtFile
                ?: return Refinement.Rejected(ExactRevalidationRejection.UNSUPPORTED_DECLARATION)
        when (
            val checked =
                capture.check(locator, psi, ExactRevalidationPolicy.CURRENT_DECLARATION, budget.workUnitLimit.value)
        ) {
            is Refinement.Rejected -> return checked
            is Refinement.Refined -> Unit
        }
        return when (
            locator.constraints.packageName.admitPackage { IntellijPackageEvidence.Known(psi.packageFqName.asString()) }
        ) {
            IntellijDiscoveryItemAdmission.ADMITTED -> Refinement.Refined(psi)
            IntellijDiscoveryItemAdmission.FILTERED -> Refinement.Rejected(ExactRevalidationRejection.SCOPE_REJECTED)
            IntellijDiscoveryItemAdmission.UNSUPPORTED ->
                Refinement.Rejected(ExactRevalidationRejection.UNSUPPORTED_DECLARATION)
        }
    }

    private fun collectCandidates(psi: KtFile): Refinement<List<KtNamedDeclaration>, ExactRevalidationRejection> {
        val collector =
            ReacquisitionCandidates(expected, budget, started, capture.chargedWork - captureStart) {
                charge()
                observation.count(IntellijReadCounter.REVALIDATION_WORK_CHARGED)
            }
        val nativeScope = scope.nativeScope.intersectWith(GlobalSearchScope.fileScope(project, psi.virtualFile))
        val name = expected.name.value
        val completed =
            when (expected.kind) {
                CompilerSymbolKind.CONSTRUCTOR ->
                    return Refinement.Rejected(ExactRevalidationRejection.UNSUPPORTED_DECLARATION)
                CompilerSymbolKind.CLASSLIKE ->
                    KotlinClassShortNameIndex.processElements(name, project, nativeScope, collector::collect)
                CompilerSymbolKind.FUNCTION ->
                    KotlinFunctionShortNameIndex.processElements(name, project, nativeScope, collector::collect)
                CompilerSymbolKind.PROPERTY ->
                    KotlinPropertyShortNameIndex.processElements(name, project, nativeScope, collector::collect)
                CompilerSymbolKind.TYPE_ALIAS ->
                    KotlinTypeAliasShortNameIndex.processElements(name, project, nativeScope, collector::collect)
            }
        return collector.result(completed)
    }

    private fun confirmCandidates(
        file: SymbolDiscoveryFileIdentity.Workspace,
        candidates: List<KtNamedDeclaration>,
    ): ExactRevalidationCompilation {
        val lookup =
            IntellijKotlinCompilerSymbolLookup(IntellijPsiExactDeclarationLookup(project), observation, capture)
        var matched: CompilerGroundedSymbolEvidence? = null
        for (declaration in candidates) {
            ProgressManager.checkCanceled()
            if (elapsedMillis(started) >= budget.elapsedTimeLimit.value)
                return rejectedCurrent(ExactRevalidationRejection.TIME_LIMIT_REACHED)
            val offset =
                when (val parsed = SymbolDiscoverySourceOffset.parse(declaration.textRange.startOffset)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return rejectedCurrent(ExactRevalidationRejection.DECLARATION_MISSING)
                }
            val evidence =
                when (
                    val found =
                        lookup.find(
                            scope,
                            IntellijExactDeclarationLookupKey(file, offset, expected.name, locator.constraints),
                        )
                ) {
                    is IntellijCompilerSymbolLookupResult.Rejected ->
                        return rejectedCurrent(found.reason.revalidationFailure())
                    is IntellijCompilerSymbolLookupResult.Found -> found.evidence
                }
            if (!evidence.sameDeclaration(expected)) continue
            if (matched != null) return rejectedCurrent(ExactRevalidationRejection.AMBIGUOUS)
            matched = evidence
        }
        return matched?.let(ExactRevalidationCompilation::Confirmed)
            ?: rejectedCurrent(ExactRevalidationRejection.DECLARATION_MISSING)
    }
}

private class ReacquisitionCandidates(
    private val expected: CompilerGroundedSymbolEvidence,
    private val budget: ResourceBudget,
    private val started: Long,
    private val captureWork: Long,
    private val charge: () -> Unit,
) {
    private val candidates = linkedSetOf<KtNamedDeclaration>()
    private var admission: Refinement<Unit, ExactRevalidationRejection> = Refinement.Refined(Unit)

    fun collect(declaration: KtNamedDeclaration): Boolean {
        ProgressManager.checkCanceled()
        if (declaration.name != expected.name.value || !declaration.hasReacquisitionKind(expected.kind)) return true
        if (declaration in candidates) return true
        admission =
            when {
                elapsedMillis(started) >= budget.elapsedTimeLimit.value ->
                    Refinement.Rejected(ExactRevalidationRejection.TIME_LIMIT_REACHED)
                candidates.size.toLong() + captureWork >= budget.workUnitLimit.value ->
                    Refinement.Rejected(ExactRevalidationRejection.WORK_LIMIT_REACHED)
                else -> Refinement.Refined(Unit)
            }
        if (admission is Refinement.Rejected) return false
        charge()
        candidates.add(declaration)
        return true
    }

    fun result(completed: Boolean): Refinement<List<KtNamedDeclaration>, ExactRevalidationRejection> =
        when (val result = admission) {
            is Refinement.Rejected -> result
            is Refinement.Refined ->
                if (completed) Refinement.Refined(candidates.toList())
                else Refinement.Rejected(ExactRevalidationRejection.COMPILER_UNAVAILABLE)
        }
}

private fun KtNamedDeclaration.hasReacquisitionKind(kind: CompilerSymbolKind): Boolean =
    when (this) {
        is KtClassOrObject -> kind == CompilerSymbolKind.CLASSLIKE
        is KtNamedFunction -> kind == CompilerSymbolKind.FUNCTION
        is KtProperty -> kind == CompilerSymbolKind.PROPERTY
        is KtParameter -> kind == CompilerSymbolKind.PROPERTY && hasValOrVar()
        is KtTypeAlias -> kind == CompilerSymbolKind.TYPE_ALIAS
        else -> false
    }

private const val NANOS_PER_MILLISECOND = 1_000_000L

private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / NANOS_PER_MILLISECOND

private fun rejectedCurrent(reason: ExactRevalidationRejection) = ExactRevalidationCompilation.Rejected(reason)
