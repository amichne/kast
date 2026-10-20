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
import io.github.amichne.kast.relation.contract.RelationFactCoverage
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalOrigin
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
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
import org.junit.Assert.assertTrue

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

    fun testOpenCallAdapterKeepsImplementationEntryWithoutWrapperReturn() {
        val observed = adapterRead("callOpen", "identity") { fixture, request -> fixture.implementation(request) }
        val step = observed.read.step
        assertTrue(step.transfers.none { it.kind == ValueTransferKind.WRAPPER_RETURN })
        val call = (step.source.role as ValueRole.Argument).call
        assertTrue(
            step.transfers.none { it.target.role == ValueRole.ExpressionResult && it.target.range == call.range }
        )
        assertImplementationEntry(step, observed.expected)
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
        adapterRead(caller, target) { _, _ -> Unit }.read

    private fun <Expected> adapterRead(
        caller: String,
        target: String,
        prepareExpected: (TransparentWrapperFixture, ValueFlowRequest) -> Expected,
    ): ObservedWrapperRead<Expected> {
        val prepared = withFixture { fixture ->
            val source = fixture.source(caller, fixture.member("Base", target), FIRST_PARAMETER)
            val budget =
                RelationBudget(
                    fixture.scope.request.budget.resources,
                    RelationByteLimit.parse(WRAPPER_READ_BYTES).nativeRefined(),
                )
            val request = ValueFlowRequest(source, budget, fixture.scope.request.boundary)
            PreparedWrapperRead(request, fixture.model, prepareExpected(fixture, request))
        }
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
        return ObservedWrapperRead(result as ValueFlowRead.Observed, prepared.expected)
    }

    private fun assertImplementationEntry(step: ValueFlowStep, expected: WrapperImplementation) {
        val transfer = step.transfers.single()
        assertEquals(ValueTransferKind.SOURCE_CALL_ENTRY, transfer.kind)
        assertEquals(expected.source, step.source)
        assertEquals(expected.source, transfer.source)
        assertTrue(transfer.source.enclosing.hasSameProofAs(expected.source.enclosing))
        assertTrue(transfer.source.executionOwner.hasSameProofAs(expected.source.executionOwner))
        val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.Enter
        val frame = operation.frame
        assertImplementationFrame(frame, expected)
        val target = transfer.target
        assertTrue(target.enclosing.hasSameProofAs(expected.implementation))
        assertTrue(target.executionOwner.hasSameProofAs(frame.callee))
        assertEquals(expected.parameterRange, target.range)
        assertEquals(ValueRole.Formal(operation.position), target.role)
        val parameter = (operation.position as ValueFormalPosition.Parameter).parameter
        assertTrue(parameter.callable.hasSameProofAs(expected.implementation))
        assertTrue(parameter.executionOwner.hasSameProofAs(frame.callee))
        assertEquals(FIRST_PARAMETER, parameter.position.value)
        assertEquals(expected.parameterName, parameter.name.value)
        assertEquals(ValueFormalOrigin.Declared(expected.parameterRange), parameter.origin)
        assertEquals(expected.source.callStack.frames + frame, target.callStack.frames)
        assertEquals(expected.source.valuePath, target.valuePath)
        assertEquals(expected.source.predicates, target.predicates)
        assertTrue(step.obligations.isEmpty())
        assertTrue(step.applications.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, step.terminal)
    }

    private fun assertImplementationFrame(frame: ValueCallFrame, expected: WrapperImplementation) {
        val original = (expected.source.role as ValueRole.Argument).call
        val invocation = frame.invocation as ValueInvocation
        assertTrue(invocation.callable.hasSameProofAs(expected.question.subject))
        assertEquals(original.range, invocation.range)
        assertTrue(invocation.enclosing.hasSameProofAs(original.enclosing))
        assertTrue(invocation.executionOwner.hasSameProofAs(original.executionOwner))
        assertEquals(original.signature, invocation.signature)
        assertEquals(expected.source.basis, invocation.basis)
        assertTrue(frame.callee.enclosing.hasSameProofAs(expected.implementation))
        assertEquals(ValueExecutionBody.Declaration, frame.callee.body)
        val proof = frame.target as ValueCallTarget.Implementation
        assertEquals(expected.implementation.valueIdentity, proof.ownership.declaration)
        assertEquals(expected.source.basis, proof.ownership.basis)
        assertEquals(expected.root, proof.ownership.root)
        assertImplementationDispatch(proof, expected)
    }

    private fun assertImplementationDispatch(proof: ValueCallTarget.Implementation, expected: WrapperImplementation) {
        val fact = proof.relation
        assertTrue(fact.source.hasSameProofAs(expected.implementation))
        assertTrue(fact.target.hasSameProofAs(expected.question.subject))
        assertTrue(fact.subject.hasSameProofAs(expected.question.subject))
        assertEquals(RelationMeaning.Implementations, fact.meaning)
        assertEquals(expected.implementation.file, fact.occurrence.file)
        assertEquals(expected.implementation.range, fact.occurrence.range)
        assertEquals(expected.source.basis, fact.authority)
        assertEquals(RelationFactCoverage.EXACT_COMPILER_CONFIRMED, fact.coverage)
        assertEquals(RelationProvenance.K2_AUTHORED_SOURCE, fact.provenance)
        val batch = proof.dispatch.batch
        val question = batch.request
        assertTrue(question.subject.hasSameProofAs(expected.question.subject))
        assertEquals(RelationMeaning.Implementations, question.meaning)
        assertEquals(expected.question.budget, question.budget)
        assertEquals(expected.question.boundary, question.boundary)
        assertEquals(RelationReadPosition.Start, question.position)
        assertEquals(expected.question.searchScope, question.searchScope)
        assertEquals(expected.question.searchConstraints, question.searchConstraints)
        assertEquals(listOf(fact), batch.facts)
        assertTrue(batch.omissions.isEmpty())
        assertTrue(batch.scopeExclusions.isEmpty())
        assertTrue(batch.referenceOccurrences.isEmpty())
        assertTrue(batch.callbackObservations.isEmpty())
        assertTrue(batch.callableObservations.isEmpty())
        assertEquals(1, batch.resultCount.value)
        assertEquals(1, (proof.dispatch.coverage as ValueImplementationCoverage.Exhausted).evidence.exactCount.value)
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

    fun implementation(request: ValueFlowRequest): WrapperImplementation {
        val base = member("Base", "identity")
        val selected = member("Constant", "identity")
        IntellijReadObservation.None.observedAnalyze(selected) {
            assertEquals(KaSymbolOrigin.SOURCE, selected.symbol.origin)
            assertEquals(listOf(base.symbol), selected.symbol.allOverriddenSymbols.toList())
            assertSame(selected.valueParameters.single(), selected.symbol.valueParameters.single().psi)
        }
        val static = endpoint(base, scope.request)
        assertTrue((request.source.role as ValueRole.Argument).call.callable.hasSameProofAs(static))
        val question =
            RelationRequest.start(
                subject = static,
                meaning = RelationMeaning.Implementations,
                budget = request.budget,
                boundary = request.boundary,
            )
        val parameter = selected.valueParameters.single()
        return WrapperImplementation(
            source = request.source,
            question = question,
            implementation = endpoint(selected, question),
            parameterRange =
                ExactDeclarationTextRange.parse(parameter.textRange.startOffset, parameter.textRange.endOffset)
                    .nativeRefined(),
            parameterName = checkNotNull(parameter.name),
            root = (model as WorkspaceSearchScopeModelCompilation.Compiled).model.sourceRoots.single(),
        )
    }

    private fun endpoint(function: KtNamedFunction, question: RelationRequest): RelationEndpoint =
        RelationEndpoint.resolve(
                lease = question.subject.lease,
                scope = question.searchScope,
                evidence = (projection.project(function) as IntellijRelationDeclarationProjection.Projected).evidence,
                constraints = question.searchConstraints,
            )
            .nativeRefined()

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
        val enclosing = endpoint(caller, scope.request)
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

private data class PreparedWrapperRead<Expected>(
    val request: ValueFlowRequest,
    val model: WorkspaceSearchScopeModelCompilation,
    val expected: Expected,
)

private data class ObservedWrapperRead<Expected>(val read: ValueFlowRead.Observed, val expected: Expected)

private data class WrapperImplementation(
    val source: ValueSite,
    val question: RelationRequest,
    val implementation: RelationEndpoint,
    val parameterRange: ExactDeclarationTextRange,
    val parameterName: String,
    val root: ModelOwnedSourceRoot,
)

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
