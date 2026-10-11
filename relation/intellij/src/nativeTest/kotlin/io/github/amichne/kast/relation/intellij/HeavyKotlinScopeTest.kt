package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
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

/** A heavy Platform project is required for a real dependency graph. Only irrelevant modules widen. */
class HeavyKotlinScopeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testNativeScopeWorkVisibilityFreshnessAndCancellation() {
        val base = getOrCreateProjectBaseDir()
        val sdk = JavaSdk.getInstance().createJdk("case-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        val roots = mutableListOf<WorkspaceSourceRootBoundary>()
        fun sourceModule(name: String, dependency: Module? = null): Pair<Module, VirtualFile> {
            val module = createModule(name)
            val source =
                WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                    base.createChildDirectory(this, name).createChildDirectory(this, "src")
                }
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.sdk = sdk
                model.addContentEntry(source).addSourceFolder(source, false)
                if (dependency != null) model.addModuleOrderEntry(dependency)
            }
            roots +=
                WorkspaceSourceRootBoundary(
                    ideaModuleName = name,
                    linkedBuildRoot = base.toNioPath(),
                    gradleProjectPath = ":$name",
                    sourceSetName = "main",
                    sourceRoot = source.toNioPath(),
                    sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                )
            return module to source
        }
        fun file(root: VirtualFile, name: String, text: String): VirtualFile =
            WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                root.createChildData(this, name).also { it.setBinaryContent(text.toByteArray()) }
            }
        val (targetModule, targetRoot) = sourceModule("target")
        val targetFile = file(targetRoot, "Target.kt", "package proof\nfun target() = 1\nfun unused() = 2\n")
        val (consumer, consumerRoot) = sourceModule("consumer", targetModule)
        val callerFile = file(consumerRoot, "Caller.kt", "package consumer\nfun caller() = proof.target()\n")
        // Explicit library input in addition to the case-owned JDK.
        ModuleRootModificationUtil.updateModel(consumer) { model ->
            val library = model.moduleLibraryTable.createLibrary("fixture-stdlib")
            val libraryModel = library.modifiableModel
            libraryModel.addRoot(
                "jar://" + kotlin.Unit::class.java.protectionDomain.codeSource.location.path + "!/",
                OrderRootType.CLASSES,
            )
            libraryModel.commit()
        }
        val noise = mutableListOf<VirtualFile>()
        fun widen(count: Int) {
            repeat(count) {
                val name = "noise${noise.size}"
                val (_, source) = sourceModule(name, targetModule)
                noise += file(source, "Noise.kt", "package $name\nfun target() = 3\nfun local() = target()\n")
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        widen(1)
        val targetPsi = psiManager.findFile(targetFile) as KtFile
        val target = targetPsi.declarations[0] as KtNamedFunction
        val unused = targetPsi.declarations[1] as KtNamedFunction
        val request = nativeRelationRequest(base.toNioPath(), targetFile.toNioPath(), 18)
        fun scope(observation: NativeWorkObservation, narrow: Boolean): CompiledRelationScope {
            val model =
                WorkspaceSearchScopeModel.compile(
                    request.subject.lease.workspaceRoot,
                    ImportedWorkspaceModelState.COMPLETE,
                    roots,
                )
            val selection =
                if (narrow)
                    SymbolSearchScope.Module(
                        (model as WorkspaceSearchScopeModelCompilation.Compiled)
                            .model
                            .sourceRoots
                            .single { it.module.value == consumer.name }
                            .module,
                        SymbolSourceKindPolicy.PRODUCTION_ONLY,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                    )
                else request.searchScope
            return (IntellijRelationScopeCompiler()
                    .compile(
                        project = project,
                        request = request,
                        modelCompilation = model,
                        selectedScope = selection,
                        observation = observation,
                    ) as IntellijRelationScopeCompilation.Compiled)
                .scope
        }
        fun query(narrow: Boolean): NativeWorkObservation {
            val observed = NativeWorkObservation()
            val compiled = scope(observed, narrow)
            val ownershipBefore = observed.counters[IntellijReadCounter.RELATION_PATH_OWNERSHIP_PROBES] ?: 0
            assertTrue(compiled.nativeScope.contains(callerFile))
            assertEquals(
                2,
                (observed.counters[IntellijReadCounter.RELATION_PATH_OWNERSHIP_PROBES] ?: 0) - ownershipBefore,
            )
            val rows = mutableListOf<Pair<String, Int>>()
            assertTrue(
                observed.forEachReference(target, compiled.nativeScope, nativeScopeTestAdmission(request, observed)) {
                    reference ->
                    rows += reference.element.containingFile.virtualFile.path to reference.element.textRange.startOffset
                    assertSame(target, reference.resolve())
                    true
                }
            )
            assertEquals(listOf(callerFile.path to CALLER_REFERENCE_OFFSET), rows)
            assertEquals(1, observed.calls[IntellijReadCall.REFERENCE_SEARCH])
            assertEquals(1, observed.calls[IntellijReadCall.REFERENCE_CALLBACK])
            return observed
        }
        val narrowTiny = query(true)
        assertTrue(noise.all { !(psiManager.findFile(it) as PsiFileImpl).isContentsLoaded })
        val broadTiny = query(false)
        widen(ADDITIONAL_NOISE_MODULES)
        val narrowWide = query(true)
        assertTrue(noise.drop(1).all { !(psiManager.findFile(it) as PsiFileImpl).isContentsLoaded })
        val broadWide = query(false)
        assertTrue(narrowWide.sourceChecks < broadWide.sourceChecks)

        val negative = NativeWorkObservation()
        assertTrue(
            negative.forEachReference(
                unused,
                scope(negative, true).nativeScope,
                nativeScopeTestAdmission(request, negative),
            ) {
                fail("unused has no references")
                false
            }
        )
        assertNativeCancellation(request, target) { scope(it, true).nativeScope }
        assertCommittedEditInvalidates(request, callerFile, target) { scope(it, true).nativeScope }
        println("native-scope tiny narrow=$narrowTiny broad=$broadTiny; wide narrow=$narrowWide broad=$broadWide")
    }

    private fun assertNativeCancellation(
        request: RelationRequest,
        target: KtNamedFunction,
        nativeScope: (NativeWorkObservation) -> GlobalSearchScope,
    ) {
        val indicator = EmptyProgressIndicator()
        val cancellation = NativeWorkObservation()
        try {
            ProgressManager.getInstance()
                .runProcess(
                    {
                        cancellation.forEachReference(
                            target,
                            nativeScope(cancellation),
                            nativeScopeTestAdmission(request, cancellation),
                        ) {
                            indicator.cancel()
                            ProgressManager.checkCanceled()
                            true
                        }
                    },
                    indicator,
                )
            fail("Expected native query cancellation")
        } catch (_: ProcessCanceledException) {
            assertEquals(IntellijReadCallOutcome.CANCELLED, cancellation.outcomes.last())
        }
    }

    private fun assertCommittedEditInvalidates(
        request: RelationRequest,
        callerFile: VirtualFile,
        target: KtNamedFunction,
        nativeScope: (NativeWorkObservation) -> GlobalSearchScope,
    ) {
        val caller = checkNotNull(psiManager.findFile(callerFile))
        val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(caller))
        WriteCommandAction.runWriteCommandAction(project) { document.setText("package consumer\nfun caller() = 4\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val fresh = NativeWorkObservation()
        assertTrue(
            fresh.forEachReference(target, nativeScope(fresh), nativeScopeTestAdmission(request, fresh)) {
                fail("committed edit removed the reference")
                false
            }
        )
    }

    private companion object {
        const val CALLER_REFERENCE_OFFSET = 38
        const val ADDITIONAL_NOISE_MODULES = 4
    }
}

private class NativeWorkObservation : IntellijReadObservation {
    val calls = mutableMapOf<IntellijReadCall, Int>()
    val counters = mutableMapOf<IntellijReadCounter, Int>()
    val outcomes = mutableListOf<IntellijReadCallOutcome>()
    val sourceChecks
        get() = calls[IntellijReadCall.RELATION_SCOPE_SOURCE_MEMBERSHIP] ?: 0

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        calls[call] = (calls[call] ?: 0) + 1
        return object : IntellijReadCallScope {
            override fun finish(outcome: IntellijReadCallOutcome) {
                outcomes += outcome
            }
        }
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counters[counter] = (counters[counter] ?: 0) + amount
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit

    override fun toString(): String =
        "scopePredicates=${calls[IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP]}, sourceChecks=$sourceChecks, deliveredCallbacks=${calls[IntellijReadCall.REFERENCE_CALLBACK]}, searches=${calls[IntellijReadCall.REFERENCE_SEARCH]}; bytes/hashes/retention=not-exercised; internalIndexVisits=opaque"
}
