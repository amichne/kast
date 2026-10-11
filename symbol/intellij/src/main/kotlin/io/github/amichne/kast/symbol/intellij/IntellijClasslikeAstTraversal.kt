package io.github.amichne.kast.symbol.intellij

import com.intellij.lang.ASTNode
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Full depth-first tree coverage. Public child access expands lazy bodies; unproven nodes retain PSI classification.
 */
internal class IntellijClasslikeAstTraversal(
    private val binding: IntellijClasslikeAstBinding.Supported,
    private val observe: () -> Boolean,
    private val observation: IntellijReadObservation,
    private val accept: (KtNamedDeclaration) -> Boolean,
) {
    fun visit(file: KtFile): Boolean {
        if (!observe()) return false
        val root = file.node
        var current = root.firstChildNode
        while (current != null) {
            if (!observe()) return false
            observation.count(IntellijReadCounter.DECLARATION_AST_NODES_VISITED)
            if (!admit(current)) return false
            current = current.firstChildNode ?: nextSiblingWithin(current, root)
        }
        return observe()
    }

    private fun admit(node: ASTNode): Boolean {
        when (binding.admit(node)) {
            ClasslikeAstPsiAdmission.OMITTED -> return true
            ClasslikeAstPsiAdmission.UNPROVEN -> observation.count(IntellijReadCounter.DECLARATION_AST_PSI_FALLBACKS)
            ClasslikeAstPsiAdmission.REQUIRED -> Unit
        }
        observation.count(IntellijReadCounter.DECLARATION_AST_PSI_REQUESTS)
        val element = node.psi
        if (element != null) observation.count(IntellijReadCounter.DECLARATION_PSI_NODES_VISITED)
        return element !is KtNamedDeclaration || accept(element)
    }

    private fun nextSiblingWithin(node: ASTNode, root: ASTNode): ASTNode? {
        var current = node
        while (current !== root) {
            if (!observe()) return null
            current.treeNext?.let {
                return it
            }
            current = current.treeParent ?: return null
        }
        return null
    }
}
