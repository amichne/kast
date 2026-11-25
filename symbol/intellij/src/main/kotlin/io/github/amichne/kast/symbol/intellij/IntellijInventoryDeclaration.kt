package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem

/** The declaration that one source leaf starts. An unselectable declaration is inside a requested kind. */
internal sealed interface IntellijInventoryDeclaration {
    data object None : IntellijInventoryDeclaration

    data object Unselectable : IntellijInventoryDeclaration

    data class Selected(val item: NavigationItem) : IntellijInventoryDeclaration
}
