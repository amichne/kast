package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.StaticCallbackEdge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

class NativeCallbackRetentionTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "callback-retention-jdk"

    override fun runInDispatchThread() = false

    fun testForwardedCapturedFormalProofCanExceedTheOutputByteGrant() {
        val fixture = fixture()
        val observation = CallbackRetentionObservation()
        val compilation = runBlocking { fixture.read(RelationMeaning.Callers, observation) }
        assertTrue(
            "Expected an exhausted compiler read, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        val callback = (compilation as RelationCompilation.Complete).batch.callbackObservations.single()
        assertTrue(callback.flow is CallbackInvocationFlowRead.Observed)
        val flow = (callback.flow as CallbackInvocationFlowRead.Observed).flow
        assertEquals(
            "The forwarded invocation must preserve its captured owner. ${observation.gauges}",
            emptySet<CallbackInvocationFlowCause>(),
            flow.obligations,
        )
        val graph = CompleteStaticCallbackGraph.admit(callback)
        assertTrue("Expected an exhaustive captured-formal graph, received $graph", graph is Refinement.Refined)
        val target =
            (graph as Refinement.Refined).value.edges.filterIsInstance<StaticCallbackEdge.BodyTarget>().single()
        assertEquals(CAPTURED_FORMAL_SOURCE.indexOf("target()"), target.occurrence.range.startInclusive)
        assertEquals(callback.occurrence, target.occurrence)
        assertTrue(
            observation.gauges.getValue(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES) >
                fixture.request.budget.returnedBytes.value
        )
    }

    private fun fixture(): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            ModuleRootModificationUtil.updateModel(module) { model ->
                val library = model.moduleLibraryTable.createLibrary("retention-stdlib")
                val roots = library.modifiableModel
                val stdlib = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                roots.addRoot("jar://$stdlib!/", OrderRootType.CLASSES)
                roots.commit()
            }
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(CAPTURED_FORMAL_SOURCE.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }
}

private class CallbackRetentionObservation : IntellijReadObservation by IntellijReadObservation.None {
    val gauges = mutableMapOf<IntellijReadGauge, Long>()

    override fun measure(gauge: IntellijReadGauge, value: IntellijReadGaugeValue) {
        gauges[gauge] = value.value
    }
}

private val CAPTURED_FORMAL_SOURCE =
    """
    package io.github.amichne.kast.relation.intellij
    class RelationRequest
    class IntellijRelationCollector
    class RelationProviderState
    class RelationProviderLocator
    class IntellijReadObservation
    class IntellijReferenceInventoryReuse
    fun caller() { inventory { proof.target(); true } }
    fun inventory(process: (String) -> Boolean) {
        readRelationInventory(
            RelationRequest(), IntellijRelationCollector(), { RelationProviderState() },
            { state, locator -> restored("reference", process) }, {}, IntellijReadObservation(),
            { 0L }, IntellijReferenceInventoryReuse(),
        )
    }
    fun readRelationInventory(
        request: RelationRequest,
        collector: IntellijRelationCollector,
        prepare: () -> RelationProviderState,
        confirm: (RelationProviderState, RelationProviderLocator) -> Boolean,
        cancellationCheck: () -> Unit,
        observation: IntellijReadObservation,
        clockNanoseconds: () -> Long,
        inventories: IntellijReferenceInventoryReuse,
    ) { consumeRelationLocator(prepare(), RelationProviderLocator(), collector, confirm) }
    fun consumeRelationLocator(
        state: RelationProviderState,
        locator: RelationProviderLocator,
        collector: IntellijRelationCollector,
        confirm: (RelationProviderState, RelationProviderLocator) -> Boolean,
    ): Boolean = confirm(state, locator)
    fun restored(value: String, process: (String) -> Boolean): Boolean = process(value)
    """
        .trimIndent()
