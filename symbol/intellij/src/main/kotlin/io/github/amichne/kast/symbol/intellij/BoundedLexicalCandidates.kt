package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolNameRelevance
import io.github.amichne.kast.symbol.contract.relevance
import java.util.PriorityQueue

/** Retains only the best admitted lexical candidates; compiler work happens after selection. */
internal class BoundedLexicalCandidates(private val pattern: SymbolDiscoveryPattern, private val capacity: Int) {
    private data class Ranked(val item: NavigationItem, val relevance: SymbolNameRelevance, val name: String, val path: String, val offset: Int)
    private val order = compareBy<Ranked> { it.relevance }.thenBy { it.name }.thenBy { it.path }.thenBy { it.offset }
    private val retained = PriorityQueue(order.reversed())
    var truncated: Boolean = false
        private set

    fun accept(item: NavigationItem) {
        val name = item.name ?: return
        val psi = item as? PsiElement
        val candidate = Ranked(item, pattern.relevance(name), name, psi?.containingFile?.virtualFile?.path.orEmpty(), psi?.textOffset ?: 0)
        if (retained.any { it.item === item }) return
        if (retained.size < capacity) retained += candidate
        else {
            truncated = true
            if (order.compare(candidate, retained.peek()) < 0) {
                retained.remove()
                retained += candidate
            }
        }
    }

    fun values(): List<NavigationItem> = retained.sortedWith(order).map { it.item }
}
