package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.jetbrains.kotlin.psi.KtProperty

/** Proposed native regression controls. They do not establish parity with the reported AssertK incident. */
class NativeImpactBoundaryReproductionTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "impact-boundary-reproduction-jdk"

    fun testBindingOnlyPreservesExactLocalReadWithoutACall() {
        val fixture = fixture()
        val steps = supported(trace(fixture, "bindingOnly"))
        assertLocalPrefix(fixture, "bindingOnly", steps)
        val expected = fixture.markedExpression(declaration("bindingOnly"), "use")
        val read = steps.single { step ->
            step.source.range == expected.anchor &&
                step.source.role == ValueRole.LocalRead &&
                step.source.enclosing.compilerIdentity == expected.enclosing.compilerIdentity
        }
        assertTrue("Discarded local read invented a successor", read.transfers.isEmpty())
        assertTrue("Binding-only fixture reached a consumer call", consumerArguments(fixture, steps).isEmpty())
    }

    fun testDirectReturnPreservesProducerResult() {
        val fixture = fixture()
        val steps = supported(trace(fixture, "directReturn"))
        val expected = fixture.markedExpression(declaration("directReturn"), "returned")
        assertTrue(
            "Producer result did not reach the exact direct return",
            steps
                .flatMap { it.transfers }
                .any { edge ->
                    edge.kind == ValueTransferKind.RETURN &&
                        edge.target.range == expected.anchor &&
                        edge.target.role == ValueRole.Return &&
                        edge.target.enclosing.compilerIdentity == expected.enclosing.compilerIdentity
                },
        )
    }

    fun testLocalReturnPreservesBindingReadAndReturn() {
        val fixture = fixture()
        val steps = supported(trace(fixture, "localReturn"))
        assertLocalPrefix(fixture, "localReturn", steps)
        val expected = fixture.markedExpression(declaration("localReturn"), "use")
        assertTrue(
            "Exact local read did not become the function return",
            steps
                .flatMap { it.transfers }
                .any { edge ->
                    edge.kind == ValueTransferKind.RETURN &&
                        edge.source.role == ValueRole.LocalRead &&
                        edge.source.range == expected.anchor &&
                        edge.target.role == ValueRole.Return &&
                        edge.target.range == expected.anchor
                },
        )
    }

    fun testLocalReadBindsToTheExactSourceConsumer() {
        val fixture = fixture()
        val steps = supported(trace(fixture, "localArgument"))
        assertLocalPrefix(fixture, "localArgument", steps)
        val expected = fixture.markedExpression(declaration("localArgument"), "use")
        val arrival = consumerArguments(fixture, steps).single()
        assertEquals(expected.anchor, arrival.range)
        assertEquals(expected.enclosing.compilerIdentity, arrival.enclosing.compilerIdentity)
        assertEquals(0, (arrival.role as ValueRole.Argument).position.value)
        assertTrue(
            "Bound source argument did not enter its formal",
            steps
                .flatMap { it.transfers }
                .any {
                    it.source.identity == arrival.identity && it.kind == ValueTransferKind.SOURCE_CALL_ENTRY
                },
        )
    }

    fun testDirectNestedArgumentBindsProducerToConsumer() {
        val fixture = fixture()
        val steps = supported(trace(fixture, "directNestedArgument"))
        val expected = fixture.markedExpression(declaration("directNestedArgument"), "use")
        val arrival = consumerArguments(fixture, steps).single()
        assertEquals(expected.anchor, arrival.range)
        assertEquals(expected.enclosing.compilerIdentity, arrival.enclosing.compilerIdentity)
        assertEquals(0, (arrival.role as ValueRole.Argument).position.value)
        assertTrue(
            "Direct producer result did not bind to its exact consumer argument",
            steps
                .flatMap { it.transfers }
                .any { edge ->
                    edge.kind == ValueTransferKind.ARGUMENT &&
                        edge.source.range == expected.anchor &&
                        edge.source.role == ValueRole.ExpressionResult &&
                        edge.target.identity == arrival.identity
                },
        )
    }

    fun testNestedArgumentReturnsToItsMatchedInvocation() {
        val fixture = fixture()
        val steps = supported(trace(fixture, "nestedArgument"))
        val expected = fixture.markedExpression(declaration("nestedArgument"), "relay-result")
        val relay = fixture.callableDeclaration(declaration("relay"))
        val input =
            steps
                .map { it.source }
                .single {
                    (it.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity == relay.compilerIdentity
                }
        val invocation = (input.role as ValueRole.Argument).call
        val returned =
            steps
                .flatMap { it.transfers }
                .single {
                    it.kind == ValueTransferKind.SOURCE_CALL_RETURN && it.target.range == expected.anchor
                }
        assertEquals(invocation.resultSite().identity, returned.target.identity)
        assertTrue("Matched return retained an unmatched call", returned.target.callStack.frames.isEmpty())
        assertEquals(expected.anchor, consumerArguments(fixture, steps).single().range)
    }

    fun testSourceWrapperPreservesOnlyItsSelectedField() {
        val fixture = fixture()
        val steps = supported(trace(fixture, "sourceWrapper"))
        val expected = fixture.markedExpression(declaration("sourceWrapper"), "payload")
        assertTrue(steps.flatMap { it.transfers }.any { it.kind == ValueTransferKind.CONSTRUCTOR_FIELD })
        assertTrue(steps.flatMap { it.transfers }.any { it.kind == ValueTransferKind.FIELD_READ })
        val arrival = consumerArguments(fixture, steps).single()
        assertEquals(expected.anchor, arrival.range)
        assertTrue("Unwrapped payload retained a field path", arrival.valuePath.segments.isEmpty())
        assertTrue("Unwrapped payload retained an unmatched call", arrival.callStack.frames.isEmpty())
    }

    fun testDiscardedInputCannotReachAConstantResult() {
        val fixture = fixture()
        val steps = supported(trace(fixture, "discardedInput"))
        val constant = fixture.callableDeclaration(declaration("constant"))
        assertTrue(
            "Negative control did not reach the constant callee's input",
            steps
                .map { it.source }
                .any {
                    (it.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity == constant.compilerIdentity
                },
        )
        val forbidden = fixture.markedExpression(declaration("discardedInput"), "constant-result")
        assertTrue("Discarded input reached the outer consumer", consumerArguments(fixture, steps).isEmpty())
        assertTrue(
            "Discarded input became the constant invocation's result",
            steps
                .flatMap { it.transfers }
                .none {
                    it.target.range == forbidden.anchor && it.target.role == ValueRole.ExpressionResult
                },
        )
    }

    fun testSameTypedOtherFieldCannotAcquireProducerProvenance() {
        val fixture = fixture()
        val steps = supported(trace(fixture, "otherField"))
        assertTrue(steps.flatMap { it.transfers }.any { it.kind == ValueTransferKind.CONSTRUCTOR_FIELD })
        assertTrue("The other String field acquired producer provenance", consumerArguments(fixture, steps).isEmpty())
        val forbidden = fixture.markedExpression(declaration("otherField"), "unrelated")
        assertTrue(
            "The unrelated field was emitted as the tracked payload",
            steps
                .flatMap { it.transfers }
                .none {
                    it.kind == ValueTransferKind.FIELD_READ && it.target.range == forbidden.anchor
                },
        )
    }

    fun testExcludedJdkCallRetainsPrefixAndExactBoundary() = assertExcludedCall(fixture(), "jdkArgument")

    fun testCompiledDependencyKeepsBinaryPolicyBoundaryWithoutInventingResultPropagation() {
        DeclaredEffectArchive.compile().use { archive ->
            val fixture =
                fixture("BinaryBoundaryCase.kt") {
                    ModuleRootModificationUtil.updateModel(module) { model ->
                        val library = model.moduleLibraryTable.createLibrary("impact-boundary-binary")
                        val roots = library.modifiableModel
                        roots.addRoot("jar://${archive.jar}!/", OrderRootType.CLASSES)
                        roots.commit()
                    }
                }
            assertExcludedCall(fixture, "binaryArgument")
        }
    }

    private fun assertExcludedCall(fixture: NativePacketValueFixture, function: String) {
        val steps = observed(trace(fixture, function))
        assertLocalPrefix(fixture, function, steps)
        val expected = fixture.markedExpression(declaration(function), "use")
        val blocked = steps.single { it.obligations.isNotEmpty() }
        assertEquals(ValueFlowTerminal.Unresolved, blocked.terminal)
        val obligation = blocked.obligations.single()
        assertEquals(ValueFlowUnsupportedCause.EXTERNAL_CALL, obligation.cause)
        assertEquals(expected.anchor, obligation.site.range)
        assertEquals(expected.enclosing.compilerIdentity, obligation.site.enclosing.compilerIdentity)
        assertEquals(ValueRole.LocalRead, obligation.site.role)
        assertTrue("Excluded call manufactured an input-to-result path", consumerArguments(fixture, steps).isEmpty())
        val result = fixture.markedExpression(declaration(function), "external-result")
        assertTrue(
            steps
                .flatMap { it.transfers }
                .none {
                    it.target.range == result.anchor && it.target.role == ValueRole.ExpressionResult
                }
        )
    }

    private fun assertLocalPrefix(fixture: NativePacketValueFixture, function: String, steps: List<ValueFlowStep>) {
        val binding = binding(fixture, function)
        val use = fixture.markedExpression(declaration(function), "use")
        val edges = steps.flatMap { it.transfers }
        val bound =
            edges
                .single {
                    it.kind == ValueTransferKind.LOCAL_BINDING && it.target.range == binding.anchor
                }
                .target
        assertEquals(ValueRole.LocalBinding, bound.role)
        assertTrue(
            "Established binding lost its exact local read",
            edges.any { edge ->
                edge.kind == ValueTransferKind.LOCAL_READ &&
                    edge.source.identity == bound.identity &&
                    edge.target.range == use.anchor &&
                    edge.target.role == ValueRole.LocalRead &&
                    edge.target.enclosing.compilerIdentity == use.enclosing.compilerIdentity
            },
        )
    }

    private fun binding(fixture: NativePacketValueFixture, function: String): PacketExpression {
        val selected = fixture.selection(declaration(function), "producer", declaration("producer"))
        return ReadAction.compute<PacketExpression, RuntimeException> {
            val virtual =
                checkNotNull(LocalFileSystem.getInstance().findFileByPath(selected.enclosing.file.stableValue))
            val file = checkNotNull(PsiManager.getInstance(project).findFile(virtual))
            val leaf = checkNotNull(file.findElementAt(selected.anchor.startInclusive))
            val property = checkNotNull(PsiTreeUtil.getParentOfType(leaf, KtProperty::class.java))
            assertEquals("value", property.name)
            assertEquals(selected.anchor, range(checkNotNull(property.initializer)))
            PacketExpression(selected.enclosing, range(property))
        }
    }

    private fun consumerArguments(fixture: NativePacketValueFixture, steps: List<ValueFlowStep>): List<ValueSite> {
        val consumer = fixture.callableDeclaration(declaration("consume"))
        return steps
            .map { it.source }
            .filter {
                (it.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity == consumer.compilerIdentity
            }
    }

    private fun supported(run: RecordedRun): List<ValueFlowStep> =
        observed(run).also { steps ->
            assertTrue(
                "Supported control retained obligations: ${run.summary()}",
                steps.all { it.obligations.isEmpty() },
            )
            assertTrue(
                "Supported control did not exhaust: ${run.summary()}",
                steps.all { it.terminal == ValueFlowTerminal.SupportedDomainExhausted },
            )
        }

    private fun observed(run: RecordedRun): List<ValueFlowStep> {
        assertTrue("Seed not admitted: ${run.summary()}", run.seed is ValueProducerSeedRead.Seeded)
        assertTrue("Fixture executed no reads: ${run.summary()}", run.reads.isNotEmpty())
        assertTrue(
            "Native read rejected or suspended: ${run.summary()}",
            run.reads.all { it.read is ValueFlowRead.Observed },
        )
        assertTrue("Unexpected native failure: ${run.summary()}", run.failures.isEmpty())
        return run.reads
            .map { (it.read as ValueFlowRead.Observed).step }
            .also { steps ->
                assertTrue("Control unexpectedly used reviewed transfers", steps.all { it.reviewedTransfers.isEmpty() })
            }
    }

    private fun trace(fixture: NativePacketValueFixture, function: String): RecordedRun =
        ImpactBoundaryTrace(project, fixture)
            .run(fixture.selection(declaration(function), "producer", declaration("producer")))

    private fun fixture(
        resource: String = "BoundaryCases.kt",
        configureCompiler: () -> Unit = {},
    ): NativePacketValueFixture {
        // Reuse the existing private indexed-project harness. Replace only this test's private file.
        val fixture = packetFixture(listOf(CONTROLS), configureCompiler = configureCompiler)
        val source = checkNotNull(javaClass.getResourceAsStream("/impact-boundary/$resource"))
        val bytes = source.use { it.readBytes() }
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) { file.setBinaryContent(bytes) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        fixture.assertNoFixtureErrors()
        return fixture
    }

    private fun declaration(name: String) = PacketDeclaration(CONTROLS, "", name)

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
        const val NATIVE_PATH = "src/fixture/controls/DestructuringControls.kt"
    }
}
