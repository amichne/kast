package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
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
import io.github.amichne.kast.shared.hierarchy.EdgeDiscoveryBudget
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * IDEA-backend implementation of [TypeEdgeResolver].
 *
 * - Supertypes: resolves FQNs via [JavaPsiFacade.findClass] within project scope.
 * - Subtypes: uses [DirectClassInheritorsSearch] on the target's light class.
 *
 * Each method acquires its own short-lived read lock (same pattern as
 * [IdeaCallEdgeResolver]) to avoid starving the IDE write lock.
 */
internal class IdeaTypeEdgeResolver(
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

    override fun supertypeEdges(
        target: PsiElement,
        budget: EdgeDiscoveryBudget,
    ): List<TypeHierarchyEdge> {
        return ApplicationManager.getApplication().runReadAction<List<TypeHierarchyEdge>> {
            val fqNames = directSupertypeNames(target)
            val scope = GlobalSearchScope.projectScope(project)
            val facade = JavaPsiFacade.getInstance(project)
            buildList {
                for (fqName in fqNames) {
                    ProgressManager.checkCanceled()
                    if (!budget.tryAdmitCandidate()) break
                    val psiClass = facade.findClass(fqName, scope) ?: continue
                    add(TypeHierarchyEdge(target = psiClass, symbol = symbolFor(psiClass)))
                }
            }
        }
    }

    override fun subtypeEdges(
        target: PsiElement,
        budget: EdgeDiscoveryBudget,
    ): List<TypeHierarchyEdge> {
        val psiClass = ApplicationManager.getApplication().runReadAction<PsiClass?> {
            when (target) {
                is PsiClass -> target
                is KtClassOrObject -> target.toLightClass()
                else -> null
            }
        } ?: return emptyList()

        // projectScope already limits to project content — no further path filter needed.
        val subtypes = ApplicationManager.getApplication().runReadAction<List<PsiClass>> {
            val scope = GlobalSearchScope.projectScope(project)
            buildList {
                DirectClassInheritorsSearch.search(psiClass, scope).forEach { subtype ->
                    ProgressManager.checkCanceled()
                    if (!budget.tryAdmitCandidate()) {
                        false
                    } else {
                        add(subtype)
                        true
                    }
                }
            }
        }

        return buildList {
            for (subtype in subtypes) {
                if (budget.timeoutReached()) break
                ApplicationManager.getApplication().runReadAction<TypeHierarchyEdge?> {
                    if (!subtype.isValid) return@runReadAction null
                    TypeHierarchyEdge(target = subtype, symbol = symbolFor(subtype))
                }?.let(::add)
            }
        }
    }

    private fun directSupertypeNames(target: PsiElement): List<String> = when (target) {
        is KtClassOrObject -> analyze(target.containingKtFile) { supertypeNames(target).orEmpty() }
        is PsiClass -> target.supers.mapNotNull(PsiClass::getQualifiedName).distinct().sorted()
        else -> emptyList()
    }
}
