package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearch
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame

/** Real dispatcher, scope compiler and Kotlin PSI. Only native index supply and elapsed observations are scripted. */
internal class ScopedDiscoveryDispatcherFixture(private val project: Project, requestedRoot: Path) {
    private val source =
        fixture(
            project,
            requestedRoot,
            (0 until 14).associate {
                "src/narrow/File$it.kt" to if (it == 0) "interface Target" else "class Noise$it"
            } + ("src/other/Excluded.kt" to "interface Target"),
            setOf(CompilerSymbolKind.CLASSLIKE),
            sourceRootDirectory = "src",
            directory = "src/narrow",
        )
    private val root = Path.of(source.scope.lease.workspaceRoot.value)
    private val initial = source.request(1, 1, 64_000, null)
    val request =
        SymbolDiscoveryRequest(
            initial.scope,
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.CLASS,
                (SymbolDiscoveryPattern.parse("Target") as Refinement.Refined).value,
                SymbolDiscoveryMatch.EXACT_NAME,
            ),
            initial.budget,
            initial.constraints.copy(
                sourceSets =
                    (SymbolDiscoverySourceSets.Exact.from(setOf(source.scope.sourceRoots.single().sourceSet))
                            as Refinement.Refined)
                        .value
            ),
        )
    val admittedFiles = source.files.filter { "/narrow/" in it.virtualFile.path }
    val excludedFile = source.files.single { "/other/" in it.virtualFile.path }
    val target = admittedFiles.single { it.name == "File0.kt" }.declarations.single()
    var now = 0L
    val observation = RecordingDiscoveryObservation { now }

    fun scope(
        observe: IntellijReadObservation = observation,
        unrelatedRoots: Int = 0,
        fileAdmission: (Path) -> Boolean = { true },
    ): CompiledIntellijSearchScope {
        val model =
            WorkspaceSearchScopeModel.compile(
                source.scope.lease.workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        "fixture",
                        root,
                        ":",
                        "main",
                        root.resolve("src"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ) +
                    (0 until unrelatedRoots).map { index ->
                        WorkspaceSourceRootBoundary(
                            "unrelated$index",
                            root,
                            ":",
                            "main",
                            root.resolve("unrelated$index/src"),
                            WorkspaceSourceRootKind.PRODUCTION,
                            WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    },
            )
        return (IntellijSearchScopeCompiler(request.constraints, observation = observe, fileAdmission = fileAdmission)
                .compile(
                    request.scope,
                    model,
                    object : GlobalSearchScope(project) {
                        override fun contains(file: VirtualFile) = true

                        override fun isSearchInModuleContent(module: com.intellij.openapi.module.Module) = true

                        override fun isSearchInLibraries() = false
                    },
                    ::nativePath,
                    { IntellijLibraryMembership.NOT_LIBRARY },
                    sourceMembership = { true },
                ) as IntellijSearchScopeCompilation.Compiled)
            .capability
    }

    fun query(observe: IntellijReadObservation = observation, cancellationCheck: () -> Unit = {}) =
        IntellijNativeDiscoveryQuery(
            environmentState = { IntellijDiscoveryEnvironmentState.READY },
            cancellationCheck = cancellationCheck,
            clock = IntellijReadNanoClock { now },
            observation = observe,
        )

    /** Strict supply: no name inventory, other declaration family, package index or second invocation is permitted. */
    inner class Indexes(
        private val expectedScope: GlobalSearchScope,
        private val fileSupply: (GlobalSearchScope, (VirtualFile) -> Boolean) -> Boolean,
        private val completeNamed: Boolean = true,
    ) : IntellijDeclarationIndexes {
        val effects = mutableListOf<String>()
        private var namedCalls = 0
        private var fileCalls = 0

        override fun named(
            index: IntellijNamedDeclarationIndex,
            name: String,
            scope: GlobalSearchScope,
            accept: (NavigationItem) -> Boolean,
        ): Boolean {
            assertEquals(0, namedCalls++)
            assertEquals(IntellijNamedDeclarationIndex.CLASS, index)
            assertEquals("Target", name)
            assertSame(expectedScope, scope)
            effects += "named"
            assertEquals(true, accept(target))
            effects += "named-return"
            return completeNamed
        }

        override fun kotlinFiles(scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean {
            assertEquals(1, namedCalls)
            assertEquals(0, fileCalls++)
            assertSame(expectedScope, scope)
            effects += "files"
            return fileSupply(scope, accept).also { effects += "files-return" }
        }

        override fun packageFiles(
            packageName: String,
            scope: GlobalSearchScope,
            accept: (VirtualFile) -> Boolean,
        ): Boolean = throw AssertionError("Unexpected package index")

        fun assertConsumed(files: Int = 1) {
            assertEquals(1, namedCalls)
            assertEquals(files, fileCalls)
        }
    }
}

internal class RecordingDiscoveryObservation(private val clock: () -> Long) : IntellijReadObservation {
    val calls = mutableMapOf<IntellijReadCall, Int>()
    val counters = mutableMapOf<IntellijReadCounter, Int>()
    private val activeCalls = ArrayDeque<IntellijReadCall>()

    data class Terminal(
        val call: IntellijReadCall,
        val parent: IntellijReadCall?,
        val phase: IntellijReadPhase,
        val outcome: IntellijReadCallOutcome,
    )

    val terminals = mutableMapOf<Terminal, Int>()
    private var phase = IntellijReadPhase.DISCOVERY_INVENTORY

    override fun phase(value: IntellijReadPhase) {
        phase = value
    }

    val searches = mutableListOf<Search>()

    data class Search(
        val kind: IntellijReadSearch,
        val started: Long,
        var callbacks: Int = 0,
        var firstCallback: Long? = null,
        var outcome: IntellijReadCallOutcome? = null,
    )

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        calls[call] = (calls[call] ?: 0) + 1
        val parent = activeCalls.lastOrNull()
        val enteredPhase = phase
        activeCalls.addLast(call)
        return object : IntellijReadCallScope {
            override fun finish(outcome: IntellijReadCallOutcome) {
                assertEquals(call, activeCalls.removeLast())
                val row = Terminal(call, parent, enteredPhase, outcome)
                terminals[row] = (terminals[row] ?: 0) + 1
            }
        }
    }

    override fun enterSearch(search: IntellijReadSearch): IntellijReadSearchScope {
        val row = Search(search, clock()).also(searches::add)
        val call = enterCall(search.call)
        return object : IntellijReadSearchScope {
            override fun callbackEntered() {
                row.callbacks++
                if (row.firstCallback == null) row.firstCallback = clock()
            }

            override fun finish(outcome: IntellijReadCallOutcome) {
                row.outcome = outcome
                call.finish(outcome)
            }
        }
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counters[counter] = (counters[counter] ?: 0) + amount
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
}
