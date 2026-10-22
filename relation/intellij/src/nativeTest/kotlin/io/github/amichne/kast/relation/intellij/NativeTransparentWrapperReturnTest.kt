@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame

/** Real compiler call mapping precedes each transparent-wrapper assertion. */
class NativeTransparentWrapperReturnTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "transparent-wrapper-return-jdk"

    override fun runInDispatchThread() = false

    fun testOpenIdentityWithConstantOverrideDoesNotProveTransparentReturn() = withFixture { fixture ->
        val base = fixture.member("Base", "identity")
        val override = fixture.member("Constant", "identity")
        val caller = fixture.caller("callOpen")
        val receiver = PsiTreeUtil.findChildrenOfType(caller, KtProperty::class.java).single()
        val constructor = receiver.initializer as KtCallExpression
        IntellijReadObservation.None.observedAnalyze(caller) {
            assertEquals(KaSymbolModality.OPEN, base.symbol.modality)
            assertTrue(override.symbol.allOverriddenSymbols.any { it == base.symbol })
            assertEquals("0", override.bodyExpression?.text)
            assertEquals("proof.Base", (receiver.symbol.returnType as KaClassType).classId.asSingleFqName().asString())
            val created = constructor.resolveCall()?.signature?.symbol as KaConstructorSymbol
            assertEquals("proof.Constant", (created.returnType as KaClassType).classId.asSingleFqName().asString())
        }
        val argument = fixture.argument("callOpen", base, FIRST_PARAMETER)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL),
            nativeTransparentWrapperReturn(argument, fixture.scope, fixture.projection),
        )
    }

    fun testFinalMemberKeepsTransparentReturn() = withFixture { fixture ->
        val target = fixture.member("Base", "finalIdentity")
        IntellijReadObservation.None.observedAnalyze(target) {
            assertEquals(KaSymbolModality.FINAL, target.symbol.modality)
        }
        val argument = fixture.argument("callFinal", target, FIRST_PARAMETER)
        assertEquals(
            Refinement.Refined(Unit),
            nativeTransparentWrapperReturn(argument, fixture.scope, fixture.projection),
        )
    }

    fun testTopLevelFunctionKeepsTransparentReturn() = withFixture { fixture ->
        val target = fixture.caller("topIdentity")
        IntellijReadObservation.None.observedAnalyze(target) {
            assertEquals(KaSymbolModality.FINAL, target.symbol.modality)
        }
        val argument = fixture.argument("callTop", target, FIRST_PARAMETER)
        assertEquals(
            Refinement.Refined(Unit),
            nativeTransparentWrapperReturn(argument, fixture.scope, fixture.projection),
        )
    }

    fun testDifferentReturnedFormalDoesNotProveTransparentReturn() = withFixture { fixture ->
        val target = fixture.caller("second")
        val argument = fixture.argument("callOther", target, FIRST_PARAMETER)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL),
            nativeTransparentWrapperReturn(argument, fixture.scope, fixture.projection),
        )
    }

    fun testFinalConstantBodyDoesNotProveTransparentReturn() = withFixture { fixture ->
        val target = fixture.member("Base", "constant")
        IntellijReadObservation.None.observedAnalyze(target) {
            assertEquals(KaSymbolModality.FINAL, target.symbol.modality)
        }
        val argument = fixture.argument("callConstant", target, FIRST_PARAMETER)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL),
            nativeTransparentWrapperReturn(argument, fixture.scope, fixture.projection),
        )
    }

    fun testOpenCallAdapterKeepsTheExactUnmodeledObligationWithoutWrapperReturn() {
        val step = adapterRead("callOpen", "identity").step
        assertTrue(step.transfers.none { it.kind == ValueTransferKind.WRAPPER_RETURN })
        assertEquals(listOf(ValueFlowUnsupportedCause.UNMODELED_CALL), step.obligations.map { it.cause })
        assertEquals(ValueFlowTerminal.Unresolved, step.terminal)
    }

    fun testOpenCallKeepsTheQualifiedConstantOverrideWithoutInputReturn() {
        val prepared = prepareAdapterRead("callOpen", "identity")
        val step = adapterRead(prepared).step
        val transfer = step.transfers.single()
        assertEquals(ValueTransferKind.SOURCE_CALL_ENTRY, transfer.kind)
        val entered = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.Enter
        val target = entered.frame.target as ValueCallTarget.Implementation
        assertEquals(
            "proof.Constant.identity",
            (target.relation.source.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value,
        )
        assertEquals(step.source.enclosing.valueIdentity, entered.frame.invocation.enclosing.valueIdentity)
        assertEquals((step.source.role as ValueRole.Argument).call, entered.frame.invocation)
        assertEquals(target.relation.source.valueIdentity, target.ownership.declaration)
        assertEquals(listOf(entered.frame), transfer.target.callStack.frames)
        assertTrue(target.dispatch.batch.facts.any { it === target.relation })
        val qualifiedBudget =
            RelationBudget(
                prepared.request.budget.resources.nativeBounded(),
                RelationByteLimit.parse(16_000_000L).nativeRefined(),
            )
        val constant =
            adapterRead(
                    prepared.copy(request = prepared.request.copy(source = transfer.target, budget = qualifiedBudget))
                )
                .step
        assertTrue(constant.transfers.isEmpty())
        assertTrue(constant.obligations.isEmpty())
        assertEquals(transfer.target.identity, constant.source.identity)
    }

    fun testFinalCallAdapterEmitsOnlyTheMatchedWrapperResult() {
        val step = adapterRead("callFinal", "finalIdentity").step
        val transfer = step.transfers.single()
        assertEquals(ValueTransferKind.WRAPPER_RETURN, transfer.kind)
        assertEquals((step.source.role as ValueRole.Argument).call.resultSite(), transfer.target)
        assertTrue(step.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, step.terminal)
    }

    private fun adapterRead(caller: String, target: String): ValueFlowRead.Observed =
        adapterRead(prepareAdapterRead(caller, target))

    private fun prepareAdapterRead(caller: String, target: String): PreparedWrapperRead = withFixture { fixture ->
        val source = fixture.source(caller, fixture.member("Base", target), FIRST_PARAMETER)
        val budget =
            RelationBudget(
                fixture.scope.request.budget.resources.nativeBounded(),
                RelationByteLimit.parse(WRAPPER_READ_BYTES).nativeRefined(),
            )
        PreparedWrapperRead(
            ValueFlowRequest(source, budget, fixture.scope.request.boundary),
            fixture.model,
        )
    }

    private fun adapterRead(prepared: PreparedWrapperRead): ValueFlowRead.Observed {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = prepared.request.source.enclosing.lease,
                    request = prepared.request,
                    model = prepared.model,
                )
        }
        assertTrue("Expected a native value-flow step, received $result", result is ValueFlowRead.Observed)
        return result as ValueFlowRead.Observed
    }

    private fun <Result> withFixture(assertion: (TransparentWrapperFixture) -> Result): Result {
        lateinit var prepared: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            prepared = prepareReferenceFixture()
            WriteCommandAction.runWriteCommandAction(project) {
                prepared.callerFile.setBinaryContent(WRAPPER_SOURCE.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return ReadAction.compute<Result, RuntimeException> {
            val file = psiManager.findFile(prepared.callerFile) as KtFile
            val scope = prepared.scope(IntellijReadObservation.None)
            val projection = IntellijK2RelationProjection(project, prepared.request.subject.lease.workspaceRoot)
            val model =
                WorkspaceSearchScopeModel.compile(
                    prepared.request.subject.lease.workspaceRoot,
                    ImportedWorkspaceModelState.COMPLETE,
                    listOf(
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = module.name,
                            linkedBuildRoot = prepared.callerFile.parent.parent.toNioPath(),
                            gradleProjectPath = ":main",
                            sourceSetName = "main",
                            sourceRoot = prepared.callerFile.parent.toNioPath(),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    ),
                )
            assertion(TransparentWrapperFixture(file = file, scope = scope, projection = projection, model = model))
        }
    }

    private companion object {
        const val FIRST_PARAMETER = 0
        const val WRAPPER_READ_BYTES = 1_000_000L
    }
}

private class TransparentWrapperFixture(
    val file: KtFile,
    val scope: CompiledRelationScope,
    val projection: IntellijK2RelationProjection,
    val model: WorkspaceSearchScopeModelCompilation,
) {
    fun caller(name: String): KtNamedFunction =
        file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == name }

    fun member(owner: String, name: String): KtNamedFunction =
        file.declarations
            .filterIsInstance<KtClass>()
            .single { it.name == owner }
            .declarations
            .filterIsInstance<KtNamedFunction>()
            .single { it.name == name }

    fun argument(callerName: String, target: KtNamedFunction, position: Int): ValueRole.Argument =
        source(callerName, target, position).role as ValueRole.Argument

    fun source(callerName: String, target: KtNamedFunction, position: Int): ValueSite {
        val caller = caller(callerName)
        val call =
            PsiTreeUtil.findChildrenOfType(caller, KtCallExpression::class.java).single {
                it.calleeExpression?.text == target.name
            }
        val actual =
            call.valueArguments.single {
                (it.getArgumentExpression() as? KtNameReferenceExpression)?.getReferencedName() == "value"
            }
        val expression = checkNotNull(actual.getArgumentExpression())
        IntellijReadObservation.None.observedAnalyze(call) {
            val resolved = checkNotNull(call.resolveCall())
            assertEquals(target.symbol, resolved.signature.symbol)
            assertEquals(KaSymbolOrigin.SOURCE, resolved.signature.symbol.origin)
            val parameter = checkNotNull(resolved.valueArgumentMapping[expression]).symbol
            assertEquals(target.valueParameters[position].symbol, parameter)
            assertSame(target.valueParameters[position], parameter.psi)
        }
        val evidence = (projection.project(caller) as IntellijRelationDeclarationProjection.Projected).evidence
        val enclosing =
            RelationEndpoint.resolve(
                    lease = scope.request.subject.lease,
                    scope = scope.request.searchScope,
                    evidence = evidence,
                    constraints = scope.request.searchConstraints,
                )
                .nativeRefined()
        val result =
            nativeValueArgument(
                    expression = expression,
                    argument = actual,
                    executionOwner = ValueExecutionOwner.declaration(enclosing),
                    scope = scope,
                    projection = projection,
                )
                .nativeRefined()
        assertEquals(position, result.position.value)
        val range = expression.textRange
        return ValueSite.fromCompiler(
                enclosing,
                ExactDeclarationTextRange.parse(range.startOffset, range.endOffset).nativeRefined(),
                result,
            )
            .nativeRefined()
    }
}

private data class PreparedWrapperRead(val request: ValueFlowRequest, val model: WorkspaceSearchScopeModelCompilation)

private val WRAPPER_SOURCE =
    """
    package proof

    open class Base {
        open fun identity(value: Int): Int = value
        fun finalIdentity(value: Int): Int { return value }
        fun constant(value: Int): Int = 0
    }

    class Constant : Base() {
        override fun identity(value: Int): Int = 0
    }

    fun topIdentity(value: Int): Int = value
    fun second(first: Int, second: Int): Int = second

    fun callOpen(value: Int): Int {
        val base: Base = Constant()
        return base.identity(value)
    }

    fun callFinal(value: Int): Int = Base().finalIdentity(value)
    fun callTop(value: Int): Int = topIdentity(value)
    fun callOther(value: Int): Int = second(second = 0, first = value)
    fun callConstant(value: Int): Int = Base().constant(value)
    """
        .trimIndent()
