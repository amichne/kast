package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.*
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScopedFuzzyScaleTest {
    @Test
    fun `typos retain best main declaration across 200 synthetic Gradle modules before projection`() {
        for (pattern in listOf("CatalogClientRepsonse", "CatalogClientRespose")) {
            val noise = List(10_000) { "Noise${it}${pattern}" }
            val preferred = "CatalogClientResponse"
            val weak = "A${pattern}Adapter"
            val dropped = "Z${pattern}Adapter"
            val excludedPackage = "Other${pattern}"
            val excludedTest = "Test${pattern}"
            val names = noise + listOf(excludedPackage, excludedTest, weak, dropped, preferred)
            val roots = roots()
            val counts = mutableMapOf<IntellijReadCounter, Int>()
            val scenario =
                SymbolDiscoveryTest()
                    .fixture(
                        pattern = pattern,
                        declarationNames = names,
                        resultLimit = 1,
                        workLimit = 1,
                        clock = { 0L },
                        directory = "module199/src",
                        packageName = "sample.catalog",
                        declarationKinds = setOf(CompilerSymbolKind.CLASSLIKE),
                        sourceSets =
                            SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("main").refined()))
                                .refined(),
                        sourceRoots = roots,
                        itemPaths =
                            names.associateWith { name ->
                                when {
                                    name == excludedTest -> "/workspace/module199/src/test/$name.kt"
                                    name in noise -> "/workspace/module${noise.indexOf(name) % 199}/src/main/$name.kt"
                                    else -> "/workspace/module199/src/main/$name.kt"
                                }
                            },
                        itemPackages =
                            names.associateWith { if (it == excludedPackage) "sample.other" else "sample.catalog" },
                        itemKinds = names.associateWith { CompilerSymbolKind.CLASSLIKE },
                        observation =
                            object : IntellijReadObservation {
                                override fun count(
                                    counter: IntellijReadCounter,
                                    contributor: IntellijReadContributor,
                                    amount: Int,
                                ) {
                                    counts[counter] = (counts[counter] ?: 0) + amount
                                }

                                override fun terminated(
                                    reason: IntellijReadTermination,
                                    contributor: IntellijReadContributor,
                                ) = Unit
                            },
                    )
            val execution =
                scenario.query.discoverDeclarations(scenario.compiledScope, scenario.request) { _, _, accept ->
                    for (name in names) {
                        val outsideCallback = mutableListOf<NavigationItem>()
                        scenario.contributor.processElementsWithName(
                            name,
                            Processor { outsideCallback.add(it) },
                            FindSymbolParameters.wrap(name, scenario.scope),
                        )
                        for (item in outsideCallback) if (!accept(item)) return@discoverDeclarations false
                    }
                    true
                }
            val outcome = (execution as IntellijNativeDiscoveryExecution.Produced).outcome
            assertTrue(outcome is SymbolDiscoveryOutcome.Qualified)
            val batch = (outcome as SymbolDiscoveryOutcome.Qualified).batch
            assertEquals(listOf(preferred), batch.candidates.map { it.name.value })
            assertEquals(listOf(preferred), scenario.projectedNames)
            assertEquals(1L, batch.examinedWorkUnits.value)
            assertEquals(1, counts[IntellijReadCounter.LEXICAL_CANDIDATES_REPLACED])
            assertEquals(1, counts[IntellijReadCounter.LEXICAL_CANDIDATES_DROPPED])
            assertEquals(10_002, counts[IntellijReadCounter.SCOPE_FILTERED])
        }
    }

    private fun roots(): List<ModelOwnedSourceRoot> {
        val boundaries =
            (0 until 200).flatMap { module ->
                listOf("main", "test").map { sourceSet ->
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = "module$module.$sourceSet",
                        linkedBuildRoot = Path.of("/workspace"),
                        gradleProjectPath = ":module$module",
                        sourceSetName = sourceSet,
                        sourceRoot = Path.of("/workspace/module$module/src/$sourceSet"),
                        sourceKind =
                            if (sourceSet == "main") WorkspaceSourceRootKind.PRODUCTION
                            else WorkspaceSourceRootKind.TEST,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                }
            }
        val model =
            WorkspaceSearchScopeModel.compile(
                CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                ImportedWorkspaceModelState.COMPLETE,
                boundaries,
            )
        return (model as WorkspaceSearchScopeModelCompilation.Compiled).model.sourceRoots
    }

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
