package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectSourceMembership
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** A disposable indexed project uses real VFS, source admission, named dispatch and K2 identity. */
class DiscoveryDirectoryNativeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testDirectAndDescendantsPreserveExactCompilerIdentities() {
        val fixture = prepare()
        for (containment in SymbolDiscoveryContainment.entries) {
            val result = discover(fixture, containment)
            assertBounded(result, SymbolDiscoveryMatch.FUZZY)
            val expected =
                if (containment == SymbolDiscoveryContainment.DIRECT) listOf("Target")
                else listOf("Target", "TargetChild")
            assertEquals(expected, result.batch.candidates.map { it.name.value })
            assertEquals(
                if (containment == SymbolDiscoveryContainment.DIRECT) 1 else 2,
                result.observation.calls[IntellijReadCall.VFS_CHILDREN],
            )
            assertCompilerIdentities(result, expected, fixture.request.scope.lease)
            result.observation.assertFinished()
            println(
                "native-directory containment=$containment names=$expected " +
                    "calls=${result.observation.calls} counters=${result.observation.counters}"
            )
        }
    }

    fun testExactNameWorkVector() {
        val fixture = prepare()
        for (traversal in SymbolDiscoveryContainment.entries) {
            val result =
                discover(
                    fixture = fixture,
                    containment = traversal,
                    match = SymbolDiscoveryMatch.EXACT_NAME,
                )
            assertBounded(result, SymbolDiscoveryMatch.EXACT_NAME)
            assertEquals(listOf("Target"), result.batch.candidates.map { it.name.value })
            assertCompilerIdentities(result, listOf("Target"), fixture.request.scope.lease)
            result.observation.assertFinished()
            println(
                "native-work traversal=$traversal calls=${result.observation.calls} " +
                    "counters=${result.observation.counters} work=${result.batch.examinedWorkUnits.value}"
            )
        }
    }

    private fun assertBounded(result: Result, match: SymbolDiscoveryMatch) {
        assertEquals(0, result.observation.calls[IntellijReadCall.NAMED_DECLARATION_INDEX] ?: 0)
        assertEquals(
            when (match) {
                SymbolDiscoveryMatch.EXACT_NAME -> 1
                SymbolDiscoveryMatch.FUZZY -> 0
            },
            result.observation.calls[IntellijReadCall.SCOPED_FILE_INDEX] ?: 0,
        )
        assertEquals(0, result.observation.calls[IntellijReadCall.EXACT_PACKAGE_INDEX] ?: 0)
    }

    private fun assertCompilerIdentities(result: Result, expected: List<String>, authority: SemanticReadAuthority) {
        val query =
            IntellijSymbolSelectorQuery(
                lookup =
                    IntellijKotlinCompilerSymbolLookup(
                        IntellijPsiExactDeclarationLookup(project, result.observation),
                        result.observation,
                    ),
                environmentState = { project.discoveryEnvironmentState() },
                cancellationCheck = {},
                observation = result.observation,
            )
        ApplicationManager.getApplication().runReadAction {
            for (ordinal in expected.indices) {
                val selection = SymbolDiscoverySelection.select(result.batch, ordinal).refined()
                val resolved = query.resolve(result.scope, selection)
                assertTrue(resolved.toString(), resolved is IntellijSymbolSelectorResolution.Resolved)
                val selector = (resolved as IntellijSymbolSelectorResolution.Resolved).selector
                assertEquals(expected[ordinal], selector.name.value)
                assertEquals(CompilerSymbolKind.CLASSLIKE, selector.kind)
                assertEquals(
                    "proof.${expected[ordinal]}",
                    (selector.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value,
                )
                println(
                    "native-identity name=${expected[ordinal]} range=${selector.range} " +
                        "signature=${selector.signature} identity=${selector.compilerIdentity.value}"
                )
                assertEquals(DECLARATION_START, selector.range.startInclusive)
                assertEquals(if (ordinal == 0) TARGET_END else CHILD_END, selector.range.endExclusive)
                assertEquals(selector.lease, authority)
                assertTrue(query.describe(result.scope, selector) is IntellijSymbolDescriptionResolution.Described)
            }
        }
        assertEquals(expected.size * 2, result.observation.calls[IntellijReadCall.K2_ANALYSIS])
    }

    fun testAbsentNameExhaustsTheSelectedUniverse() {
        val result =
            discover(
                fixture = prepare(),
                containment = SymbolDiscoveryContainment.DESCENDANTS,
                pattern = "Absent",
                match = SymbolDiscoveryMatch.EXACT_NAME,
            )
        assertBounded(result, SymbolDiscoveryMatch.EXACT_NAME)
        assertTrue(result.batch.candidates.isEmpty())
        assertEquals(2, result.observation.calls[IntellijReadCall.VFS_CHILDREN])
        result.observation.assertFinished()
    }

    fun testLiveNativeSourceRejectionPreservesCompleteExclusion() {
        val fixture = prepare()
        ApplicationManager.getApplication().invokeAndWait {
            val rejected = folder(fixture.selected, "rejected")
            val rejectedFile = file(rejected, "Target.kt", "package excluded\nclass Target\n")
            WriteCommandAction.runWriteCommandAction(project) {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.contentEntries.single().addExcludeFolder(rejected)
                }
            }
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            ApplicationManager.getApplication().runReadAction {
                assertFalse(IntellijProjectSourceMembership.contains(project, rejectedFile))
            }
        }
        val result = discover(fixture, SymbolDiscoveryContainment.DESCENDANTS, directory = "src/selected/rejected")
        assertBounded(result, SymbolDiscoveryMatch.FUZZY)
        assertTrue(result.batch.candidates.isEmpty())
        assertEquals(0, result.observation.calls[IntellijReadCall.DISCOVERY_SCOPE_SOURCE_MEMBERSHIP] ?: 0)
        assertEquals(1, result.observation.counters[IntellijReadCounter.DISCOVERY_SCOPE_FILES_EXCLUDED])
        assertEquals(0, result.observation.calls[IntellijReadCall.PSI_FIND_FILE] ?: 0)
        assertEquals(0, result.observation.calls[IntellijReadCall.K2_ANALYSIS] ?: 0)
        result.observation.assertFinished()
    }

    private fun discover(
        fixture: Fixture,
        containment: SymbolDiscoveryContainment,
        pattern: String = "Target",
        match: SymbolDiscoveryMatch = SymbolDiscoveryMatch.FUZZY,
        directory: String = "src/selected",
    ): Result {
        val initial = fixture.request
        val request =
            SymbolDiscoveryRequest(
                scope = initial.scope,
                target =
                    SymbolDiscoveryTarget.Name(
                        SymbolNameDiscoveryKind.CLASS,
                        SymbolDiscoveryPattern.parse(pattern).refined(),
                        match,
                    ),
                budget = initial.budget,
                constraints =
                    initial.constraints.copy(
                        directory =
                            SymbolDiscoveryDirectoryConstraint(
                                SymbolDiscoveryDirectory.parse(directory).refined(),
                                containment,
                            )
                    ),
            )
        val observation = NativeDirectoryObservation()
        return ApplicationManager.getApplication().runReadAction<Result> {
            val scope =
                (IntellijSearchScopeCompiler(request.constraints, observation = observation)
                        .compile(project, request.scope, fixture.model) as IntellijSearchScopeCompilation.Compiled)
                    .capability
            val execution =
                IntellijNativeDiscoveryQuery(
                        environmentState = { project.discoveryEnvironmentState() },
                        cancellationCheck = {},
                        observation = observation,
                    )
                    .discoverNative(project, scope, request)
            val outcome = (execution as IntellijNativeDiscoveryExecution.Produced).outcome
            assertTrue(outcome.toString(), outcome is SymbolDiscoveryOutcome.Complete)
            val batch = (outcome as SymbolDiscoveryOutcome.Complete).batch

            Result(batch, scope, observation)
        }
    }

    private fun prepare(): Fixture {
        lateinit var fixture: Fixture
        ApplicationManager.getApplication().invokeAndWait { fixture = prepareOnEdt() }
        return fixture
    }

    private fun prepareOnEdt(): Fixture {
        val base = getOrCreateProjectBaseDir()
        val source =
            WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                base.createChildDirectory(this, "src")
            }
        val sdk = JavaSdk.getInstance().createJdk("directory-discovery-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.sdk = sdk
            model.addContentEntry(source).addSourceFolder(source, false)
        }
        val selected = folder(source, "selected")
        file(selected, "Target.kt", "package proof\nclass Target\n")
        file(selected, "Helpers.kt", "package proof\nfun helper() = 1\nval value = 2\ntypealias Alias = Target\n")
        file(folder(selected, "nested"), "TargetChild.kt", "package proof\nclass TargetChild\n")
        val unrelated = folder(source, "unrelated")
        file(unrelated, "Target.kt", "package other\nclass Target\n")
        repeat(UNRELATED_FILES) { file(unrelated, "Noise$it.kt", "package other\nclass Noise$it\n") }
        file(folder(source, "selected-other"), "Target.kt", "package sibling\nclass Target\n")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val request = nativeRequest(base)
        val root = request.scope.lease.workspaceRoot
        val model =
            WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = module.name,
                        linkedBuildRoot = base.toNioPath(),
                        gradleProjectPath = ":app",
                        sourceSetName = "main",
                        sourceRoot = source.toNioPath(),
                        sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            )
        return Fixture(request, model, selected)
    }

    private fun nativeRequest(base: VirtualFile): SymbolDiscoveryRequest {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(1).refined())
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_ONLY,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val request =
            SymbolDiscoveryRequest(
                SymbolSearchScopeRequest(lease, scope),
                SymbolDiscoveryTarget.Name(
                    SymbolNameDiscoveryKind.CLASS,
                    SymbolDiscoveryPattern.parse("Target").refined(),
                    SymbolDiscoveryMatch.FUZZY,
                ),
                SymbolDiscoveryBudget(
                    ResourceBudget(
                        ResultLimit.parse(20).refined(),
                        WorkUnitLimit.parse(10_000).refined(),
                        ElapsedTimeLimitMillis.parse(60_000).refined(),
                    ),
                    SymbolDiscoveryByteLimit.parse(100_000).refined(),
                ),
            )
        return request
    }

    private fun folder(parent: VirtualFile, name: String): VirtualFile =
        WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
            parent.createChildDirectory(this, name)
        }

    private fun file(parent: VirtualFile, name: String, text: String): VirtualFile =
        WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
            parent.createChildData(this, name).also { it.setBinaryContent(text.toByteArray()) }
        }

    private data class Fixture(
        val request: SymbolDiscoveryRequest,
        val model: WorkspaceSearchScopeModelCompilation,
        val selected: VirtualFile,
    )

    private data class Result(
        val batch: SymbolDiscoveryBatch,
        val scope: CompiledIntellijSearchScope,
        val observation: NativeDirectoryObservation,
    )

    private companion object {
        const val DECLARATION_START = 14
        const val TARGET_END = 26
        const val CHILD_END = 31
        const val UNRELATED_FILES = 100
    }

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}

private class NativeDirectoryObservation : IntellijReadObservation {
    val calls = mutableMapOf<IntellijReadCall, Int>()
    val counters = mutableMapOf<IntellijReadCounter, Int>()
    private val stack = ArrayDeque<IntellijReadCall>()
    private val events = mutableListOf<Triple<IntellijReadCall, IntellijReadCall?, IntellijReadCallOutcome>>()

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        calls[call] = (calls[call] ?: 0) + 1
        val parent = stack.lastOrNull()
        stack.addLast(call)
        return object : IntellijReadCallScope {
            override fun finish(outcome: IntellijReadCallOutcome) {
                check(stack.removeLast() == call)
                events += Triple(call, parent, outcome)
            }
        }
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counters[counter] = (counters[counter] ?: 0) + amount
    }

    override fun unexpected(failure: io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure) {
        error("Unexpected native failure: $failure")
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit

    fun assertFinished() {
        check(stack.isEmpty())
        check(events.size == calls.values.sum())
        check(events.all { it.third == IntellijReadCallOutcome.RETURNED })
    }
}
