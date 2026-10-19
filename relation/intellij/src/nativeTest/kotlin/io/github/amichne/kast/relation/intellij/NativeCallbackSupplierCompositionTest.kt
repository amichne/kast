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
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.StaticCallbackEdge
import io.github.amichne.kast.relation.contract.StaticCallbackNode
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

class NativeCallbackSupplierCompositionTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "callback-supplier-composition-jdk"
    private val proofObservation = SupplierProofObservation()

    override fun runInDispatchThread() = false

    fun testStoredSupplierComposesNestedFormalOwner() =
        assertCompleteSupplier(
            source("val callback: () -> Unit = { proof.target() }; invokeCaptured(callback)"),
            listOf("{ block() }", "{ proof.target() }"),
            formalOwner = "consumer.invokeCaptured",
        )

    fun testLiteralSupplierComposesNestedFormalOwner() =
        assertCompleteSupplier(
            source("invokeCaptured { proof.target() }"),
            listOf("{ block() }", "{ proof.target() }"),
            formalOwner = "consumer.invokeCaptured",
        )

    fun testStoredSupplierKeepsOpaqueFormalOwnerGap() {
        DeclaredEffectArchive.compile().use { archive ->
            val text =
                source("val callback: () -> Unit = { proof.target() }; invokeCaptured(callback)")
                    .replace("kotlin.with(Unit) { block() }", "effects.absent(marker = 1, block = { block() })")
            assertEffectGap(observation(text, listOf(archive.jar)))
        }
    }

    fun testDefaultSupplierComposesNamedCaller() =
        assertCompleteSupplier(
            defaultSource("useDefault()"),
            listOf("{ proof.target() }"),
        )

    fun testDefaultSupplierComposesAnonymousCaller() =
        assertCompleteSupplier(
            defaultSource("kotlin.with(Unit) { useDefault() }"),
            listOf("{ useDefault() }", "{ proof.target() }"),
        )

    fun testDefaultSupplierComposesNestedFormalOwner() =
        assertCompleteSupplier(
            defaultSource("useDefault()", "kotlin.with(Unit) { block() }"),
            listOf("{ proof.target() }", "{ block() }"),
            formalOwner = "consumer.useDefault",
        )

    fun testBranchDefaultSupplierKeepsItsAnonymousCaller() {
        val text =
            source("kotlin.with(Unit) { useDefault() }") +
                "fun useDefault(block: () -> Unit = if (true) ({ proof.target() }) else ({})) { block() }\n"
        val fixture = fixture(text, emptyList())
        val compilation = runBlocking { fixture.read(RelationMeaning.Callers, proofObservation) }
        assertTrue("The unsupported named policy must stay qualified", compilation is RelationCompilation.Qualified)
        val observed = (compilation as RelationCompilation.Qualified).batch.callbackObservations.single()
        assertTrue(observed.policy is CallbackNamedCallPolicy.Unavailable)
        assertTrue(
            "The default transfer must preserve its owner evidence: ${observed.flow}",
            observed.flow is CallbackInvocationFlowRead.Immutable,
        )
        val flow = (observed.flow as CallbackInvocationFlowRead.Immutable).flow
        assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
        val owner = flow.ownerBindings.single()
        val body = "{ useDefault() }"
        assertEquals(text.indexOf(body), owner.body.range.startInclusive)
        assertEquals(text.indexOf(body) + body.length, owner.body.range.endExclusive)
        val use = flow.uses.single() as ImmutableCallbackInvocationUse.Supplied
        assertEquals(owner.body, use.supplier.binding.invocationOwner)
        assertTrue(CompleteStaticCallbackGraph.admit(observed) is Refinement.Rejected)
    }

    fun testUnprovedLocalFunctionUseCannotBecomeAnEmptyCompleteGraph() {
        val observed =
            observation(source("val callback: () -> Unit = { proof.target() }; fun later() { callback() }; later()"))
        val flow = (observed.flow as CallbackInvocationFlowRead.Immutable).flow
        assertTrue(
            "An unproved named local owner must keep its gap",
            CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION in flow.obligations,
        )
        assertEquals(CallbackInvocationScan.INCOMPLETE, flow.scan)
        assertTrue(CompleteStaticCallbackGraph.admit(observed) is Refinement.Rejected)
    }

    fun testDefaultSupplierKeepsOpaqueCallerGap() {
        DeclaredEffectArchive.compile().use { archive ->
            val text = defaultSource("effects.absent(marker = 1, block = { useDefault() })")
            assertEffectGap(observation(text, listOf(archive.jar)))
        }
    }

    private fun defaultSource(expression: String, invocation: String = "block()") =
        source(expression) + "fun useDefault(block: () -> Unit = { proof.target() }) { $invocation }\n"

    private fun assertCompleteSupplier(text: String, bodies: List<String>, formalOwner: String? = null) {
        val observed = observation(text)
        val obligations =
            when (val flow = observed.flow) {
                is CallbackInvocationFlowRead.Observed -> flow.flow.obligations
                is CallbackInvocationFlowRead.Immutable -> flow.flow.obligations
                else -> error("Expected typed callback flow: $flow")
            }
        assertEquals(
            "Expected complete supplier owners for ${observed.lexicalOwner.signature}: ${proofObservation.gauges}",
            emptySet<CallbackInvocationFlowCause>(),
            obligations,
        )
        val admitted = CompleteStaticCallbackGraph.admit(observed)
        assertTrue("Expected complete supplier composition, received $admitted", admitted is Refinement.Refined)
        val graph = (admitted as Refinement.Refined).value
        val caller =
            graph.nodes.filterIsInstance<StaticCallbackNode.Named>().single { identity(it) == "consumer.caller" }
        val target = graph.edges.filterIsInstance<StaticCallbackEdge.BodyTarget>().single()
        assertEquals("proof.target", identity(target.target))
        assertEquals(observed.occurrence, target.occurrence)
        assertTrue(
            "The caller must reach the exact target through the supplier route",
            reachable(graph.edges, caller, target.target),
        )
        val formalInvocationOwner = formalOwner?.let {
            assertFormalInvocationOwner(graph = graph, text = text, expectedRoot = it, supplied = target.source)
        }
        val anonymous = graph.nodes.filterIsInstance<StaticCallbackNode.Anonymous>()
        anonymous
            .filter { it != formalInvocationOwner }
            .forEach { owner ->
                assertTrue("The caller must reach each supplier owner", reachable(graph.edges, caller, owner))
                assertTrue("Each supplier owner must reach the target", reachable(graph.edges, owner, target.target))
            }
        val actual = anonymous.map { it.body.range.startInclusive to it.body.range.endExclusive }.toSet()
        val expected = bodies.map { body -> text.lastIndexOf(body) to text.lastIndexOf(body) + body.length }.toSet()
        assertEquals(expected, actual)
    }

    private fun assertFormalInvocationOwner(
        graph: CompleteStaticCallbackGraph,
        text: String,
        expectedRoot: String,
        supplied: StaticCallbackNode.Anonymous,
    ): StaticCallbackNode.Anonymous {
        val invocation = graph.edges.filterIsInstance<StaticCallbackEdge.Invoke>().single { it.target == supplied }
        val owner = invocation.owner as StaticCallbackNode.Anonymous
        val receiver = graph.nodes.filterIsInstance<StaticCallbackNode.Named>().single { identity(it) == expectedRoot }
        val declarationStart = text.indexOf("fun ${expectedRoot.substringAfterLast('.')}(")
        val ownerStart = text.indexOf("{ block() }", declarationStart)
        assertEquals(ownerStart, owner.body.range.startInclusive)
        assertEquals(ownerStart + "{ block() }".length, owner.body.range.endExclusive)
        assertTrue(
            "The receiving callable must reach the formal invocation owner",
            reachable(graph.edges, receiver, owner),
        )
        val formal = invocation.source.parameter
        assertEquals(receiver.callable.evidence.compilerIdentity, formal.callable.compilerIdentity)
        assertEquals(receiver.callable.range, formal.callable.range)
        assertEquals(0, formal.position.value)
        val parameterStart = text.indexOf("block: () -> Unit", declarationStart)
        assertEquals(parameterStart, formal.parameter.range.startInclusive)
        assertEquals(text.indexOf(") {", parameterStart), formal.parameter.range.endExclusive)
        val supply = graph.edges.filterIsInstance<StaticCallbackEdge.Supply>().single { it.body == supplied }
        assertEquals("consumer.caller", identity(supply.source as StaticCallbackNode.Named))
        assertEquals(supply.target, invocation.source)
        return owner
    }

    private fun assertEffectGap(observed: RelationCallbackObservation) {
        val obligations =
            when (val flow = observed.flow) {
                is CallbackInvocationFlowRead.Observed -> flow.flow.obligations
                is CallbackInvocationFlowRead.Immutable -> flow.flow.obligations
                else -> error("Expected typed callback flow: $flow")
            }
        assertTrue(
            "Expected the opaque owner effect gap, received ${obligations}",
            obligations.contains(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN),
        )
        assertTrue(CompleteStaticCallbackGraph.admit(observed) is Refinement.Rejected)
    }

    private fun observation(text: String, libraries: List<Path> = emptyList()): RelationCallbackObservation {
        val fixture = fixture(text, libraries)
        val compilation = runBlocking { fixture.read(RelationMeaning.Callers, proofObservation) }
        assertTrue(
            "Expected exhausted compiler read, received $compilation",
            compilation is RelationCompilation.Complete,
        )
        val observed = (compilation as RelationCompilation.Complete).batch.callbackObservations.single()
        assertTrue(
            "Expected callback flow evidence, received ${observed.flow}",
            observed.flow is CallbackInvocationFlowRead.Observed ||
                observed.flow is CallbackInvocationFlowRead.Immutable,
        )
        return observed
    }

    private fun source(expression: String) =
        "package consumer\n" +
            "fun scope(block: () -> Unit) { block() }\n" +
            "fun relay(block: () -> Unit) { scope(block) }\n" +
            "fun capture(block: () -> Unit) { var captured = block }\n" +
            "fun invokeCaptured(block: () -> Unit) { kotlin.with(Unit) { block() } }\n" +
            "fun caller() { $expression }\n"

    private fun identity(node: StaticCallbackNode.Named) =
        (node.callable.evidence.signature as CanonicalCompilerSignature.Function).qualifiedIdentity.value

    private fun reachable(edges: List<StaticCallbackEdge>, from: StaticCallbackNode, to: StaticCallbackNode): Boolean {
        val visited = mutableSetOf(from)
        val pending = ArrayDeque<StaticCallbackNode>().also { it.add(from) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current == to) return true
            edges.filter { it.source == current }.forEach { if (visited.add(it.target)) pending.add(it.target) }
        }
        return false
    }

    private fun fixture(text: String, libraries: List<Path>): NativeReferenceFixture {
        lateinit var fixture: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture = prepareReferenceFixture()
            val libraryRoot = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib")
            ModuleRootModificationUtil.updateModel(module) { model ->
                val library = model.moduleLibraryTable.createLibrary("composition-stdlib")
                val roots = library.modifiableModel
                (libraries + listOf(libraryRoot.resolve("kotlin-stdlib.jar"))).forEach {
                    roots.addRoot("jar://$it!/", OrderRootType.CLASSES)
                }
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

private class SupplierProofObservation : IntellijReadObservation by IntellijReadObservation.None {
    val gauges = mutableMapOf<IntellijReadGauge, Long>()
    val counters = mutableMapOf<IntellijReadCounter, Int>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counters[counter] = counters.getOrDefault(counter, 0) + amount
    }

    override fun measure(gauge: IntellijReadGauge, value: IntellijReadGaugeValue) {
        gauges[gauge] = value.value
    }
}
