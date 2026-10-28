@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
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
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Source override modality does not make a stored getter dispatchable outside its final declaring class. */
class NativeConstructorPropertyStorageTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "constructor-property-storage-jdk"

    override fun runInDispatchThread() = false

    fun testPinnedCompilerKeepsOverrideModalityOpenInsideFinalClasses() {
        val fixture = fixture()
        ReadAction.run<RuntimeException> {
            val file = psiManager.findFile(fixture.callerFile) as KtFile
            for (name in listOf("FinalValue", "DataValue")) {
                assertModality(file, name, KaSymbolModality.OPEN)
            }
            assertModality(file, "PlainValue", KaSymbolModality.FINAL)
            assertModality(file, "ExplicitFinalValue", KaSymbolModality.FINAL)
        }
    }

    fun testPlainConstructorValueReachesTheExactObserver() = assertStorage("plainStorage")

    fun testFinalClassConstructorOverrideReachesTheExactObserver() = assertStorage("finalStorage")

    fun testDataClassConstructorOverrideReachesTheExactObserver() = assertStorage("dataStorage")

    fun testExplicitFinalConstructorOverrideReachesTheExactObserver() = assertStorage("explicitFinalStorage")

    fun testOpenConstructorOverrideKeepsItsUnsupportedFrontier() = assertConstructorRejected("openStorage")

    fun testMutableConstructorOverrideKeepsItsUnsupportedFrontier() = assertConstructorRejected("mutableStorage")

    fun testUnprovenPropertyReadsNeverAcquireStoredTransfers() {
        val fixture = fixture()
        for (name in listOf("openRead", "abstractRead", "mutableRead", "computedRead", "inheritedOpenRead")) {
            val prepared = prepare(fixture, name, PropertySource.RECEIVER)
            if (name == "computedRead") assertComputedGetterDiscardsReceiver(prepared)
            else assertUnsupported(name, step(prepared, prepared.request.source))
        }
    }

    private fun assertComputedGetterDiscardsReceiver(prepared: PropertyStorageCase) {
        val receiver = next(prepared, prepared.request.source, ValueTransferKind.RECEIVER)
        val role = receiver.role as ValueRole.Receiver
        assertTrue(role.call.operation is io.github.amichne.kast.relation.contract.ValueInvocationOperation.Getter)
        val signature =
            role.call.callable.signature as io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature.Property
        assertEquals("fixture.ComputedValue.value", signature.qualifiedIdentity.value)
        val formal = next(prepared, receiver, ValueTransferKind.SOURCE_CALL_ENTRY)
        val entry = formal.callStack.frames.last()
        assertEquals(role.call, entry.invocation)
        val discarded = step(prepared, formal)
        assertTrue(discarded.transfers.isEmpty())
        assertTrue(discarded.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, discarded.terminal)
    }

    private fun assertModality(file: KtFile, name: String, expected: KaSymbolModality) {
        val declaration = file.declarations.filterIsInstance<KtClass>().single { it.name == name }
        IntellijReadObservation.None.observedAnalyze(declaration) {
            val parameter = declaration.primaryConstructorParameters.single()
            val property =
                checkNotNull((parameter.symbol as KaValueParameterSymbol).generatedPrimaryConstructorProperty)
            val getter = checkNotNull(property.getter)
            assertEquals("Declaring class $name", KaSymbolModality.FINAL, declaration.symbol.modality)
            assertEquals("Property $name.value", expected, property.modality)
            assertEquals("Getter $name.value", expected, getter.modality)
            assertEquals(declaration.symbol, property.containingDeclaration)
            assertEquals(parameter, property.psi)
            assertTrue(property.isVal)
            assertFalse(property.isDelegated)
            assertTrue(property.hasBackingField)
            assertFalse(getter.isNotDefault)
        }
    }

    private fun assertStorage(function: String) {
        val prepared = prepare(fixture(), function, PropertySource.CONSTRUCTOR_ARGUMENT)
        val argument = next(prepared, prepared.request.source, ValueTransferKind.ARGUMENT)
        val constructed = step(prepared, argument)
        assertEquals(listOf(ValueTransferKind.CONSTRUCTOR_FIELD), constructed.transfers.map { it.kind })
        assertTrue("Constructor retained a frontier: ${constructed.obligations}", constructed.obligations.isEmpty())
        val stored = constructed.transfers.single()
        val storage = (stored.evidence as ValueSourceTransfer).operation as ValueSourceOperation.ConstructField
        assertEquals(0, storage.storage.parameter.position.value)
        assertEquals(storage.storage.parameter.range, storage.storage.field.declaration.range)
        assertEquals(storage.call.resultSite().range, stored.target.range)

        val projected = step(prepared, stored.target)
        assertEquals(listOf(ValueTransferKind.FIELD_READ), projected.transfers.map { it.kind })
        assertTrue("Field read retained a frontier: ${projected.obligations}", projected.obligations.isEmpty())
        val read = projected.transfers.single()
        val field = (read.evidence as ValueSourceTransfer).operation as ValueSourceOperation.ReadField
        assertEquals(storage.storage.field, field.field)
        assertTrue(read.target.valuePath.segments.isEmpty())
        assertEquals(argument.callStack, read.target.callStack)

        val observed = next(prepared, read.target, ValueTransferKind.ARGUMENT)
        val observer = observed.role as ValueRole.Argument
        assertEquals(prepared.observer.valueIdentity, observer.call.callable.valueIdentity)
        assertEquals(prepared.observerRange, observer.call.range)
        assertEquals(0, observer.position.value)
        val terminal = step(prepared, next(prepared, observed, ValueTransferKind.SOURCE_CALL_ENTRY))
        assertTrue(terminal.transfers.isEmpty())
        assertTrue(terminal.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, terminal.terminal)
    }

    private fun assertConstructorRejected(function: String) {
        val prepared = prepare(fixture(), function, PropertySource.CONSTRUCTOR_ARGUMENT)
        val argument = next(prepared, prepared.request.source, ValueTransferKind.ARGUMENT)
        assertUnsupported(function, step(prepared, argument))
    }

    private fun assertUnsupported(function: String, step: ValueFlowStep) {
        assertTrue("Unproven storage acquired a transfer: $function", step.transfers.isEmpty())
        assertEquals(
            "Wrong property rejection for $function",
            listOf(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY),
            step.obligations.map { it.cause },
        )
        assertEquals(step.source, step.obligations.single().site)
    }

    private fun next(prepared: PropertyStorageCase, source: ValueSite, kind: ValueTransferKind): ValueSite {
        val step = step(prepared, source)
        assertTrue("Unexpected storage frontier: ${step.obligations}", step.obligations.isEmpty())
        assertEquals(listOf(kind), step.transfers.map { it.kind })
        return step.transfers.single().target
    }

    private fun step(prepared: PropertyStorageCase, source: ValueSite): ValueFlowStep = runBlocking {
        val result =
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = source.enclosing.lease,
                    request = ValueFlowRequest(source, prepared.request.budget, prepared.request.boundary),
                    model = prepared.model,
                )
        assertTrue("Property read was not observed: $result", result is ValueFlowRead.Observed)
        (result as ValueFlowRead.Observed).step
    }

    private fun fixture(): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.callerFile.setBinaryContent(CONSTRUCTOR_PROPERTY_SOURCE.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return fixture
    }

    private fun prepare(
        fixture: NativeReferenceFixture,
        functionName: String,
        source: PropertySource,
    ): PropertyStorageCase =
        ReadAction.compute<PropertyStorageCase, RuntimeException> {
            val file = psiManager.findFile(fixture.callerFile) as KtFile
            val functions = file.declarations.filterIsInstance<KtNamedFunction>()
            val function = functions.single { it.name == functionName }
            val observer =
                PsiTreeUtil.findChildrenOfType(function, KtCallExpression::class.java).single {
                    it.calleeExpression?.text == "observe"
                }
            val expression = observer.valueArguments.single().getArgumentExpression() as KtDotQualifiedExpression
            val origin =
                when (source) {
                    PropertySource.CONSTRUCTOR_ARGUMENT ->
                        checkNotNull(
                            (expression.receiverExpression as KtCallExpression)
                                .valueArguments
                                .single()
                                .getArgumentExpression()
                        )
                    PropertySource.RECEIVER -> expression.receiverExpression
                }
            val enclosing = endpoint(fixture, function)
            val site = ValueSite.fromCompiler(enclosing, range(origin), ValueRole.ExpressionResult).nativeRefined()
            val budget =
                RelationBudget(
                    fixture.request.budget.resources.nativeBounded(),
                    RelationByteLimit.parse(1_000_000L).nativeRefined(),
                )
            PropertyStorageCase(
                request = ValueFlowRequest(site, budget, fixture.request.boundary),
                model = sourceModel(fixture),
                observer = endpoint(fixture, functions.single { it.name == "observe" }),
                observerRange = range(observer),
            )
        }

    private fun endpoint(fixture: NativeReferenceFixture, function: KtNamedFunction): RelationEndpoint {
        val projection = IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot)
        val evidence = (projection.project(function) as IntellijRelationDeclarationProjection.Projected).evidence
        return RelationEndpoint.resolve(fixture.request.subject.lease, fixture.request.searchScope, evidence)
            .nativeRefined()
    }

    private fun sourceModel(fixture: NativeReferenceFixture): WorkspaceSearchScopeModelCompilation =
        WorkspaceSearchScopeModel.compile(
            fixture.request.subject.lease.workspaceRoot,
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                WorkspaceSourceRootBoundary(
                    ideaModuleName = module.name,
                    linkedBuildRoot = fixture.callerFile.parent.parent.toNioPath(),
                    gradleProjectPath = ":main",
                    sourceSetName = "main",
                    sourceRoot = fixture.callerFile.parent.toNioPath(),
                    sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                )
            ),
        )
}

private data class PropertyStorageCase(
    val request: ValueFlowRequest,
    val model: WorkspaceSearchScopeModelCompilation,
    val observer: RelationEndpoint,
    val observerRange: ExactDeclarationTextRange,
)

private enum class PropertySource {
    CONSTRUCTOR_ARGUMENT,
    RECEIVER,
}

private val CONSTRUCTOR_PROPERTY_SOURCE =
    """
    package fixture

    interface StoredValue<out T> { val value: T }
    class PlainValue<T>(val value: T)
    class FinalValue<T>(override val value: T) : StoredValue<T>
    data class DataValue<T> internal constructor(override val value: T) : StoredValue<T>
    data class ExplicitFinalValue<T>(final override val value: T) : StoredValue<T>
    open class OpenValue<T>(override val value: T) : StoredValue<T>
    class FinalChild<T>(value: T) : OpenValue<T>(value)
    class MutableValue<T>(override var value: T) : StoredValue<T>
    class ComputedValue(val stored: Int) : StoredValue<Int> { override val value: Int get() = 0 }

    fun observe(value: Int) {}
    fun plainStorage() = observe(PlainValue(7).value)
    fun finalStorage() = observe(FinalValue(7).value)
    fun dataStorage() = observe(DataValue(7).value)
    fun explicitFinalStorage() = observe(ExplicitFinalValue(7).value)
    fun openStorage() = observe(OpenValue(7).value)
    fun mutableStorage() = observe(MutableValue(7).value)
    fun openRead(box: OpenValue<Int>) = observe(box.value)
    fun abstractRead(box: StoredValue<Int>) = observe(box.value)
    fun mutableRead(box: MutableValue<Int>) = observe(box.value)
    fun computedRead(box: ComputedValue) = observe(box.value)
    fun inheritedOpenRead(box: FinalChild<Int>) = observe(box.value)
    """
        .trimIndent()
