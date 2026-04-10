package io.github.amichne.kast.standalone.hierarchy

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.shared.analysis.callHierarchyDeclaration
import io.github.amichne.kast.shared.analysis.resolvedFilePath
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.shared.hierarchy.CallEdge
import io.github.amichne.kast.shared.hierarchy.CallEdgeResolver
import io.github.amichne.kast.shared.hierarchy.callSiteLocation
import io.github.amichne.kast.standalone.analysis.CandidateFileResolver
import io.github.amichne.kast.standalone.normalizeStandalonePath
import java.nio.file.Path

/**
 * Standalone-backend implementation of [CallEdgeResolver].
 *
 * Uses [CandidateFileResolver] for incoming-edge candidate file pruning and
 * [normalizeStandalonePath] for workspace boundary checks on outgoing edges.
 */
internal class StandaloneCallEdgeResolver(
    private val candidateFileResolver: CandidateFileResolver,
    private val normalizedWorkspaceRoot: Path,
) : CallEdgeResolver {

    override fun incomingEdges(
        target: PsiElement,
        timeoutCheck: () -> Boolean,
        onFileVisited: (filePath: String) -> Unit,
    ): List<CallEdge> {
        val edges = mutableListOf<CallEdge>()

        candidateFileResolver.resolve(target).files.forEach { candidateFile ->
            if (timeoutCheck()) return edges
            onFileVisited(candidateFile.resolvedFilePath().value)
            candidateFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (timeoutCheck()) {
                            stopWalking()
                            return
                        }
                        element.references.forEach { reference ->
                            val resolved = reference.resolve()
                            if (resolved == target || resolved?.isEquivalentTo(target) == true) {
                                val caller = reference.element.callHierarchyDeclaration() ?: return@forEach
                                val callerSymbol = caller.toSymbolModel(containingDeclaration = null)
                                edges += CallEdge(
                                    target = caller,
                                    symbol = callerSymbol,
                                    callSite = reference.callSiteLocation(),
                                )
                            }
                        }
                        super.visitElement(element)
                    }
                },
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
        onFileVisited(declaration.resolvedFilePath().value)
        val edges = mutableListOf<CallEdge>()

        declaration.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (timeoutCheck()) {
                        stopWalking()
                        return
                    }
                    // Skip nested declarations to avoid expanding into inner
                    // classes/functions that are separate call hierarchy targets.
                    if (element !== declaration && element.callHierarchyDeclaration() === element) {
                        return
                    }
                    element.references.forEach { reference ->
                        val resolved = reference.resolve() ?: return@forEach
                        if (resolved.containingFile == null) return@forEach
                        if (!resolved.isWithinWorkspaceRoot(normalizedWorkspaceRoot)) return@forEach
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

private fun PsiElement.isWithinWorkspaceRoot(workspaceRoot: Path): Boolean {
    val vf = containingFile.virtualFile ?: containingFile.viewProvider.virtualFile
    if (!vf.isInLocalFileSystem) return false
    val filePath = runCatching { normalizeStandalonePath(Path.of(vf.path)) }.getOrNull() ?: return false
    return filePath.startsWith(workspaceRoot)
}
