package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Actual SDK index work over complete production inventories, with independent reference sites. */
class NativeReferencePartitionsTest : HeavyPlatformTestCase() {
    private val roots = mutableListOf<WorkspaceSourceRootBoundary>()
    private lateinit var caseSdk: com.intellij.openapi.projectRoots.Sdk

    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testUnrelatedGrowthDoesNotGrowFileIdChecksOrChangeReferences() {
        val fixture = fixture()
        addNoise(1)
        val baselineTiny = query(fixture, partitioned = false)
        val candidateTiny = query(fixture, partitioned = true)
        addNoise(ADDITIONAL_NOISE_MODULES)
        val baselineWide = query(fixture, partitioned = false)
        val candidateWide = query(fixture, partitioned = true)
        assertTrue(baselineTiny.fileIdChecks > 0)
        assertTrue(baselineWide.fileIdChecks > baselineTiny.fileIdChecks)
        assertEquals(0, candidateTiny.fileIdChecks)
        assertEquals(0, candidateWide.fileIdChecks)
        assertEquals(2, candidateTiny.calls[IntellijReadCall.REFERENCE_SEARCH])
        assertEquals(2, candidateWide.calls[IntellijReadCall.REFERENCE_SEARCH])
        println(
            "native-reference-partitions tiny=${baselineTiny.fileIdChecks}->${candidateTiny.fileIdChecks}; " +
                "wide=${baselineWide.fileIdChecks}->${candidateWide.fileIdChecks}; searches=1->2; exactSites=1"
        )
    }

    fun testProcessorStopAndNativeCancellationDoNotContinueThePartitions() {
        val fixture = fixture("package proof\nfun target() = 1\nfun first() = target()\n")
        val stopped = PartitionObservation()
        var callbacks = 0
        assertFalse(
            stopped.forEachReference(
                fixture.target,
                scope(fixture, stopped),
                nativeScopeTestAdmission(fixture.request, stopped),
            ) {
                callbacks++
                false
            }
        )
        assertEquals(1, callbacks)
        assertEquals(1, stopped.calls[IntellijReadCall.REFERENCE_SEARCH])
        val indicator = EmptyProgressIndicator()
        val cancelled = PartitionObservation()
        try {
            ProgressManager.getInstance()
                .runProcess(
                    {
                        cancelled.forEachReference(
                            fixture.target,
                            scope(fixture, cancelled),
                            nativeScopeTestAdmission(fixture.request, cancelled),
                        ) {
                            indicator.cancel()
                            ProgressManager.checkCanceled()
                            true
                        }
                    },
                    indicator,
                )
            fail("Expected cancellation from the native reference callback")
        } catch (_: ProcessCanceledException) {
            assertEquals(IntellijReadCallOutcome.CANCELLED, cancelled.outcomes.last())
            assertEquals(1, cancelled.calls[IntellijReadCall.REFERENCE_CALLBACK])
            assertEquals(1, cancelled.calls[IntellijReadCall.REFERENCE_SEARCH])
        }
    }

    fun testIntersectionsRemainDisjointAndAnEmptyCompleteScopeIsExhausted() {
        val fixture = fixture()
        val observed = PartitionObservation()
        val prepared = scope(fixture, observed) as EnumeratedRelationScope
        val parts = prepared.referencePartitions(nativeScopeTestAdmission(fixture.request, observed)).toList()
        assertEquals(2, parts.size)
        val targetFile = fixture.target.containingFile.virtualFile
        assertEquals(1, parts.count { it.contains(targetFile) })
        assertEquals(1, parts.count { it.contains(fixture.caller) })
        val narrowed = prepared.intersectWith(GlobalSearchScope.fileScope(project, targetFile))
        assertTrue(
            observed.forEachReference(fixture.target, narrowed, nativeScopeTestAdmission(fixture.request, observed)) {
                fail("No calls in the target file")
                false
            }
        )
        val empty = prepared.intersectWith(GlobalSearchScope.EMPTY_SCOPE)
        assertTrue(
            observed.forEachReference(fixture.target, empty, nativeScopeTestAdmission(fixture.request, observed)) {
                fail("Empty complete scope")
                false
            }
        )
    }

    fun testPartitionsBoundNativeQueryFanoutAndPreserveCombinedFallback() {
        val fixture = fixture()
        repeat(PARTITION_CAPACITY_EXTRA_FILES) { number ->
            file(fixture.caller.parent, "Extra$number.kt", "package proof\nval extra$number = 1\n")
        }
        ready()
        val atCapacity = scope(fixture, PartitionObservation()) as EnumeratedRelationScope
        assertEquals(
            EXPECTED_PARTITION_CAPACITY,
            atCapacity
                .referencePartitions(nativeScopeTestAdmission(fixture.request, IntellijReadObservation.None))
                .count(),
        )
        file(fixture.caller.parent, "Beyond.kt", "package proof\nval beyond = 1\n")
        ready()
        val original = scope(fixture, PartitionObservation()) as EnumeratedRelationScope
        assertSame(
            original,
            original
                .referencePartitions(nativeScopeTestAdmission(fixture.request, IntellijReadObservation.None))
                .single(),
        )
        val fallback = query(fixture, partitioned = true)
        assertEquals(1, fallback.calls[IntellijReadCall.REFERENCE_SEARCH])
        assertTrue(fallback.fileIdChecks > 0)
    }

    fun testKotlinDeclarationReferencesKeepBothJavaAndKotlinConsumers() {
        val fixture = fixture()
        val javaCaller =
            file(
                fixture.caller.parent,
                "JavaCaller.java",
                "package consumer; class JavaCaller { int call() { return proof.TargetKt.target(); } }",
            )
        ready()
        val observed = PartitionObservation()
        val selected = scope(fixture, observed)
        val expected = listOf(javaCaller.path, fixture.caller.path).sorted()
        val baseline = mutableListOf<String>()
        assertTrue(
            ReferencesSearch.search(fixture.target, selected, false)
                .forEach(
                    Processor { reference ->
                        assertSame(fixture.target, checkNotNull(reference.resolve()).navigationElement)
                        baseline += reference.element.containingFile.virtualFile.path
                        true
                    }
                )
        )
        assertEquals(expected, baseline.sorted())
        val sites = mutableListOf<String>()
        assertTrue(
            observed.forEachReference(fixture.target, selected, nativeScopeTestAdmission(fixture.request, observed)) {
                reference ->
                assertSame(fixture.target, checkNotNull(reference.resolve()).navigationElement)
                sites += reference.element.containingFile.virtualFile.path
                true
            }
        )
        assertEquals(expected, sites.sorted())
    }

    private fun fixture(targetText: String = "package proof\nfun target() = 1\n"): PartitionFixture {
        caseSdk = JavaSdk.getInstance().createJdk("partition-case-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(caseSdk)
        val source = sourceModule("target")
        val targetFile = file(source, "Target.kt", targetText)
        val caller = file(source, "Caller.kt", "package consumer\nfun caller() = proof.target()\n")
        ready()
        val target =
            (psiManager.findFile(targetFile) as KtFile).declarations.filterIsInstance<KtNamedFunction>().single {
                it.name == "target"
            }
        return PartitionFixture(
            target,
            caller,
            nativeRelationRequest(getOrCreateProjectBaseDir().toNioPath(), targetFile.toNioPath(), TARGET_OFFSET),
        )
    }

    private fun sourceModule(name: String): VirtualFile {
        val nativeModule = createModule(name)
        val source =
            WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                getOrCreateProjectBaseDir().createChildDirectory(this, name).createChildDirectory(this, "src")
            }
        ModuleRootModificationUtil.updateModel(nativeModule) { model ->
            model.sdk = caseSdk
            model.addContentEntry(source).addSourceFolder(source, false)
        }
        roots +=
            WorkspaceSourceRootBoundary(
                ideaModuleName = nativeModule.name,
                linkedBuildRoot = getOrCreateProjectBaseDir().toNioPath(),
                gradleProjectPath = ":$name",
                sourceSetName = "main",
                sourceRoot = source.toNioPath(),
                sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                provenance = WorkspaceSourceRootProvenance.AUTHORED,
            )
        return source
    }

    private fun addNoise(count: Int) {
        repeat(count) {
            val name = "noise${roots.size}"
            file(sourceModule(name), "Noise.kt", "package $name\nfun target() = 3\nfun local() = target()\n")
        }
        ready()
    }

    private fun file(root: VirtualFile, name: String, text: String): VirtualFile =
        WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
            root.createChildData(this, name).also { it.setBinaryContent(text.toByteArray()) }
        }

    private fun ready() {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun scope(fixture: PartitionFixture, observation: PartitionObservation): GlobalSearchScope {
        val model =
            WorkspaceSearchScopeModel.compile(
                fixture.request.subject.lease.workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                roots,
            )
        val selection =
            SymbolSearchScope.Module(
                (model as WorkspaceSearchScopeModelCompilation.Compiled)
                    .model
                    .sourceRoots
                    .single { it.module.value == "target" }
                    .module,
                SymbolSourceKindPolicy.PRODUCTION_ONLY,
                SymbolGeneratedSourcePolicy.EXCLUDE,
            )
        return (IntellijRelationScopeCompiler()
                .compile(
                    project = project,
                    request = fixture.request,
                    modelCompilation = model,
                    selectedScope = selection,
                    observation = observation,
                ) as IntellijRelationScopeCompilation.Compiled)
            .scope
            .prepareFileEnumeration(IntellijRelationAllowance { 0L }, ReadLimits.Default)
            .nativeScope
    }

    private fun query(fixture: PartitionFixture, partitioned: Boolean): PartitionObservation {
        val observed = PartitionObservation()
        val selected = scope(fixture, observed)
        assertTrue(selected is EnumeratedRelationScope)
        val sites = mutableListOf<Pair<String, Int>>()
        val visit: (com.intellij.psi.PsiReference) -> Boolean = { reference ->
            sites += reference.element.containingFile.virtualFile.path to reference.element.textRange.startOffset
            assertSame(fixture.target, reference.resolve())
            true
        }
        val exhausted =
            if (partitioned)
                observed.forEachReference(
                    subject = fixture.target,
                    scope = selected,
                    admission = nativeScopeTestAdmission(fixture.request, observed),
                    process = visit,
                )
            else ReferencesSearch.search(fixture.target, selected, false).forEach(Processor(visit))
        assertTrue(exhausted)
        assertEquals(listOf(fixture.caller.path to CALLER_OFFSET), sites)
        return observed
    }

    private companion object {
        const val TARGET_OFFSET = 18
        const val CALLER_OFFSET = 38
        const val ADDITIONAL_NOISE_MODULES = 4
        const val PARTITION_CAPACITY_EXTRA_FILES = 6
        const val EXPECTED_PARTITION_CAPACITY = 8
    }
}

private data class PartitionFixture(
    val target: com.intellij.psi.PsiElement,
    val caller: VirtualFile,
    val request: RelationRequest,
)

private class PartitionObservation : IntellijReadObservation {
    val calls = mutableMapOf<IntellijReadCall, Int>()
    val outcomes = mutableListOf<IntellijReadCallOutcome>()
    val fileIdChecks
        get() = calls[IntellijReadCall.RELATION_SCOPE_FILE_ID_MEMBERSHIP] ?: 0

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        calls[call] = (calls[call] ?: 0) + 1
        return object : IntellijReadCallScope {
            override fun finish(outcome: IntellijReadCallOutcome) {
                outcomes += outcome
            }
        }
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) = Unit

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
        error("Unexpected termination: $reason")
}
