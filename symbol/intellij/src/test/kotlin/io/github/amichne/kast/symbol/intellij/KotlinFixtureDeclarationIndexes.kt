package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest

/** These synthetic inventories contain only Kotlin source files. */
internal interface KotlinFixtureDeclarationIndexes : IntellijDeclarationIndexes {
    override fun javaNamed(
        request: SymbolDiscoveryRequest,
        scope: GlobalSearchScope,
        accept: (NavigationItem) -> Boolean,
    ): Boolean = true
}
