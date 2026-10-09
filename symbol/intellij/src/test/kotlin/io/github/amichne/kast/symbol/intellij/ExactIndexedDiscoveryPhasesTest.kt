package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real Kotlin PSI and production admission; index supply/clock injected at their existing effect boundaries. */
class ExactIndexedDiscoveryPhasesTest {
    @Test
    fun `fallback timeout retains indexed declarations with incomplete coverage`(@TempDir home: Path) =
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("timeout"), mapOf("Cache.kt" to "interface CacheManager"))
            val initial = fixture.request(1, 1, 64_000, null)
            val request = exactRequest(initial, "CacheManager")
            var now = 0L
            val query =
                IntellijNativeDiscoveryQuery(
                    environmentState = { IntellijDiscoveryEnvironmentState.READY },
                    cancellationCheck = {},
                    clock = IntellijReadNanoClock { now },
                )
            val outcome =
                query
                    .discoverIndexed(
                        fixture.scope,
                        request,
                        IntellijReadContributor.EXACT_INDEX,
                        process = { _, _, accept ->
                            accept(
                                IntellijDiscoveryDeclarationInput.Indexed(fixture.files.single().declarations.single())
                            )
                        },
                        continuation = { observe, _, _ ->
                            now = 10_000_000_000L
                            observe()
                        },
                    )
                    .outcome()
            assertTrue(outcome is SymbolDiscoveryOutcome.Qualified)
            assertEquals(listOf("CacheManager"), outcome.batch().candidates.map { it.name.value })
            assertEquals(1L, outcome.batch().examinedWorkUnits.value)
            assertTrue(
                SymbolDiscoveryQualification.TIME_LIMIT_REACHED in
                    (outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values
            )
        }

    @Test
    fun `interrupted index keeps partial evidence and does not claim completion from fallback`(@TempDir home: Path) =
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("interrupted"), mapOf("Cache.kt" to "interface CacheManager"))
            val request = exactRequest(fixture.request(1, 1, 64_000, null), "CacheManager")
            val query =
                IntellijNativeDiscoveryQuery(
                    environmentState = { IntellijDiscoveryEnvironmentState.READY },
                    cancellationCheck = {},
                )
            val outcome =
                query
                    .discoverIndexed(
                        fixture.scope,
                        request,
                        IntellijReadContributor.EXACT_INDEX,
                        process = { _, _, accept ->
                            accept(
                                IntellijDiscoveryDeclarationInput.Indexed(fixture.files.single().declarations.single())
                            )
                            false
                        },
                        continuation = { _, _, _ ->
                            error("An incomplete index cannot establish a complete combined discovery")
                        },
                    )
                    .outcome()
            assertTrue(outcome is SymbolDiscoveryOutcome.Qualified)
            assertEquals(listOf("CacheManager"), outcome.batch().candidates.map { it.name.value })
            assertEquals(
                setOf(SymbolDiscoveryQualification.PROVIDER_FAILURE),
                (outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values.toSet(),
            )
        }

    @Test
    fun `scoped fallback retains local matches and does not spend duplicate index capacity`(@TempDir home: Path) =
        withParser(home) { project ->
            val source = "val shared = 0; fun outer() { val noise = 1; val shared = 2 }"
            val fixture =
                fixture(
                    project,
                    home.resolve("complete"),
                    mapOf("Cache.kt" to source),
                    setOf(CompilerSymbolKind.PROPERTY),
                )
            val request = exactRequest(fixture.request(2, 2, 64_000, null), "shared")
            val query =
                IntellijNativeDiscoveryQuery(
                    environmentState = { IntellijDiscoveryEnvironmentState.READY },
                    cancellationCheck = {},
                )
            val outcome =
                query
                    .discoverIndexed(
                        fixture.scope,
                        request,
                        IntellijReadContributor.EXACT_INDEX,
                        process = { _, _, accept ->
                            accept(
                                IntellijDiscoveryDeclarationInput.Indexed(fixture.files.single().declarations.first())
                            )
                        },
                        continuation = { observe, qualify, accept ->
                            ScopedKotlinDeclarationVisitor(
                                    PsiManager.getInstance(project),
                                    request.constraints,
                                    request.requestedDeclarationKinds(),
                                    observe,
                                    qualify,
                                    { accept(IntellijDiscoveryDeclarationInput.Scoped(it)) },
                                )
                                .read(fixture.files.single().virtualFile)
                        },
                    )
                    .outcome()
            assertTrue(outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(
                listOf(0, source.indexOf("val shared = 2")),
                outcome.batch().candidates.map {
                    (it.location as SymbolDiscoveryCandidateLocation.Declaration).offset.value
                },
            )
            assertEquals(2L, outcome.batch().examinedWorkUnits.value)
        }

    private fun exactRequest(initial: SymbolDiscoveryRequest, name: String) =
        SymbolDiscoveryRequest(
            initial.scope,
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                (SymbolDiscoveryPattern.parse(name) as Refinement.Refined).value,
                SymbolDiscoveryMatch.EXACT_NAME,
            ),
            initial.budget,
            initial.constraints,
        )
}
