package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** File-backed PSI discovery proof only; compiler identity and native indexes require separate qualification. */
class ScopedLocalDeclarationDiscoveryTest {
    @Test
    fun `fuzzy discovery descends excluded function containers and preserves shadowed local properties`(
        @TempDir home: Path
    ) = discoverLocals(home, SymbolDiscoveryMatch.FUZZY)

    @Test
    fun `exact local name admission filters unrelated declarations before capacity`(@TempDir home: Path) =
        discoverLocals(home, SymbolDiscoveryMatch.EXACT_NAME)

    @Test
    fun `exact named functions are discovered inside property initializers`(@TempDir home: Path) =
        withParser(home) { project ->
            val text = "val container = run { fun selected() = 1; selected() }; fun outer() { fun selected() = 2 }"
            val fixture =
                fixture(
                    project,
                    home.resolve("local-functions"),
                    mapOf("Functions.kt" to text),
                    setOf(CompilerSymbolKind.FUNCTION),
                )
            val initial = fixture.request(2, 2, 64_000, null)
            val request =
                SymbolDiscoveryRequest(
                    initial.scope,
                    SymbolDiscoveryTarget.Name(
                        SymbolNameDiscoveryKind.SYMBOL,
                        refinedPattern("selected"),
                        SymbolDiscoveryMatch.EXACT_NAME,
                    ),
                    initial.budget,
                    initial.constraints,
                )
            val query =
                IntellijNativeDiscoveryQuery(
                    environmentState = { IntellijDiscoveryEnvironmentState.READY },
                    cancellationCheck = {},
                )
            val result =
                query
                    .discoverDeclarations(fixture.scope, request) { observe, qualify, accept ->
                        ScopedKotlinDeclarationVisitor(
                                PsiManager.getInstance(project),
                                request.constraints,
                                request.requestedDeclarationKinds(),
                                observe,
                                qualify,
                                accept,
                            )
                            .read(fixture.files.single().virtualFile)
                    }
                    .outcome()
            assertTrue(result is SymbolDiscoveryOutcome.Complete)
            assertEquals(
                listOf(text.indexOf("fun selected() = 1"), text.indexOf("fun selected() = 2")),
                result.batch().candidates.map {
                    (it.location as SymbolDiscoveryCandidateLocation.Declaration).offset.value
                },
            )
        }

    private fun discoverLocals(home: Path, match: SymbolDiscoveryMatch) {
        withParser(home) { project ->
            val noise = (1..40).joinToString("; ") { "val unrelated$it = $it" }
            val text =
                "fun excludedContainer() { $noise; val shared = 1; run { val shared = 2 }; fun local() { val shared = 3 } }"
            val fixture =
                fixture(
                    project,
                    home.resolve("local-discovery"),
                    mapOf("Locals.kt" to text),
                    setOf(CompilerSymbolKind.PROPERTY),
                )
            val initial = fixture.request(3, 3, 64_000, null)
            val request =
                SymbolDiscoveryRequest(
                    initial.scope,
                    SymbolDiscoveryTarget.Name(SymbolNameDiscoveryKind.SYMBOL, refinedPattern("shared"), match),
                    initial.budget,
                    initial.constraints,
                )
            val query =
                IntellijNativeDiscoveryQuery(
                    environmentState = { IntellijDiscoveryEnvironmentState.READY },
                    cancellationCheck = {},
                )
            val result =
                query
                    .discoverDeclarations(fixture.scope, request) { observe, qualify, accept ->
                        ScopedKotlinDeclarationVisitor(
                                PsiManager.getInstance(project),
                                request.constraints,
                                request.requestedDeclarationKinds(),
                                observe,
                                qualify,
                                accept,
                            )
                            .read(fixture.files.single().virtualFile)
                    }
                    .outcome()
            assertTrue(result is SymbolDiscoveryOutcome.Complete)
            assertEquals(listOf("shared", "shared", "shared"), result.batch().candidates.map { it.name.value })
            assertEquals(
                listOf(text.indexOf("val shared = 1"), text.indexOf("val shared = 2"), text.indexOf("val shared = 3")),
                result.batch().candidates.map {
                    (it.location as SymbolDiscoveryCandidateLocation.Declaration).offset.value
                },
            )
            assertTrue(request.usesScopedDeclarationEnumeration())
        }
    }

    private fun refinedPattern(value: String) =
        when (val result = SymbolDiscoveryPattern.parse(value)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error(result.failure.toString())
        }
}
