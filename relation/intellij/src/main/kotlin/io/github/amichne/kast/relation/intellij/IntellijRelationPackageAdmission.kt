package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import org.jetbrains.kotlin.psi.KtFile

internal sealed interface IntellijRelationPackageEvidence {
    data class Known(val value: String) : IntellijRelationPackageEvidence

    data object Unavailable : IntellijRelationPackageEvidence
}

internal enum class IntellijRelationPackageAdmission {
    ADMITTED,
    OUTSIDE_SCOPE,
    UNSUPPORTED,
}

/** Called only after native provider collection, never by GlobalSearchScope.contains. */
internal fun PsiElement.relationPackageEvidence(): IntellijRelationPackageEvidence =
    when (val file = containingFile) {
        is KtFile -> IntellijRelationPackageEvidence.Known(file.packageFqName.asString())
        else -> IntellijRelationPackageEvidence.Unavailable
    }

internal fun SymbolDiscoveryPackageConstraint?.admitPackage(
    evidence: () -> IntellijRelationPackageEvidence
): IntellijRelationPackageAdmission {
    if (this == null) return IntellijRelationPackageAdmission.ADMITTED
    val observed =
        when (val result = evidence()) {
            is IntellijRelationPackageEvidence.Known -> result.value
            IntellijRelationPackageEvidence.Unavailable -> return IntellijRelationPackageAdmission.UNSUPPORTED
        }
    val included =
        when (containment) {
            SymbolDiscoveryContainment.DIRECT -> observed == packageName.value
            SymbolDiscoveryContainment.DESCENDANTS ->
                observed == packageName.value || observed.startsWith("${packageName.value}.")
        }
    return if (included) IntellijRelationPackageAdmission.ADMITTED else IntellijRelationPackageAdmission.OUTSIDE_SCOPE
}
