package io.github.amichne.kast.symbol.intellij

import com.intellij.lang.ASTNode
import com.intellij.psi.impl.source.tree.CompositeElement
import org.jetbrains.kotlin.psi.KtFile

/** Fixture-only cached-wrapper census; expands AST without acquiring PSI and excludes each file root. */
internal object ClasslikeAstPsiWork {
    private val wrapper = CompositeElement::class.java.getDeclaredField("myWrapper").apply { isAccessible = true }

    data class Census(val nodes: Int, val cachedCompositePsi: Int)

    fun census(files: List<KtFile>): Census {
        var nodes = 0
        var cached = 0
        for (file in files) {
            val root = file.node
            var current = root.firstChildNode
            while (current != null) {
                nodes++
                if (current is CompositeElement && wrapper.get(current) != null) cached++
                current = current.firstChildNode ?: next(current, root)
            }
        }
        return Census(nodes, cached)
    }

    private fun next(node: ASTNode, root: ASTNode): ASTNode? {
        var current = node
        while (current !== root) {
            current.treeNext?.let {
                return it
            }
            current = current.treeParent ?: return null
        }
        return null
    }
}
