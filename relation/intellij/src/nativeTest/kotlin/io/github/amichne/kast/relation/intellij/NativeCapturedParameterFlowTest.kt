package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Real compiler binding and invocation evidence, not lexical containment, admits a captured parameter. */
class NativeCapturedParameterFlowTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "captured-parameter-flow-jdk"

    override fun runInDispatchThread() = false

    fun testImmutableParameterCrossesBothInvokedAnonymousOwners() =
        checkCapture(
            "fun target(value: String, decoder: Decoder) = runFlow { capture { decoder.decode(value) } }",
            true,
        )

    fun testReturnedUninvokedCallbackDoesNotPropagateCapturedParameter() =
        checkCapture(
            "fun target(value: String, decoder: Decoder): () -> String = { decoder.decode(value) }",
            false,
        )

    fun testIgnoredOuterCallbackDoesNotActivateInnerCapturedReads() =
        checkCapture(
            "fun target(value: String, decoder: Decoder) = ignore { capture { decoder.decode(value) } }",
            false,
        )

    fun testReturnedOuterCallbackDoesNotActivateInnerCapturedReads() =
        checkCapture(
            "fun target(value: String, decoder: Decoder): () -> String = { capture { decoder.decode(value) } }",
            false,
        )

    fun testIgnoredInvocationOwnerDoesNotActivateCapturedReads() =
        checkCapture(
            "fun hidden(block: () -> String): String = ignore { block() }\n" +
                "fun target(value: String, decoder: Decoder) = runFlow { hidden { decoder.decode(value) } }",
            false,
        )

    fun testShadowedValueDoesNotAcquireOuterParameterProvenance() =
        checkCapture(
            caller =
                "fun target(value: String, decoder: Decoder) = runFlow { capture { " +
                    "val value = \"other\"; decoder.decode(value) } }",
            invoked = true,
            excluded = setOf(0),
        )

    fun testSameTypedUnusedDecoderDoesNotAcquireReceiverProvenance() =
        checkCapture(
            caller =
                "fun target(value: String, decoder: Decoder, other: Decoder) = " +
                    "runFlow { capture { decoder.decode(value) } }",
            invoked = true,
            excluded = setOf(2),
        )

    fun testSeparateWrapperSuppliersKeepTheirExactCapturedBindings() =
        checkCapture(
            caller =
                "fun target(value: String, decoder: Decoder, other: String, otherDecoder: Decoder) { " +
                    "runFlow { capture { decoder.decode(value) } }; " +
                    "runFlow { capture { otherDecoder.decode(other) } } }",
            invoked = true,
        )

    private fun checkCapture(caller: String, invoked: Boolean, excluded: Set<Int> = emptySet()) {
        val fixture = captureFixture(caller)
        runBlocking {
            readAction {
                val observation = IntellijReadObservation.None
                val projection = IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot)
                val file = psiManager.findFile(fixture.callerFile) as KtFile
                val owner = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "target" }
                val evidence = (projection.project(owner) as IntellijRelationDeclarationProjection.Projected).evidence
                val budget =
                    io.github.amichne.kast.relation.contract.RelationBudget(
                        fixture.request.budget.resources as io.github.amichne.kast.kernel.ResourceBudget.Bounded,
                        io.github.amichne.kast.relation.contract.RelationByteLimit.parse(2_000_000L).nativeRefined(),
                    )
                val domain =
                    reissuedNativeRelationRequest(
                        request = fixture.request,
                        evidence = evidence,
                        meaning = RelationMeaning.References,
                        budget = budget,
                    )
                val scope = fixture.scope(observation, domain)
                owner.valueParameters.forEachIndexed { index, parameter ->
                    val source = parameterSite(domain.subject, index, parameter)
                    val step =
                        captureStep(
                            source = source,
                            owner = owner,
                            element = parameter,
                            scope = scope,
                            projection = projection,
                        )
                    if (index in excluded) {
                        assertTrue(step.transfers.isEmpty())
                        assertTrue(step.obligations.isEmpty())
                    } else if (invoked) assertCaptureContinuation(step, owner, scope, projection, index)
                    else {
                        assertTrue(step.transfers.isEmpty())
                        assertEquals(
                            listOf(io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause.NESTED_EXECUTION),
                            step.obligations.map { it.cause },
                        )
                        assertEquals(source, step.obligations.single().site)
                    }
                }
            }
        }
    }

    private fun parameterSite(
        endpoint: io.github.amichne.kast.relation.contract.RelationEndpoint,
        index: Int,
        parameter: org.jetbrains.kotlin.psi.KtParameter,
    ): ValueSite {
        val formal =
            nativeValueFormalParameter(endpoint, ValueArgumentPosition.parse(index).nativeRefined(), parameter)
                .nativeRefined()
        return ValueSite.fromCompiler(endpoint, formal.range, ValueRole.Formal(ValueFormalPosition.Parameter(formal)))
            .nativeRefined()
    }

    private fun captureFixture(caller: String): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            ModuleRootModificationUtil.updateModel(module) { model ->
                val library = model.moduleLibraryTable.createLibrary("capture-stdlib")
                val roots = library.modifiableModel
                val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                roots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
                roots.commit()
            }
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(
                    ("package consumer\n" +
                            "interface Decoder { fun decode(value: String): String }\n" +
                            "class FlowScope\n" +
                            "fun <T> runFlow(block: FlowScope.() -> T): T = FlowScope().block()\n" +
                            "fun ignore(block: () -> String): String = \"constant\"\n" +
                            "fun <T> capture(block: () -> T): T = block()\n$caller\n")
                        .toByteArray()
                )
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }

    private fun captureStep(
        source: ValueSite,
        owner: com.intellij.psi.PsiElement,
        element: com.intellij.psi.PsiElement,
        scope: CompiledRelationScope,
        projection: IntellijK2RelationProjection,
    ): ValueFlowStep {
        val result =
            captureRead(source = source, owner = owner, element = element, scope = scope, projection = projection)
        assertTrue("Expected compiler observation: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun captureRead(
        source: ValueSite,
        owner: com.intellij.psi.PsiElement,
        element: com.intellij.psi.PsiElement,
        scope: CompiledRelationScope,
        projection: IntellijK2RelationProjection,
    ): ValueFlowRead =
        IntellijValueFlowNative(
                request = ValueFlowRequest(source, scope.request.budget, RelationSearchBoundary.WORKSPACE_EXPANSION),
                owner = owner,
                scope = scope,
                projection = projection,
                observation = IntellijReadObservation.None,
                started = System.nanoTime(),
                onWork = {},
            )
            .read(element)

    private fun assertCaptureContinuation(
        step: ValueFlowStep,
        owner: KtNamedFunction,
        scope: CompiledRelationScope,
        projection: IntellijK2RelationProjection,
        index: Int,
    ) {
        assertEquals("Captured parameter must reach its exact read", 1, step.transfers.size)
        assertTrue(step.obligations.isEmpty())
        val captured = step.transfers.single().target
        val context = captured.executionOwner.context as ValueExecutionContext.Callback
        assertEquals(2, context.invocations.size)
        assertEquals(step.source.callStack, captured.callStack)
        val element = checkNotNull(owner.exactValueElement(captured.range))
        val formal = (step.source.role as ValueRole.Formal).position as ValueFormalPosition.Parameter
        assertEquals(formal.parameter.name.value, element.text)
        val restored =
            restoreCapturedValueExecution(
                    element = element,
                    host = owner,
                    expected = captured.executionOwner,
                    scope = scope,
                    projection = projection,
                )
                .nativeRefined()
        val arrival =
            captureStep(
                source = captured,
                owner = restored.nativeOwner,
                element = element,
                scope = scope,
                projection = projection,
            )
        val expected = if (index % 2 == 0) ValueTransferKind.ARGUMENT else ValueTransferKind.RECEIVER
        assertEquals(listOf(expected), arrival.transfers.map { it.kind })
        assertTrue(arrival.obligations.isEmpty())
        assertEquals(captured.executionOwner, arrival.transfers.single().target.executionOwner)
        val downstream =
            captureRead(
                source = arrival.transfers.single().target,
                owner = restored.nativeOwner,
                element = element,
                scope = scope,
                projection = projection,
            )
        when (downstream) {
            is ValueFlowRead.Observed -> assertTrue(downstream.step.transfers.isEmpty())
            is ValueFlowRead.Rejected ->
                assertEquals(
                    io.github.amichne.kast.relation.contract.ValueFlowRejection.OUTSIDE_DOMAIN,
                    downstream.cause,
                )
            else -> fail("Unexpected decode continuation: $downstream")
        }
    }
}
