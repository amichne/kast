package io.github.amichne.kast.symbol.intellij

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget

/** Both exact-name routes feed source declarations into the same bounded admission and compiler projection. */
internal fun collectExactJavaDeclarations(
    project: Project,
    request: SymbolDiscoveryRequest,
    scope: GlobalSearchScope,
    accept: (NavigationItem) -> Boolean,
): Boolean {
    val target = request.target
    if (target !is SymbolDiscoveryTarget.Name || target.match != SymbolDiscoveryMatch.EXACT_NAME) return true
    val name = target.pattern.value
    val kinds = request.requestedDeclarationKinds()
    val javaScope = GlobalSearchScope.getScopeRestrictedByFileTypes(scope, JavaFileType.INSTANCE)
    fun admit(member: PsiMember): Boolean {
        val membership = request.constraints.packageName.admitPackage(member::indexedPackage)
        return membership == IntellijDiscoveryItemAdmission.FILTERED || accept(member)
    }
    val index = StubIndex.getInstance()
    if (
        CompilerSymbolKind.CLASSLIKE in kinds &&
            !index.processElements(
                JavaStubIndexKeys.CLASS_SHORT_NAMES,
                name,
                project,
                javaScope,
                PsiClass::class.java,
                ::admit,
            )
    )
        return false
    return CompilerSymbolKind.FUNCTION !in kinds ||
        index.processElements(JavaStubIndexKeys.METHODS, name, project, javaScope, PsiMethod::class.java) {
            it.isConstructor || admit(it)
        }
}

/** The outer class stub supplies its package without a package PSI read inside an index callback. */
private fun PsiMember.indexedPackage(): IntellijPackageEvidence {
    val owner = if (this is PsiClass) this else containingClass
    val qualified = generateSequence(owner) { it.containingClass }.lastOrNull()?.qualifiedName
    return if (qualified == null) IntellijPackageEvidence.Unavailable
    else IntellijPackageEvidence.Known(qualified.substringBeforeLast('.', ""))
}
