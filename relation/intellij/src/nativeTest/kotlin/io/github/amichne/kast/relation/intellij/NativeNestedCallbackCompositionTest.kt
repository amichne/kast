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
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

class NativeNestedCallbackCompositionTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "nested-callback-composition-jdk"

    override fun runInDispatchThread() = false

    fun testNestedDependencyKeepsItsUnresolvedOwner() =
        assertNestedRejection(
            "kotlin.with(Unit) { Unit.also { proof.target() } }",
            setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
        )

    fun testNestedSourceKeepsItsUnresolvedOwner() =
        assertNestedRejection(
            "scope { scope { proof.target() } }",
            setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
        )

    fun testSourceInsideDependencyKeepsTheFailedOwnerProof() =
        assertNestedRejection(
            "kotlin.with(Unit) { scope { proof.target() } }",
            setOf(
                CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                CallbackInvocationFlowCause.OUTSIDE_DOMAIN,
                CallbackInvocationFlowCause.EXTERNAL_CALLABLE,
            ),
        )

    private fun assertNestedRejection(expression: String, expectedCauses: Set<CallbackInvocationFlowCause>) {
        val text = "package consumer\nfun scope(block: () -> Unit) { block() }\nfun caller() { $expression }\n"
        val fixture = fixture(text)
        val compilation = runBlocking { fixture.read(RelationMeaning.Callers) }
        assertTrue(
            "Expected exhausted compiler read, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        val callbacks = (compilation as RelationCompilation.Complete).batch.callbackObservations
        assertEquals(1, callbacks.size)
        val observed = callbacks.single()
        val read = observed.flow
        assertTrue("Expected callback evidence, received $read", read is CallbackInvocationFlowRead.Observed)
        val flow = (read as CallbackInvocationFlowRead.Observed).flow
        assertEquals(expectedCauses, flow.obligations)
        val offset = text.indexOf("target()")
        assertEquals(offset, observed.occurrence.range.startInclusive)
        assertEquals(offset + "target".length, observed.occurrence.range.endExclusive)
        val graph = CompleteStaticCallbackGraph.admit(observed)
        assertEquals(Refinement.Rejected(StaticCallbackGraphFailure.Unresolved(flow)), graph)
        println("Nested callback causes=${flow.obligations} binding=${flow.binding::class.simpleName}")
        println("Nested callback owners=${flow.ownerBindings.map { it.binding::class.simpleName }}")
    }

    private fun fixture(text: String): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            val libraryRoot = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib")
            ModuleRootModificationUtil.updateModel(module) { model ->
                val library = model.moduleLibraryTable.createLibrary("composition-stdlib")
                val roots = library.modifiableModel
                roots.addRoot("jar://${libraryRoot.resolve("kotlin-stdlib.jar")}!/", OrderRootType.CLASSES)
                roots.commit()
            }
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(text.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }
}
