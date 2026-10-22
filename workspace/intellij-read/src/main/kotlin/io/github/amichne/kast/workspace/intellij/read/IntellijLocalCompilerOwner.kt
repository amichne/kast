@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.progress.ProgressManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Anonymous functions remain lexical scopes even when their PSI exposes a synthetic name. */
fun KaSymbol.namedLocalCompilerOwner(
    session: KaSession,
    workAllowance: WorkAllowance = defaultLocalCompilerOwnerAllowance(),
): Refinement<KaSymbol, LocalDeclarationProjectionFailure> =
    with(session) {
        var owner = containingSymbol
        var traversed = 0L
        val seen = hashSetOf(this@namedLocalCompilerOwner)
        while (owner != null && !owner.isLocalDeclarationOwner()) {
            ProgressManager.checkCanceled()
            if (!seen.add(owner)) return Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerOwnerUnavailable)
            traversed = nextCompilerOwnerDepth(traversed)
            if (!workAllowance.permitsCompilerOwnerTraversal(traversed))
                return Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerDepthExceeded)
            owner = owner.containingSymbol
        }
        return refineCompilerOwner(owner, seen)
    }

private fun nextCompilerOwnerDepth(traversed: Long): Long =
    if (traversed == Long.MAX_VALUE) traversed else traversed + 1L

private fun WorkAllowance.permitsCompilerOwnerTraversal(traversed: Long): Boolean =
    when (this) {
        WorkAllowance.Unlimited -> true
        is WorkAllowance.Bounded -> traversed <= DEFAULT_LOCAL_COMPILER_OWNER_DEPTH && permits(traversed)
    }

private fun refineCompilerOwner(
    owner: KaSymbol?,
    seen: Set<KaSymbol>,
): Refinement<KaSymbol, LocalDeclarationProjectionFailure> =
    when {
        owner == null -> Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerUnavailable)
        owner in seen -> Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerOwnerUnavailable)
        else -> Refinement.Refined(owner)
    }

private fun KaSymbol.isLocalDeclarationOwner(): Boolean =
    when (this) {
        is KaAnonymousFunctionSymbol -> false
        is KaConstructorSymbol,
        is KaAnonymousObjectSymbol -> true
        else -> (psi as? KtNamedDeclaration)?.name != null
    }

private const val DEFAULT_LOCAL_COMPILER_OWNER_DEPTH = 32

private fun defaultLocalCompilerOwnerAllowance(): WorkAllowance.Bounded =
    when (val parsed = WorkUnitLimit.parse(DEFAULT_LOCAL_COMPILER_OWNER_DEPTH.toLong())) {
        is Refinement.Refined -> WorkAllowance.Bounded(parsed.value)
        is Refinement.Rejected -> error("The fixed compiler owner allowance must be positive")
    }
