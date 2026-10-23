@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackResultProjection
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractCallsInPlaceContractEffectDeclaration
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression

/** Indexed callback evidence may cross the selected directory; named caller results may not. */
class NativeCallbackDirectoryIsolationTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "callback-directory-isolation-jdk"

    override fun runInDispatchThread() = false

    fun testOutsideMapDoesNotRejectSelectedPlainCaller() {
        val fixture = fixture("listOf(1).map { proof.target() }")
        val counts = Counts()
        val compilation = narrowedRead(fixture, counts)
        assertTrue(
            "Expected selected direct caller, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        val batch = (compilation as RelationCompilation.Complete).batch
        assertEquals(listOf("direct"), batch.facts.map { it.source.name.value })
        assertTrue(batch.callbackObservations.isEmpty())
        assertEquals(1, counts.values[IntellijReadCounter.CALLBACK_RESULTS_EXCLUDED])
    }

    fun testOutsideSupplyRetainsInsideFormalInvocation() {
        val fixture = fixture("selected.invoke { proof.target() }", "fun invoke(block: () -> Int) = block()\n")
        val counts = Counts()
        val compilation = narrowedRead(fixture, counts)
        assertTrue(
            "Expected admitted formal invocation, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        val batch = (compilation as RelationCompilation.Complete).batch
        assertEquals(listOf("direct"), batch.facts.map { it.source.name.value })
        val callback = batch.callbackObservations.single()
        val projection = callback.resultProjection
        assertTrue(projection is CallbackResultProjection.Admitted)
        val flow = (callback.flow as CallbackInvocationFlowRead.Observed).flow
        assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
        assertEquals("invoke", (flow.invocations.single().owner as RelationCallableBody.Named).evidence.name.value)
        assertTrue(CompleteStaticCallbackGraph.admit(callback) is Refinement.Refined)
        assertEquals(1, counts.values[IntellijReadCounter.CALLBACK_RESULTS_ADMITTED])
    }

    fun testStandaloneApplyUsesDeclaredContract() =
        assertDependency("Unit.apply { proof.target() }", "kotlin.apply", true)

    fun testSafeLetUsesDeclaredContract() =
        assertDependency("val value: Int? = 1; value?.let { proof.target() }", "kotlin.let", true)

    fun testStandaloneMapKeepsMissingEffectUnproven() =
        assertDependency("listOf(1).map { proof.target() }", "kotlin.collections.map", false)

    private fun assertDependency(expression: String, callable: String, declared: Boolean) {
        val fixture = fixture(expression)
        assertCompilerEffect(fixture, callable, declared)
        val compilation = runBlocking { fixture.read(RelationMeaning.Callers) }
        assertTrue(
            "Expected exhausted relation read, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        val callback = (compilation as RelationCompilation.Complete).batch.callbackObservations.single()
        val flow = (callback.flow as CallbackInvocationFlowRead.Observed).flow
        if (declared) {
            assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
            val binding = flow.binding as CallbackBindingEvidence.DependencyContract
            assertEquals("EXACTLY_ONCE", binding.binding.invocationKind.name)
            assertEquals("KOTLIN_BINARY_CONTRACT", binding.binding.provenance.name)
            assertTrue(CompleteStaticCallbackGraph.admit(callback) is Refinement.Refined)
        } else {
            assertEquals(setOf(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN), flow.obligations)
            assertTrue(flow.binding is CallbackBindingEvidence.Unavailable)
            assertTrue(CompleteStaticCallbackGraph.admit(callback) is Refinement.Rejected)
        }
    }

    private fun assertCompilerEffect(fixture: NativeReferenceFixture, callable: String, declared: Boolean) =
        runBlocking {
            readAction {
                val file = psiManager.findFile(fixture.callerFile) as KtFile
                val lambda = checkNotNull(PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java))
                val call = checkNotNull(PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java))
                analyze(call) {
                    val resolved = checkNotNull(call.resolveCall())
                    val function = resolved.signature.symbol as KaNamedFunctionSymbol
                    assertEquals(KaSymbolOrigin.LIBRARY, function.origin)
                    assertEquals(callable, function.callableId?.asSingleFqName()?.asString())
                    assertTrue(function.isInline)
                    val parameter = checkNotNull(resolved.valueArgumentMapping[lambda]?.symbol)
                    assertFalse(parameter.isNoinline)
                    assertFalse(parameter.isCrossinline)
                    val effects =
                        function.contractEffects
                            .filterIsInstance<KaContractCallsInPlaceContractEffectDeclaration>()
                            .filter { it.valueParameterReference.symbol == parameter }
                    assertEquals(
                        if (declared) listOf("EXACTLY_ONCE") else emptyList<String>(),
                        effects.map { it.invocationKind.name },
                    )
                }
            }
        }

    private fun narrowedRead(
        fixture: NativeReferenceFixture,
        observation: IntellijReadObservation,
    ): RelationCompilation = runBlocking {
        val request = readAction {
            val projection = IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot)
            val evidence =
                (projection.project(fixture.target) as IntellijRelationDeclarationProjection.Projected).evidence
            val reissued = reissuedNativeRelationRequest(fixture.request, evidence, RelationMeaning.Callers)
            RelationRequest.start(
                subject = reissued.subject,
                meaning = RelationMeaning.Callers,
                budget = reissued.budget,
                boundary =
                    RelationSearchBoundary.Explicit(
                        reissued.searchScope,
                        SymbolDiscoveryDirectoryConstraint(
                            SymbolDiscoveryDirectory.parse("src/selected").nativeRefined(),
                            SymbolDiscoveryContainment.DESCENDANTS,
                        ),
                    ),
            )
        }
        val root = Path.of(request.subject.lease.workspaceRoot.value)
        val model =
            WorkspaceSearchScopeModel.compile(
                request.subject.lease.workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = module.name,
                        linkedBuildRoot = root,
                        gradleProjectPath = ":main",
                        sourceSetName = "main",
                        sourceRoot = root.resolve("src"),
                        sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            )
        IntellijRelationCompilerQuery(observation = observation)
            .read(project = project, currentLease = request.subject.lease, request = request, modelCompilation = model)
    }

    private fun fixture(expression: String, selectedDeclaration: String = ""): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            ModuleRootModificationUtil.updateModel(module) { model ->
                val library = model.moduleLibraryTable.createLibrary("directory-isolation-stdlib")
                val roots = library.modifiableModel
                val stdlib = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                roots.addRoot("jar://$stdlib!/", OrderRootType.CLASSES)
                roots.commit()
            }
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent("package consumer\nfun caller() { $expression }\n".toByteArray())
                val selected = fixture.callerFile.parent.createChildDirectory(this, "selected")
                selected
                    .createChildData(this, "Inside.kt")
                    .setBinaryContent(
                        ("package selected\nfun direct() = proof.target()\n" + selectedDeclaration).toByteArray()
                    )
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }

    private class Counts : IntellijReadObservation by IntellijReadObservation.None {
        val values = mutableMapOf<IntellijReadCounter, Int>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            values[counter] = values.getOrDefault(counter, 0) + amount
        }
    }
}
