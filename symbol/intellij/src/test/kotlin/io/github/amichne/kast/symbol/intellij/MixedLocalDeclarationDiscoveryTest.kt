package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Physical source traversal and one shared grant; index input is supplied at the existing producer boundary. */
class MixedLocalDeclarationDiscoveryTest {
    @Test
    fun `include libraries supplements exact indexed results with local declarations`(@TempDir home: Path) =
        discover(home, SymbolDiscoveryMatch.EXACT_NAME, 4)

    @Test
    fun `include libraries fuzzy sources consume the same result grant`(@TempDir home: Path) =
        discover(home, SymbolDiscoveryMatch.FUZZY, 2)

    private fun discover(home: Path, match: SymbolDiscoveryMatch, limit: Int) =
        withParser(home) { project ->
            val source =
                "val shared = 0; fun outer() { val shared = 1; run { val shared = 2 }; fun nested() { val shared = 3 } }"
            val fixture =
                fixture(
                    project,
                    home.resolve("mixed-discovery"),
                    mapOf("Locals.kt" to source),
                    setOf(CompilerSymbolKind.PROPERTY),
                )
            val scope = includeLibraryScope(fixture.scope)
            val initial = fixture.request(limit, 4, 64_000, null)
            val request =
                SymbolDiscoveryRequest(
                    SymbolSearchScopeRequest(scope.lease, scope.scope),
                    SymbolDiscoveryTarget.Name(SymbolNameDiscoveryKind.SYMBOL, pattern("shared"), match),
                    initial.budget,
                    initial.constraints,
                )
            val result = mixedOutcome(fixture, request, scope)
            assertEquals(limit, result.batch().candidates.size)
            assertEquals(limit, result.batch().candidates.distinct().size)
            assertEquals(List(limit) { "shared" }, result.batch().candidates.map { it.name.value })
            if (limit == 4) assertTrue(result is SymbolDiscoveryOutcome.Complete)
            else assertTrue(result is SymbolDiscoveryOutcome.Qualified)
        }

    private fun mixedOutcome(
        fixture: IntellijDeclarationDiscoveryTestFixture.Fixture,
        request: SymbolDiscoveryRequest,
        scope: CompiledIntellijSearchScope,
    ): SymbolDiscoveryOutcome {
        val query =
            IntellijNativeDiscoveryQuery(
                environmentState = { IntellijDiscoveryEnvironmentState.READY },
                cancellationCheck = {},
            )
        return query
            .discoverMixedDeclarations(scope, request) { observe, qualify, accept ->
                val indexed = fixture.files.single().declarations.first() as org.jetbrains.kotlin.psi.KtProperty
                val local =
                    com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                            fixture.files.single(),
                            org.jetbrains.kotlin.psi.KtProperty::class.java,
                        )
                        .first { it.isLocal }
                accept(IntellijDiscoveryDeclarationInput.Indexed(indexed)) &&
                    accept(IntellijDiscoveryDeclarationInput.Indexed(local)) &&
                    ScopedKotlinDeclarationVisitor(
                            PsiManager.getInstance(fixture.files.single().project),
                            request.constraints,
                            request.requestedDeclarationKinds(),
                            observe,
                            qualify,
                            { accept(IntellijDiscoveryDeclarationInput.Scoped(it)) },
                            localOnly = true,
                        )
                        .read(fixture.files.single().virtualFile)
            }
            .outcome()
    }

    private fun includeLibraryScope(scope: CompiledIntellijSearchScope): CompiledIntellijSearchScope =
        CompiledIntellijSearchScope(
            scope.lease,
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.INCLUDE,
            ),
            scope.sourceRoots,
            scope.nativeScope,
        )

    private fun pattern(value: String) =
        when (val parsed = SymbolDiscoveryPattern.parse(value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error(parsed.failure.toString())
        }
}
