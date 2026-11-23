package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ConstrainedSymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.intellij.SymbolDiscoveryTest.FakeContributor
import io.github.amichne.kast.symbol.intellij.SymbolDiscoveryTest.FakeItem
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A name selection that meets an enum entry is qualified with `ENUM_ENTRY_UNSELECTABLE`. The entry never consumes
 * pending or result capacity, and its package check stays outside native index callbacks.
 */
class EnumEntryNameDiscoveryTest {
    @Test
    fun `a name that matches only an enum entry is qualified instead of a complete empty selection`() {
        val scenario = Scenario(name(), listOf(FakeItem(TARGET, "Outcome")), enumEntries = setOf("Outcome"))
        val outcome = scenario.execute()
        assertTrue(outcome is SymbolDiscoveryOutcome.Qualified, outcome.toString())
        assertEquals(listOf(SymbolDiscoveryQualification.ENUM_ENTRY_UNSELECTABLE), outcome.qualifications())
        assertEquals(emptyList<String>(), outcome.candidateNames())
        assertEquals(emptyList<String>(), scenario.projected)
    }

    @Test
    fun `same-named enum entries leave the result capacity to a selected property`() {
        val entries = List(5) { FakeItem(TARGET, "Outcome$it") }
        val scenario =
            Scenario(
                name(resultLimit = 1),
                entries + FakeItem(TARGET, "Codes"),
                enumEntries = entries.mapTo(linkedSetOf(), FakeItem::identity),
                properties = setOf("Codes"),
            )
        val outcome = scenario.execute()
        // The property is selected; the entries keep the selection qualified without a result-limit stop.
        assertTrue(outcome is SymbolDiscoveryOutcome.Qualified, outcome.toString())
        assertEquals(listOf(SymbolDiscoveryQualification.ENUM_ENTRY_UNSELECTABLE), outcome.qualifications())
        val candidates = (outcome as SymbolDiscoveryOutcome.Qualified).batch.candidates
        assertEquals(listOf(TARGET), candidates.map { it.name.value })
        assertTrue(candidates.single().location.file.stableValue.endsWith("/src/Codes.kt"))
        assertEquals(listOf("Codes"), scenario.projected)
    }

    @Test
    fun `an all selection filters enum entries before the result capacity of other selected kinds`() {
        val entries = List(5) { FakeItem("Entry$it") }
        val scenario =
            Scenario(
                request(SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.SYMBOL), resultLimit = 1),
                entries + FakeItem("ZProperty"),
                enumEntries = entries.mapTo(linkedSetOf(), FakeItem::identity),
                properties = setOf("ZProperty"),
            )
        val outcome = scenario.execute()
        assertTrue(outcome is SymbolDiscoveryOutcome.Complete, outcome.toString())
        assertEquals(listOf("ZProperty"), outcome.candidateNames())
        assertEquals(listOf("ZProperty"), scenario.projected)
    }

    @Test
    fun `an enum entry owes its package check until native callbacks end`() {
        val cases =
            listOf(
                "sample.codes" to listOf(SymbolDiscoveryQualification.ENUM_ENTRY_UNSELECTABLE),
                "sample.other" to emptyList(),
            )
        for ((entryPackage, expected) in cases) {
            val scenario =
                Scenario(
                    name(packageName = "sample.codes"),
                    listOf(FakeItem(TARGET, "Outcome")),
                    enumEntries = setOf("Outcome"),
                    packages = mapOf("Outcome" to entryPackage),
                )
            val outcome = scenario.execute()
            assertEquals(expected, outcome.qualifications(), entryPackage)
            assertEquals(emptyList<String>(), outcome.candidateNames())
            assertEquals(1, scenario.packageReads, entryPackage)
        }
    }

    /** Native supply of [items] by name; classification, files and packages are explicit per item identity. */
    private class Scenario(
        private val request: SymbolDiscoveryRequest,
        items: List<FakeItem>,
        private val enumEntries: Set<String>,
        private val properties: Set<String> = emptySet(),
        private val packages: Map<String, String> = emptyMap(),
    ) {
        val projected = mutableListOf<String>()
        var packageReads = 0
            private set

        private val files = items.associateWith { LightVirtualFile("/workspace/src/${it.identity}.kt") }
        private val nativeScope =
            object : GlobalSearchScope() {
                override fun contains(file: VirtualFile): Boolean = file in files.values

                override fun isSearchInModuleContent(aModule: Module): Boolean = true

                override fun isSearchInLibraries(): Boolean = false
            }
        private val contributor =
            FakeContributor(
                items.map(FakeItem::candidateName).distinct(),
                items.groupBy(FakeItem::candidateName),
                false,
            )
        private val query =
            IntellijNativeDiscoveryQuery(
                itemFile = { item -> IntellijDiscoveryItemFileResult.Found(files.getValue(item as FakeItem)) },
                projector = { discoveryRequest, item, file ->
                    projected += (item as FakeItem).identity
                    SymbolDiscoveryCandidate.fromBoundary(
                        SymbolDiscoveryKind.SYMBOL,
                        item.candidateName,
                        discoveryRequest.scope.lease,
                        Path.of(file.path),
                        file.url,
                        0,
                    )
                },
                itemCompilerKind = { item ->
                    val identity = (item as FakeItem).identity
                    when (identity) {
                        in enumEntries -> IntellijDiscoveryItemCompilerKindResult.EnumEntry
                        in properties -> IntellijDiscoveryItemCompilerKindResult.Found(CompilerSymbolKind.PROPERTY)
                        else -> IntellijDiscoveryItemCompilerKindResult.Unsupported
                    }
                },
                itemPackage = { item ->
                    assertTrue(!contributor.processingElements, "package PSI must not run inside index callbacks")
                    packageReads += 1
                    packages[(item as FakeItem).identity]?.let(IntellijPackageEvidence::Known)
                        ?: IntellijPackageEvidence.Unavailable
                },
                environmentState = { IntellijDiscoveryEnvironmentState.READY },
                cancellationCheck = {},
                clock = IntellijDiscoveryStepClock(),
            )

        fun execute(): SymbolDiscoveryOutcome {
            val scope = CompiledIntellijSearchScope(request.scope.lease, request.scope.scope, emptyList(), nativeScope)
            return (query.discover(scope, request, listOf(contributor)) as IntellijNativeDiscoveryExecution.Produced)
                .outcome
        }
    }

    private fun SymbolDiscoveryOutcome.candidateNames(): List<String> =
        when (this) {
            is SymbolDiscoveryOutcome.Complete -> batch.candidates
            is SymbolDiscoveryOutcome.Qualified -> batch.candidates
        }.map { it.name.value }

    private fun SymbolDiscoveryOutcome.qualifications(): List<SymbolDiscoveryQualification> =
        when (this) {
            is SymbolDiscoveryOutcome.Complete -> emptyList()
            is SymbolDiscoveryOutcome.Qualified -> qualifications.values.toList()
        }

    private companion object {
        const val TARGET = "MIGRATION_REJECTED"

        fun name(resultLimit: Int = 10, packageName: String? = null): SymbolDiscoveryRequest =
            request(
                SymbolDiscoveryTarget.Name(
                    SymbolNameDiscoveryKind.SYMBOL,
                    SymbolDiscoveryPattern.parse(TARGET).refined(),
                    SymbolDiscoveryMatch.EXACT_NAME,
                ),
                resultLimit,
                packageName,
            )

        /** The public default declaration kinds: they include the classlike family that holds enum entries. */
        fun request(target: ConstrainedSymbolDiscoveryTarget, resultLimit: Int, packageName: String? = null) =
            SymbolDiscoveryRequest(
                scope =
                    SymbolSearchScopeRequest(
                        SemanticReadLease(
                            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
                            EvidenceGeneration.parse(1L).refined(),
                        ),
                        SymbolSearchScope.Workspace(
                            SymbolSourceKindPolicy.PRODUCTION_ONLY,
                            SymbolGeneratedSourcePolicy.EXCLUDE,
                            SymbolLibraryPolicy.EXCLUDE,
                        ),
                    ),
                target = target,
                budget =
                    SymbolDiscoveryBudget(
                        ResourceBudget(
                            ResultLimit.parse(resultLimit).refined(),
                            WorkUnitLimit.parse(100L).refined(),
                            ElapsedTimeLimitMillis.parse(60_000L).refined(),
                        ),
                        SymbolDiscoveryByteLimit.parse(100_000L).refined(),
                    ),
                constraints =
                    SymbolDiscoveryConstraints(
                        directory = null,
                        packageName =
                            packageName?.let {
                                SymbolDiscoveryPackageConstraint(
                                    SymbolDiscoveryPackage.parse(it).refined(),
                                    SymbolDiscoveryContainment.DIRECT,
                                )
                            },
                        declarationKinds =
                            SymbolDiscoveryDeclarationKinds.from(
                                    setOf(
                                        CompilerSymbolKind.CLASSLIKE,
                                        CompilerSymbolKind.FUNCTION,
                                        CompilerSymbolKind.PROPERTY,
                                        CompilerSymbolKind.TYPE_ALIAS,
                                    )
                                )
                                .refined(),
                    ),
            )

        fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error("Fixture rejected: $failure")
            }
    }
}
