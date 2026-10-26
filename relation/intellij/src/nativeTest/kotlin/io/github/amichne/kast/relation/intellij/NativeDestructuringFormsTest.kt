package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Component outputs keep their exact parameter or iteration occurrence and source execution proof. */
class NativeDestructuringFormsTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "destructuring-forms-jdk"

    fun testAuthoredLoopComponentReturnsToEachExactIterationBinding() {
        val fixture = controls()
        val returned = componentReturns(fixture, "LoopComponent", "component1")
        assertTrue("Unexpected obligations: ${returned.obligations}", returned.obligations.isEmpty())
        assertEquals(List(2) { ValueTransferKind.SOURCE_CALL_RETURN }, returned.transfers.map { it.kind })
        val expected = listOf("first", "second").map { marked(fixture, "loopForms", "$it-binding").anchor }.toSet()
        assertEquals(expected, returned.transfers.map { it.target.range }.toSet())
        for (transfer in returned.transfers) {
            val source = transfer.target
            assertEquals(ValueExecutionContext.Declaration, source.executionOwner.context)
            val name = if (source.range == marked(fixture, "loopForms", "first-binding").anchor) "first" else "second"
            assertExactUse(fixture = fixture, source = source, function = "loopForms", marker = "$name-use")
        }
        assertFalse(returned.transfers[0].target.identity == returned.transfers[1].target.identity)
    }

    fun testAuthoredLambdaComponentKeepsTheProvenCallbackOwner() {
        val fixture = controls()
        val returned = componentReturns(fixture, "LambdaComponent", "component1")
        assertTrue("Unexpected obligations: ${returned.obligations}", returned.obligations.isEmpty())
        assertEquals(listOf(ValueTransferKind.SOURCE_CALL_RETURN), returned.transfers.map { it.kind })
        val source = returned.transfers.single().target
        assertEquals(marked(fixture, "lambdaForm", "binding").anchor, source.range)
        assertTrue(source.executionOwner.context is ValueExecutionContext.Callback)
        assertExactUse(fixture = fixture, source = source, function = "lambdaForm", marker = "use")
        assertErasedCallbackOwnerRejects(fixture, source)
    }

    fun testDormantLambdaCannotAcquireAComponentReturn() {
        val fixture = controls()
        val returned = componentReturns(fixture, "DormantComponent", "component1")
        assertTrue("Dormant callback acquired a return: ${returned.transfers}", returned.transfers.isEmpty())
        val expression = marked(fixture, "dormantLambda", "binding")
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = expression.enclosing,
                    anchor = expression.anchor,
                    role = ValueSiteRoleClaim.ExpressionResult,
                    budget = fixture.budget,
                )
                .nativeRefined()
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        }
        assertEquals(ValueModelSiteRead.Unsupported(ValueFlowUnsupportedCause.NESTED_EXECUTION), read)
    }

    fun testSkippedThrowingComponentsPreserveLoopAndLambdaOutputs() {
        val fixture = controls()
        val returned = componentReturns(fixture, "SkippedFormComponent", "component2")
        assertTrue("Skipped component produced an obligation: ${returned.obligations}", returned.obligations.isEmpty())
        assertEquals(List(2) { ValueTransferKind.SOURCE_CALL_RETURN }, returned.transfers.map { it.kind })
        val owners = listOf("skippedLoop", "skippedLambda")
        assertEquals(
            owners.map { marked(fixture, it, "binding").anchor }.toSet(),
            returned.transfers.map { it.target.range }.toSet(),
        )
        for (name in owners) {
            val source = returned.transfers.single { it.target.range == marked(fixture, name, "binding").anchor }.target
            assertExactUse(fixture = fixture, source = source, function = name, marker = "use")
        }
    }

    fun testOrderedAuthoredComponentsCannotBypassTheirFirstEffect() {
        val fixture = controls()
        val returned = componentReturns(fixture, "OrderedFormComponent", "component2")
        assertTrue("Ordered components invented a successful result", returned.transfers.isEmpty())
        assertEquals(setOf(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN), returned.obligations.map { it.cause }.toSet())
    }

    fun testGeneratedLoopBindingsKeepDistinctSameTypedComponents() {
        assertGeneratedBindings("generatedLoop", callback = false)
    }

    fun testGeneratedLambdaBindingsKeepDistinctSameTypedComponents() {
        assertGeneratedBindings("generatedLambda", callback = true)
    }

    private fun assertGeneratedBindings(function: String, callback: Boolean) {
        val fixture = controls()
        assertGeneratedFieldSelection(fixture, function)
        val sites =
            listOf("first", "second").map { name ->
                val expression = marked(fixture, function, "$name-binding")
                val request =
                    ValueSiteRevalidationRequest.create(
                            enclosing = expression.enclosing,
                            anchor = expression.anchor,
                            role = ValueSiteRoleClaim.ExpressionResult,
                            budget =
                                fixture.budget.copy(returnedBytes = RelationByteLimit.parse(MAX_BYTES).nativeRefined()),
                        )
                        .nativeRefined()
                val read = runBlocking {
                    IntellijValueFlowCompilerAdapter()
                        .revalidateSite(
                            project = project,
                            current = fixture.lease,
                            request = request,
                            model = fixture.model,
                        )
                }
                assertTrue("Generated result was not admitted: $read", read is ValueModelSiteRead.Revalidated)
                val source = (read as ValueModelSiteRead.Revalidated).position.site
                assertEquals(callback, source.executionOwner.context is ValueExecutionContext.Callback)
                assertExactUse(fixture = fixture, source = source, function = function, marker = "$name-use")
                source
            }
        assertFalse(sites[0].identity == sites[1].identity)
    }

    private fun assertGeneratedFieldSelection(fixture: NativePacketValueFixture, function: String) {
        ReadAction.run<RuntimeException> {
            val virtual =
                checkNotNull(
                    getOrCreateProjectBaseDir()
                        .findFileByRelativePath("src/fixture/controls/DestructuringFormsControls.kt")
                )
            val file = PsiManager.getInstance(project).findFile(virtual) as KtFile
            val owner = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == function }
            val declaration = checkNotNull(PsiTreeUtil.findChildOfType(owner, KtDestructuringDeclaration::class.java))
            val klass = file.declarations.filterIsInstance<KtClass>().single { it.name == "GeneratedFormComponent" }
            val expected =
                listOf("first", "second").map { name -> klass.primaryConstructorParameters.single { it.name == name } }
            val projection = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot)
            for ((entry, field) in declaration.entries.zip(expected)) {
                val target = nativeComponentTarget(entry, projection)
                assertTrue(
                    "Generated component lacked a compiler-selected field: $target",
                    target is Refinement.Refined,
                )
                val selected = (target as Refinement.Refined).value
                assertTrue(selected is NativeComponentTarget.Field)
                assertSame(field, (selected as NativeComponentTarget.Field).parameter)
            }
        }
    }

    private fun componentReturns(fixture: NativePacketValueFixture, owner: String, component: String): ValueFlowStep {
        val request =
            fixture.selection(PacketDeclaration(CONTROLS, owner, component), "formPayload", declaration("formPayload"))
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue("Component producer was not admitted: $read", read is ValueProducerSeedRead.Seeded)
        val source = (read as ValueProducerSeedRead.Seeded).seed.site
        return step(fixture, next(fixture, source, ValueTransferKind.RETURN))
    }

    private fun assertErasedCallbackOwnerRejects(fixture: NativePacketValueFixture, source: ValueSite) {
        val erased =
            ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = source.range,
                    role = source.role,
                    executionOwner = ValueExecutionOwner.declaration(source.enclosing),
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                )
                .nativeRefined()
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(erased, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Erased callback ownership acquired a component continuation: $read", read is ValueFlowRead.Rejected)
        assertEquals(ValueFlowRejection.NESTED_EXECUTION, (read as ValueFlowRead.Rejected).cause)
    }

    private fun assertExactUse(fixture: NativePacketValueFixture, source: ValueSite, function: String, marker: String) {
        val binding = next(fixture, source, ValueTransferKind.LOCAL_BINDING)
        val use = next(fixture, binding, ValueTransferKind.LOCAL_READ)
        assertEquals(marked(fixture, function, marker).anchor, use.range)
        assertEquals(source.executionOwner, use.executionOwner)
        assertEquals(source.valuePath, use.valuePath)
        assertEquals(source.callStack, use.callStack)
        assertEquals(ValueRole.LocalRead, use.role)
    }

    private fun next(fixture: NativePacketValueFixture, source: ValueSite, kind: ValueTransferKind): ValueSite {
        val read = step(fixture, source)
        assertTrue("Unexpected obligations: ${read.obligations}", read.obligations.isEmpty())
        assertEquals("Missing $kind", listOf(kind), read.transfers.map { it.kind })
        return read.transfers.single().target
    }

    private fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(MAX_BYTES).nativeRefined())
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Component flow was not observed: $read", read is ValueFlowRead.Observed)
        return (read as ValueFlowRead.Observed).step
    }

    private fun controls() = packetFixture(listOf(CONTROLS))

    private fun declaration(name: String) = PacketDeclaration(CONTROLS, "", name)

    private fun marked(fixture: NativePacketValueFixture, function: String, marker: String) =
        fixture.markedExpression(declaration(function), marker)

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringFormsControls.kt"
        const val MAX_BYTES = 16_000_000L
    }
}
