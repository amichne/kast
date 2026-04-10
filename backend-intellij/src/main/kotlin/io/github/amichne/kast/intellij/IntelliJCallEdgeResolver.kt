package io.github.amichne.kast.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import io.github.amichne.kast.shared.analysis.callHierarchyDeclaration
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.hierarchy.CallEdge
import io.github.amichne.kast.shared.hierarchy.CallEdgeResolver
import io.github.amichne.kast.shared.hierarchy.callSiteLocation

/**
 * IntelliJ-backend implementation of [CallEdgeResolver].
 *
 * Uses [ReferencesSearch] and [GlobalSearchScope.projectScope] for incoming
 * edges, and a [PsiRecursiveElementWalkingVisitor] walk for outgoing edges.
 */
internal class IntelliJCallEdgeResolver(
    private val project: Project,
    private val workspacePrefix: String,
) : CallEdgeResolver {

    override fun incomingEdges(
        target: PsiElement,
        timeoutCheck: () -> Boolean,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge> {
        val edges = mutableListOf<CallEdge>()
        val searchScope = GlobalSearchScope.projectScope(project)

        ReferencesSearch.search(target, searchScope).forEach { ref ->
            if (timeoutCheck()) return edges
            val element = ref.element
            val filePath = element.resolvedFilePath().value
            onFileVisited(filePath)
            if (!filePath.startsWith(workspacePrefix)) return@forEach

            val caller = element.callHierarchyDeclaration() ?: return@forEach
            edges += CallEdge(
                target = caller,
                symbol = caller.toSymbolModel(containingDeclaration = null),
                callSite = ref.callSiteLocation(),
            )
        }

        return edges
    }

    override fun outgoingEdges(
        target: PsiElement,
        timeoutCheck: () -> Boolean,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge> {
        val declaration = target.callHierarchyDeclaration() ?: return emptyList()
        val filePath = declaration.resolvedFilePath().value
        onFileVisited(filePath)
        val edges = mutableListOf<CallEdge>()

        declaration.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (timeoutCheck()) {
                        stopWalking()
                        return
                    }
                    // Skip nested declarations to avoid expanding inner hierarchy targets.
                    if (element !== declaration && element.callHierarchyDeclaration() === element) {
                        return
                    }
                    element.references.forEach { reference ->
                        val resolved = reference.resolve() ?: return@forEach
                        if (resolved.containingFile == null) return@forEach
                        val resolvedPath = resolved.resolvedFilePath().value
                        if (!resolvedPath.startsWith(workspacePrefix)) return@forEach
                        edges += CallEdge(
                            target = resolved,
                            symbol = resolved.toSymbolModel(containingDeclaration = null),
                            callSite = reference.callSiteLocation(),
                        )
                    }
                    super.visitElement(element)
                }
            },
        )

        return edges
    }
}
