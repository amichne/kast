package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
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
import org.jetbrains.kotlin.idea.KotlinFileType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The immutable specification tree serves the actual provider requests at each unrelated-file size. */
class DiscoveryDirectoryTreeWorkTest {
    @Test
    fun `direct tree excludes unrelated growth and duplicate names before supply`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("repo"),
                    DirectoryDiscoveryTree.sources,
                    sourceRootDirectory = DirectoryDiscoveryTree.MAIN,
                )
            compare(project, source, SymbolDiscoveryContainment.DIRECT)
        }

    @Test
    fun `descendant tree excludes unrelated growth and duplicate names before supply`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("repo"),
                    DirectoryDiscoveryTree.sources,
                    sourceRootDirectory = DirectoryDiscoveryTree.MAIN,
                )
            compare(project, source, SymbolDiscoveryContainment.DESCENDANTS)
        }

    private fun compare(
        project: Project,
        source: IntellijDeclarationDiscoveryTestFixture.Fixture,
        traversal: SymbolDiscoveryContainment,
    ) {
        val base = source.request(20, 10_000, 100_000, null)
        val request =
            SymbolDiscoveryRequest(
                base.scope,
                SymbolDiscoveryTarget.Name(
                    SymbolNameDiscoveryKind.CLASS,
                    SymbolDiscoveryPattern.parse("Target").refined(),
                    SymbolDiscoveryMatch.EXACT_NAME,
                ),
                base.budget,
                base.constraints.copy(
                    directory =
                        SymbolDiscoveryDirectoryConstraint(
                            SymbolDiscoveryDirectory.parse(DirectoryDiscoveryTree.SELECTED).refined(),
                            traversal,
                        ),
                    declarationKinds = null,
                ),
            )
        var expected: List<io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate>? = null
        val failures = mutableListOf<String>()
        for (noise in listOf(0, 100, 100_000)) {
            val sample = sample(project, source, request, noise)
            if (expected == null) expected = sample.candidates else assertEquals(expected, sample.candidates)
            if (sample.providers != 0 || sample.outside != 0)
                failures += "noise=$noise providers=${sample.providers} outside=${sample.outside}"
        }
        assertEquals(emptyList<String>(), failures)
    }

    private fun sample(
        project: Project,
        source: IntellijDeclarationDiscoveryTestFixture.Fixture,
        request: SymbolDiscoveryRequest,
        noise: Int,
    ): Sample {
        val observation = RecordingDiscoveryObservation { 0L }
        val indexes = TreeIndexes(source, noise)
        val query =
            IntellijNativeDiscoveryQuery(
                environmentState = { IntellijDiscoveryEnvironmentState.READY },
                cancellationCheck = {},
                clock = IntellijReadNanoClock { 0L },
                observation = observation,
            )
        val scope = compileScope(project, source, request, observation)
        val result = query.discoverNative(project, scope, request, indexes = indexes).outcome()
        assertTrue(result is SymbolDiscoveryOutcome.Complete)
        val candidates = result.batch().candidates
        assertEquals(listOf("Target"), candidates.map { it.name.value })
        assertEquals(
            source.scope.lease.workspaceRoot.value + "/${DirectoryDiscoveryTree.SELECTED}/Target.kt",
            candidates.single().location.file.stableValue,
        )
        assertEquals(0, (candidates.single().location as SymbolDiscoveryCandidateLocation.Declaration).offset.value)
        assertEquals(observation.calls.values.sum(), observation.terminals.values.sum())
        assertTrue(
            observation.terminals.keys.all {
                it.outcome == io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome.RETURNED
            }
        )
        println(
            "tree-work traversal=${request.constraints.directory?.containment} noise=$noise " +
                "providers=${indexes.providers} outside=${indexes.outside} " +
                "calls=${observation.calls} counters=${observation.counters} " +
                "terminals=${observation.terminals} work=${result.batch().examinedWorkUnits.value}"
        )
        return Sample(candidates, indexes.providers, indexes.outside)
    }

    private data class Sample(
        val candidates: List<io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate>,
        val providers: Int,
        val outside: Int,
    )

    private fun compileScope(
        project: Project,
        source: IntellijDeclarationDiscoveryTestFixture.Fixture,
        request: SymbolDiscoveryRequest,
        observation: RecordingDiscoveryObservation,
    ): CompiledIntellijSearchScope {
        val root = Path.of(source.scope.lease.workspaceRoot.value)
        val model =
            io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel.compile(
                source.scope.lease.workspaceRoot,
                io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState.COMPLETE,
                listOf(DirectoryDiscoveryTree.MAIN, DirectoryDiscoveryTree.TEST).map { relative ->
                    io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary(
                        "fixture",
                        root,
                        ":app",
                        if (relative == DirectoryDiscoveryTree.MAIN) "main" else "test",
                        root.resolve(relative),
                        if (relative == DirectoryDiscoveryTree.MAIN)
                            io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind.PRODUCTION
                        else io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind.TEST,
                        io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance.AUTHORED,
                    )
                },
            )
        return (IntellijSearchScopeCompiler(request.constraints, observation = observation)
                .compile(
                    request.scope,
                    model,
                    GlobalSearchScope.allScope(project),
                    ::nativePath,
                    { IntellijLibraryMembership.NOT_LIBRARY },
                    { true },
                ) as IntellijSearchScopeCompilation.Compiled)
            .capability
    }

    private class TreeIndexes(
        private val source: IntellijDeclarationDiscoveryTestFixture.Fixture,
        private val noise: Int,
    ) : IntellijDeclarationIndexes {
        var providers = 0
        var outside = 0
        private val selected =
            source.files.single {
                it.virtualFile.path.endsWith("/selected/Target.kt") && "/main/" in it.virtualFile.path
            }
        private val repeated = source.files.single { "/unrelated/Target.kt" in it.virtualFile.path }
        private val root = Path.of(source.scope.lease.workspaceRoot.value)

        override fun named(
            index: IntellijNamedDeclarationIndex,
            name: String,
            scope: GlobalSearchScope,
            accept: (NavigationItem) -> Boolean,
        ): Boolean {
            assertEquals(IntellijNamedDeclarationIndex.CLASS, index)
            assertEquals("Target", name)
            providers++
            val inputs = sequence {
                yield(repeated.virtualFile)
                repeat(noise) { yield(NoiseFile(root.resolve("${DirectoryDiscoveryTree.MAIN}/unrelated/Noise$it.kt"))) }
                yield(selected.virtualFile)
            }
            return inputs.all { file ->
                if (file !== selected.virtualFile) outside++
                if (!scope.contains(file)) true
                else
                    when (file) {
                        selected.virtualFile -> accept(selected.declarations.single())
                        repeated.virtualFile -> accept(repeated.declarations.single())
                        else -> throw AssertionError("No declaration projection is scripted for an excluded file")
                    }
            }
        }

        override fun kotlinFiles(scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean {
            providers++
            val inputs = sequence {
                yieldAll(source.files.map { it.virtualFile })
                repeat(noise) { yield(NoiseFile(root.resolve("${DirectoryDiscoveryTree.MAIN}/unrelated/Noise$it.kt"))) }
            }
            return inputs.all { file ->
                if (!file.path.startsWith(root.resolve(DirectoryDiscoveryTree.SELECTED).toString() + "/")) outside++
                !scope.contains(file) || accept(file)
            }
        }

        override fun packageFiles(
            packageName: String,
            scope: GlobalSearchScope,
            accept: (VirtualFile) -> Boolean,
        ): Boolean = throw AssertionError("Unexpected package provider")
    }

    private class NoiseFile(private val source: Path) :
        LightVirtualFile(source.fileName.toString(), KotlinFileType.INSTANCE, "class Target") {
        override fun getPath(): String = source.toString()

        override fun toNioPath(): Path = source
    }

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
