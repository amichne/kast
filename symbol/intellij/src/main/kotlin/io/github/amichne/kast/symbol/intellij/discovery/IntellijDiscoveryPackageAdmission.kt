package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import org.jetbrains.kotlin.psi.KtFile

/** Package evidence is extracted only after native index collection has returned. */
internal sealed interface IntellijPackageEvidence {
    data class Known(val value: String) : IntellijPackageEvidence
    data object Unavailable : IntellijPackageEvidence
}

internal fun interface IntellijDiscoveryItemPackage {
    fun inspect(item: NavigationItem): IntellijPackageEvidence
}

internal data object IntellijPsiDiscoveryItemPackage : IntellijDiscoveryItemPackage {
    override fun inspect(item: NavigationItem): IntellijPackageEvidence {
        val element = when (item) {
            is PsiElement -> item
            is PsiElementNavigationItem -> item.targetElement
            else -> return IntellijPackageEvidence.Unavailable
        }
        return element?.containingFile?.packageEvidence() ?: IntellijPackageEvidence.Unavailable
    }
}

internal fun PsiFile.packageEvidence(): IntellijPackageEvidence = when (this) {
    is KtFile -> IntellijPackageEvidence.Known(packageFqName.asString())
    else -> IntellijPackageEvidence.Unavailable
}

/** A missing restriction does not acquire package PSI; missing requested evidence fails closed. */
internal fun SymbolDiscoveryPackageConstraint?.admitPackage(
    evidence: () -> IntellijPackageEvidence,
): IntellijDiscoveryItemAdmission {
    if (this == null) return IntellijDiscoveryItemAdmission.ADMITTED
    val observed = when (val result = evidence()) {
        is IntellijPackageEvidence.Known -> result.value
        IntellijPackageEvidence.Unavailable -> return IntellijDiscoveryItemAdmission.UNSUPPORTED
    }
    val included = when (containment) {
        SymbolDiscoveryContainment.DIRECT -> observed == packageName.value
        SymbolDiscoveryContainment.DESCENDANTS -> observed == packageName.value || observed.startsWith("${packageName.value}.")
    }
    return if (included) IntellijDiscoveryItemAdmission.ADMITTED else IntellijDiscoveryItemAdmission.FILTERED
}
