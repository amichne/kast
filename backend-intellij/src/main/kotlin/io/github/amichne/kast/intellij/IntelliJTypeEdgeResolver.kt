package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DirectClassInheritorsSearch
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.shared.analysis.supertypeNames
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.hierarchy.TypeEdgeResolver
import io.github.amichne.kast.shared.hierarchy.TypeHierarchyEdge
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * IntelliJ-backend implementation of [TypeEdgeResolver].
 *
 * - Supertypes: resolves FQNs via [JavaPsiFacade.findClass] within project scope.
 * - Subtypes: uses [DirectClassInheritorsSearch] on the target's light class.
 *
 * Each method acquires its own short-lived read lock (same pattern as
 * [IntelliJCallEdgeResolver]) to avoid starving the IDE write lock.
 */
internal class IntelliJTypeEdgeResolver(
    private val project: Project,
) : TypeEdgeResolver {

    override fun symbolFor(target: PsiElement): Symbol =
        ApplicationManager.getApplication().runReadAction<Symbol> {
            val supertypes = directSupertypeNames(target).takeUnless { it.isEmpty() }
            when (target) {
                is KtClassOrObject -> analyze(target.containingKtFile) {
                    target.toSymbolModel(containingDeclaration = null, supertypes = supertypes)
                }
                else -> target.toSymbolModel(containingDeclaration = null, supertypes = supertypes)
            }
        }

    override fun supertypeEdges(target: PsiElement): List<TypeHierarchyEdge> {
        return ApplicationManager.getApplication().runReadAction<List<TypeHierarchyEdge>> {
            val fqNames = directSupertypeNames(target)
            val scope = GlobalSearchScope.projectScope(project)
            val facade = JavaPsiFacade.getInstance(project)
            fqNames.mapNotNull { fqName ->
                val psiClass = facade.findClass(fqName, scope) ?: return@mapNotNull null
                TypeHierarchyEdge(target = psiClass, symbol = symbolFor(psiClass))
            }
        }
    }

    override fun subtypeEdges(target: PsiElement): List<TypeHierarchyEdge> {
        val psiClass = ApplicationManager.getApplication().runReadAction<PsiClass?> {
            when (target) {
                is PsiClass -> target
                is KtClassOrObject -> target.toLightClass()
                else -> null
            }
        } ?: return emptyList()

        // projectScope already limits to project content — no further path filter needed.
        val subtypes = ApplicationManager.getApplication().runReadAction<Collection<PsiClass>> {
            val scope = GlobalSearchScope.projectScope(project)
            DirectClassInheritorsSearch.search(psiClass, scope).findAll()
        }

        return subtypes.mapNotNull { subtype ->
            ApplicationManager.getApplication().runReadAction<TypeHierarchyEdge?> {
                if (!subtype.isValid) return@runReadAction null
                TypeHierarchyEdge(target = subtype, symbol = symbolFor(subtype))
            }
        }
    }

    private fun directSupertypeNames(target: PsiElement): List<String> = when (target) {
        is KtClassOrObject -> analyze(target.containingKtFile) { supertypeNames(target).orEmpty() }
        is PsiClass -> target.supers.mapNotNull(PsiClass::getQualifiedName).distinct().sorted()
        else -> emptyList()
    }
}
