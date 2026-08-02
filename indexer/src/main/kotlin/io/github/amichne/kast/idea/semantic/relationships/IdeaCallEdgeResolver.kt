package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.shared.analysis.callHierarchyDeclaration
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.hierarchy.CallEdge
import io.github.amichne.kast.shared.hierarchy.CallEdgeResolver
import io.github.amichne.kast.shared.hierarchy.EdgeDiscoveryBudget
import io.github.amichne.kast.shared.hierarchy.callSiteLocation

/**
 * Indexer implementation of [CallEdgeResolver] through IntelliJ compiler PSI.
 *
 * Uses [ReferencesSearch] and [GlobalSearchScope.projectScope] for incoming
 * edges, and a [PsiRecursiveElementWalkingVisitor] walk for outgoing edges.
 *
 * Each method acquires its own short-lived read lock so that the caller
 * (recursive [io.github.amichne.kast.shared.hierarchy.CallHierarchyEngine])
 * does **not** need to hold the IDE read lock for the entire traversal.
 */
internal class IdeaCallEdgeResolver(
    private val project: Project,
    private val workspaceIdentity: WorkspaceIdentity,
) : CallEdgeResolver {

    override fun incomingEdges(
        target: PsiElement,
        budget: EdgeDiscoveryBudget,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge> {
        val edges = mutableListOf<CallEdge>()
        val visitedFiles = mutableSetOf<String>()
        fun edgeFor(ref: PsiReference): CallEdge? {
            val element = ref.element
            if (!element.isValid) return null
            val filePath = element.resolvedFilePath().value
            if (visitedFiles.add(filePath)) {
                onFileVisited(filePath)
            }
            if (!workspaceIdentity.contains(filePath)) return null
            val caller = element.callHierarchyDeclaration() ?: return null
            return CallEdge(
                target = caller,
                symbol = caller.toSymbolModel(containingDeclaration = null),
                callSite = ref.callSiteLocation(),
            )
        }
        ApplicationManager.getApplication().runReadAction {
            val searchScope = GlobalSearchScope.projectScope(project)
            ReferencesSearch.search(target, searchScope).forEach(Processor { ref ->
                ProgressManager.checkCanceled()
                if (budget.timeoutReached()) {
                    false
                } else {
                    val edge = edgeFor(ref)
                    when {
                        edge == null -> true
                        !budget.tryAdmitCandidate() -> false
                        else -> {
                            edges += edge
                            true
                        }
                    }
                }
            })
        }
        return edges
    }

    override fun outgoingEdges(
        target: PsiElement,
        budget: EdgeDiscoveryBudget,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge> {
        val declaration = ApplicationManager.getApplication().runReadAction<PsiElement?> {
            target.callHierarchyDeclaration()
        } ?: return emptyList()

        val filePath = ApplicationManager.getApplication().runReadAction<String> {
            declaration.resolvedFilePath().value
        }
        onFileVisited(filePath)

        val edges = mutableListOf<CallEdge>()
        ApplicationManager.getApplication().runReadAction {
            declaration.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        ProgressManager.checkCanceled()
                        if (budget.timeoutReached()) {
                            stopWalking()
                            return
                        }
                        // Skip nested declarations to avoid expanding inner hierarchy targets.
                        if (element !== declaration && element.callHierarchyDeclaration() === element) {
                            return
                        }
                        for (reference in element.references) {
                            val resolved = reference.resolve() ?: continue
                            if (resolved.containingFile == null) continue
                            val resolvedPath = resolved.resolvedFilePath().value
                            if (!workspaceIdentity.contains(resolvedPath)) continue
                            val edge = CallEdge(
                                target = resolved,
                                symbol = resolved.toSymbolModel(containingDeclaration = null),
                                callSite = reference.callSiteLocation(),
                            )
                            if (!budget.tryAdmitCandidate()) {
                                stopWalking()
                                return
                            }
                            edges += edge
                        }
                        super.visitElement(element)
                    }
                },
            )
        }
        return edges
    }
}
