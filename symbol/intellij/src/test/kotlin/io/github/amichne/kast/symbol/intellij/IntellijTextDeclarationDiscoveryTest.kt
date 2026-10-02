package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The indexed callback is scripted; production ownership/projection runs with physical Kotlin PSI. */
class IntellijTextDeclarationDiscoveryTest {
    @Test
    fun `text discovery deduplicates exact owners while preserving lexical ranges and unsupported hits`(
        @TempDir home: Path
    ) {
        withTextDiscoveryParser(home) { project ->
            val content =
                """
                // launchd has no declaration owner
                import launchd.Unsupported
                fun launchd(parameter: String) = "launchd launchd"
                fun other() = "launchd"
                """
                    .trimIndent()
            val fixture = fixture(project, home, content)
            val outcome = fixture.execute().outcome()
            assertEquals(listOf("launchd", "other"), outcome.batch().candidates.map { it.name.value })
            assertEquals(
                setOf(SymbolDiscoveryQualification.UNSUPPORTED_ITEM),
                (outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values,
            )
            for (candidate in outcome.batch().candidates) {
                assertInstanceOf(SymbolDiscoveryCandidateLocation.Declaration::class.java, candidate.location)
                val evidence = requireNotNull(candidate.textMatch)
                assertEquals(
                    "launchd",
                    content.substring(evidence.range.startInclusive.value, evidence.range.endExclusive.value),
                )
                assertEquals(
                    content.substring(
                        evidence.contextRange.startInclusive.value,
                        evidence.contextRange.endExclusive.value,
                    ),
                    evidence.context,
                )
                assertEquals(candidate.lease, evidence.lease)
                assertEquals(candidate.location.file, SymbolDiscoveryFileIdentity.Workspace(evidence.file))
            }
        }
    }

    @Test
    fun `excluded kinds and unsupported owners precede candidate capacity without climbing through them`(
        @TempDir home: Path
    ) {
        withTextDiscoveryParser(home) { project ->
            val content =
                """
                val launchd = "launchd"
                enum class Process { launchd }
                fun excluded(launchd: String) = launchd
                fun wanted() = "launchd"
                """
                    .trimIndent()
            val fixture = fixture(project, home, content, work = 2, kinds = setOf(CompilerSymbolKind.FUNCTION))
            val outcome = fixture.execute().outcome()
            assertEquals(listOf("excluded", "wanted"), outcome.batch().candidates.map { it.name.value })
            assertTrue(
                SymbolDiscoveryQualification.UNSUPPORTED_ITEM in
                    (outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values
            )
            assertFalse(SymbolDiscoveryQualification.WORK_LIMIT_REACHED in outcome.qualifications.values)
        }
    }

    @Test
    fun `out of scope files cannot exhaust eligible text capacity`(@TempDir home: Path) {
        withTextDiscoveryParser(home) { project ->
            val wanted = fixture(project, home, "fun wanted() = \"launchd\"", work = 1)
            val excludedRoot = Files.createDirectories(home.resolve("excluded"))
            val excluded = fixture(project, excludedRoot, "fun irrelevant() = \"launchd\"")
            val outcome =
                IntellijTextDeclarationDiscoveryQuery({ IntellijDiscoveryEnvironmentState.READY })
                    .discover(wanted.scope, wanted.request) { accept ->
                        repeat(1000) { assertTrue(accept(excluded.file, excluded.offsets.single())) }
                        accept(wanted.file, wanted.offsets.single())
                    }
                    .outcome()
            assertInstanceOf(SymbolDiscoveryOutcome.Complete::class.java, outcome)
            assertEquals(listOf("wanted"), outcome.batch().candidates.map { it.name.value })
            assertEquals(1L, outcome.batch().examinedWorkUnits.value)
        }
    }

    @Test
    fun `previous attempts retain their native work allowance`(@TempDir home: Path) {
        withTextDiscoveryParser(home) { project ->
            val fixture = fixture(project, home, "fun first() = \"launchd\"\nfun second() = \"launchd\"", work = 2)
            val allowance = IntellijDeclarationDiscoveryAllowance(fixture.request)
            assertTrue(allowance.consume())
            val outcome =
                IntellijTextDeclarationDiscoveryQuery({ IntellijDiscoveryEnvironmentState.READY })
                    .discover(fixture.scope, fixture.request, allowance) { accept ->
                        fixture.offsets.all { accept(fixture.file, it) }
                    }
                    .outcome()
            assertEquals(listOf("first"), outcome.batch().candidates.map { it.name.value })
            assertEquals(2L, outcome.batch().examinedWorkUnits.value)
            assertEquals(
                setOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED),
                (outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values,
            )
        }
    }

    @Test
    fun `elapsed allowance expires before entering a retried native provider`(@TempDir home: Path) {
        withTextDiscoveryParser(home) { project ->
            val fixture = fixture(project, home, "fun wanted() = \"launchd\"")
            var now = 0L
            val allowance = IntellijDeclarationDiscoveryAllowance(fixture.request, IntellijReadNanoClock { now })
            now = 10_000_000_000L
            val outcome =
                IntellijTextDeclarationDiscoveryQuery({ IntellijDiscoveryEnvironmentState.READY })
                    .discover(fixture.scope, fixture.request, allowance) {
                        error("Expired allowance must not restart index work")
                    }
                    .outcome()
            assertEquals(
                setOf(SymbolDiscoveryQualification.TIME_LIMIT_REACHED),
                (outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values,
            )
            assertTrue(outcome.batch.candidates.isEmpty())
            assertEquals(0L, outcome.batch.examinedWorkUnits.value)
        }
    }

    @Test
    fun `no-match and known-empty discovery avoid fabricated success and provider work`(@TempDir home: Path) {
        withTextDiscoveryParser(home) { project ->
            val fixture = fixture(project, home, "fun ordinary() = 1")
            assertTrue(fixture.execute().outcome() is SymbolDiscoveryOutcome.Complete)
            val empty =
                CompiledIntellijSearchScope(
                    fixture.request.scope.lease,
                    fixture.request.scope.scope,
                    emptyList(),
                    GlobalSearchScope.EMPTY_SCOPE,
                    population = IntellijScopePopulation.KNOWN_EMPTY,
                )
            val result =
                IntellijTextDeclarationDiscoveryQuery({ IntellijDiscoveryEnvironmentState.READY })
                    .discover(empty, fixture.request) {
                        error("Known empty scope must not enter indexed native provider")
                    }
                    .outcome()
            assertTrue(result is SymbolDiscoveryOutcome.Complete)
            assertTrue(result.batch().candidates.isEmpty())
        }
    }

    private data class Fixture(
        val request: SymbolDiscoveryRequest,
        val scope: CompiledIntellijSearchScope,
        val file: PsiElement,
        val offsets: List<Int>,
    ) {
        fun execute() =
            IntellijTextDeclarationDiscoveryQuery({ IntellijDiscoveryEnvironmentState.READY }).discover(
                scope,
                request,
            ) { accept ->
                offsets.all { accept(file, it) }
            }
    }

    private fun fixture(
        project: Project,
        home: Path,
        content: String,
        work: Long = 100,
        kinds: Set<CompilerSymbolKind> = CompilerSymbolKind.entries.toSet(),
    ): Fixture {
        val root = home.toRealPath()
        val path = root.resolve("App.kt")
        Files.writeString(path, content)
        val virtual =
            requireNotNull(VirtualFileManager.getInstance().getFileSystem("file").findFileByPath(path.toString()))
        val file = requireNotNull(PsiManager.getInstance(project).findFile(virtual))
        val lease =
            SemanticReadLease(
                CanonicalWorkspaceRoot.fromCanonicalPath(root).refined(),
                EvidenceGeneration.parse(1).refined(),
            )
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val request =
            SymbolDiscoveryRequest(
                SymbolSearchScopeRequest(lease, scope),
                SymbolDiscoveryTarget.TextDeclarations(SymbolDiscoveryWord.parse("launchd").refined()),
                SymbolDiscoveryBudget(
                    ResourceBudget(
                        ResultLimit.parse(10).refined(),
                        WorkUnitLimit.parse(work).refined(),
                        ElapsedTimeLimitMillis.parse(10000).refined(),
                    ),
                    SymbolDiscoveryByteLimit.parse(10000).refined(),
                ),
                SymbolDiscoveryConstraints(null, null, SymbolDiscoveryDeclarationKinds.from(kinds).refined()),
            )
        val native =
            object : GlobalSearchScope(project) {
                override fun contains(file: com.intellij.openapi.vfs.VirtualFile) = file == virtual

                override fun isSearchInModuleContent(module: com.intellij.openapi.module.Module) = false

                override fun isSearchInLibraries() = false
            }
        val compiled = CompiledIntellijSearchScope(lease, scope, emptyList(), native)
        return Fixture(request, compiled, file, Regex("launchd").findAll(content).map { it.range.first }.toList())
    }

    private fun IntellijNativeDiscoveryExecution.outcome() = (this as IntellijNativeDiscoveryExecution.Produced).outcome

    private fun SymbolDiscoveryOutcome.batch() =
        when (this) {
            is SymbolDiscoveryOutcome.Complete -> batch
            is SymbolDiscoveryOutcome.Qualified -> batch
        }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
