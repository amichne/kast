package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScopedDeclarationDiscoveryTest {
    @Test
    fun `transposed fuzzy name still reaches scoped declarations`() {
        val scenario = SymbolDiscoveryTest().fixture(pattern = "AItme", workLimit = 2L)
        val result = scenario.query.discoverDeclarations(scenario.compiledScope, scenario.request) { _, _, accept ->
            for (name in listOf("NoMatch", "ZItem", "AItem")) {
                scenario.contributor.processElementsWithName(name, Processor { accept(it) },
                    FindSymbolParameters.wrap(name, scenario.scope))
            }
            true
        }.outcome()
        assertEquals(listOf("AItem"), result.batch().candidates.map { it.name.value })
    }

    @Test
    fun `excluded files cannot spend scoped file capacity`() {
        val scenario = SymbolDiscoveryTest().fixture(all = true)
        val selected = LightVirtualFile("/workspace/selected.kt")
        val overflow = LightVirtualFile("/workspace/overflow.kt")
        val native =
            object : GlobalSearchScope() {
                override fun contains(file: VirtualFile) = file === selected || file === overflow

                override fun isSearchInModuleContent(module: com.intellij.openapi.module.Module) = true

                override fun isSearchInLibraries() = false
            }
        val scope =
            CompiledIntellijSearchScope(scenario.compiledScope.lease, scenario.compiledScope.scope, emptyList(), native)
        val qualifications = mutableListOf<SymbolDiscoveryQualification>()
        val files =
            ScopedKotlinFileCollection(
                scope,
                (WorkUnitLimit.parse(1) as Refinement.Refined).value,
                { true },
                qualifications::add,
            )
        repeat(100) { assertTrue(files.accept(LightVirtualFile("/outside/Noise$it.kt"))) }
        assertTrue(files.accept(selected))
        assertEquals(listOf(selected), files.values)
        assertEquals(ScopedFileCollectionStop.NONE, files.stop)
        assertEquals(false, files.accept(overflow))
        assertEquals(ScopedFileCollectionStop.WORK_LIMIT, files.stop)
        assertEquals(listOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED), qualifications)
    }

    @Test
    fun `function restriction excludes class contributors before native name enumeration`() {
        val request = SymbolDiscoveryTest().fixture(declarationKinds = setOf(CompilerSymbolKind.FUNCTION)).request
        assertEquals(setOf(CompilerSymbolKind.FUNCTION), request.requestedDeclarationKinds())
        assertEquals(
            false,
            request.admitsContributorName("org.jetbrains.kotlin.idea.goto.KotlinGotoClassSymbolContributor"),
        )
        assertTrue(request.admitsContributorName("org.jetbrains.kotlin.idea.goto.KotlinGotoFunctionSymbolContributor"))
    }

    @Test
    fun `fuzzy scoped names are filtered before candidate capacity without contributor name enumeration`() {
        val scenario = SymbolDiscoveryTest().fixture(workLimit = 2L)
        assertTrue(scenario.request.usesScopedDeclarationEnumeration())
        val result =
            scenario.query
                .discoverDeclarations(scenario.compiledScope, scenario.request) { _, _, accept ->
                    for (name in List(100) { "NoMatch" } + listOf("ZItem", "AItem")) {
                        scenario.contributor.processElementsWithName(
                            name,
                            Processor { accept(it) },
                            FindSymbolParameters.wrap(name, scenario.scope),
                        )
                    }
                    true
                }
                .outcome()
        assertTrue(result is SymbolDiscoveryOutcome.Complete)
        assertEquals(listOf("AItem", "ZItem"), result.batch().candidates.map { it.name.value })
        assertEquals(null, scenario.contributor.nameScope)
    }

    @Test
    fun `cheap declaration kind exclusion precedes unavailable package PSI`() {
        val scenario =
            SymbolDiscoveryTest()
                .fixture(
                    all = true,
                    declarationKinds = setOf(CompilerSymbolKind.FUNCTION),
                    packageName = "sample",
                    itemKinds =
                        mapOf(
                            "AItem" to CompilerSymbolKind.CLASSLIKE,
                            "ZItem" to CompilerSymbolKind.CLASSLIKE,
                            "NoMatch" to CompilerSymbolKind.CLASSLIKE,
                        ),
                )
        val result = scenario.execute().outcome()
        assertTrue(result is SymbolDiscoveryOutcome.Complete)
        assertTrue(result.batch().candidates.isEmpty())
        assertTrue(scenario.projectedNames.isEmpty())
    }

    private fun IntellijNativeDiscoveryExecution.outcome() = (this as IntellijNativeDiscoveryExecution.Produced).outcome

    private fun SymbolDiscoveryOutcome.batch() =
        when (this) {
            is SymbolDiscoveryOutcome.Complete -> batch
            is SymbolDiscoveryOutcome.Qualified -> batch
        }
}
