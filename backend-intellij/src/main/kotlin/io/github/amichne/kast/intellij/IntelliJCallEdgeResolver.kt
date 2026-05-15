package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReference
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
 *
 * Each method acquires its own short-lived read lock so that the caller
 * (recursive [io.github.amichne.kast.shared.hierarchy.CallHierarchyEngine])
 * does **not** need to hold the IDE read lock for the entire traversal.
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
        // Collect references incrementally so the read lock can be interrupted by
        // checkCanceled() if a write action is pending, preventing EDT freezes.
        val refs = ApplicationManager.getApplication().runReadAction<List<PsiReference>> {
            val searchScope = GlobalSearchScope.projectScope(project)
            val collected = mutableListOf<PsiReference>()
            ReferencesSearch.search(target, searchScope).forEach { ref ->
                ProgressManager.checkCanceled()
                collected.add(ref)
                true
            }
            collected
        }

        val edges = mutableListOf<CallEdge>()
        val visitedFiles = mutableSetOf<String>()

        for (ref in refs) {
            if (timeoutCheck()) break
            // Process each reference in its own short read action so the IDE write
            // lock can be acquired between references.
            val edge = ApplicationManager.getApplication().runReadAction<CallEdge?> {
                val element = ref.element
                if (!element.isValid) return@runReadAction null
                val filePath = element.resolvedFilePath().value
                if (visitedFiles.add(filePath)) {
                    onFileVisited(filePath)
                }
                if (!filePath.startsWith(workspacePrefix)) return@runReadAction null
                val caller = element.callHierarchyDeclaration() ?: return@runReadAction null
                CallEdge(
                    target = caller,
                    symbol = caller.toSymbolModel(containingDeclaration = null),
                    callSite = ref.callSiteLocation(),
                )
            }
            edge?.let { edges += it }
        }

        return edges
    }

    override fun outgoingEdges(
        target: PsiElement,
        timeoutCheck: () -> Boolean,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge> {
        // Phase 1: Collect call-expression elements and their references in one read action.
        data class ElementRef(val element: PsiElement, val reference: PsiReference)

        val declaration = ApplicationManager.getApplication().runReadAction<PsiElement?> {
            target.callHierarchyDeclaration()
        } ?: return emptyList()

        val filePath = ApplicationManager.getApplication().runReadAction<String> {
            declaration.resolvedFilePath().value
        }
        onFileVisited(filePath)

        val elementRefs = ApplicationManager.getApplication().runReadAction<List<ElementRef>> {
            val collected = mutableListOf<ElementRef>()
            declaration.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        ProgressManager.checkCanceled()
                        if (timeoutCheck()) {
                            stopWalking()
                            return
                        }
                        // Skip nested declarations to avoid expanding inner hierarchy targets.
                        if (element !== declaration && element.callHierarchyDeclaration() === element) {
                            return
                        }
                        element.references.forEach { reference ->
                            collected += ElementRef(element, reference)
                        }
                        super.visitElement(element)
                    }
                },
            )
            collected
        }

        // Phase 2: Process each collected reference in its own short read action.
        val edges = mutableListOf<CallEdge>()
        for (ref in elementRefs) {
            if (timeoutCheck()) break
            val edge = ApplicationManager.getApplication().runReadAction<CallEdge?> {
                if (!ref.element.isValid) return@runReadAction null
                val resolved = ref.reference.resolve() ?: return@runReadAction null
                if (resolved.containingFile == null) return@runReadAction null
                val resolvedPath = resolved.resolvedFilePath().value
                if (!resolvedPath.startsWith(workspacePrefix)) return@runReadAction null
                CallEdge(
                    target = resolved,
                    symbol = resolved.toSymbolModel(containingDeclaration = null),
                    callSite = ref.reference.callSiteLocation(),
                )
            }
            edge?.let { edges += it }
        }

        return edges
    }
}
