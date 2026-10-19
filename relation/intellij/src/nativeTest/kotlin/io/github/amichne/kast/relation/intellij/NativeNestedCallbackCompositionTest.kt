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
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtProperty

class NativeNestedCallbackCompositionTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "nested-callback-composition-jdk"
    private val proofObservation = CompositionProofObservation()

    override fun runInDispatchThread() = false

    fun testNestedDependencyComposesItsOwner() =
        assertComplete("kotlin.with(Unit) { Unit.also { proof.target() } }", emptySet())

    fun testNestedSourceComposesItsOwner() =
        assertComplete("scope { scope { proof.target() } }", setOf("consumer.scope"))

    fun testSourceInsideDependencyComposesItsOwner() =
        assertComplete("kotlin.with(Unit) { scope { proof.target() } }", setOf("consumer.scope"))

    fun testDependencyInsideSourceComposesItsOwner() =
        assertComplete("scope { Unit.also { proof.target() } }", setOf("consumer.scope"))

    fun testDepthThreeComposesEveryOwner() =
        assertComplete("scope { kotlin.with(Unit) { scope { proof.target() } } }", setOf("consumer.scope"))

    fun testForwardingPreservesEverySupplyContext() =
        assertComplete("relay { scope { proof.target() } }", setOf("consumer.scope", "consumer.relay"))

    fun testDirectLambdaInsideSourceComposesItsOwner() =
        assertComplete("scope { ({ proof.target() })() }", setOf("consumer.scope"))

    fun testSourceInsideDirectLambdaComposesItsOwner() =
        assertComplete("({ scope { proof.target() } })()", setOf("consumer.scope"))

    fun testDirectAnonymousFunctionInsideSourceComposesItsOwner() =
        assertComplete("scope { (fun() { proof.target() })() }", setOf("consumer.scope"))

    fun testStoredOuterCallbackWithExactInvocationComposes() =
        assertComplete("val callback = { scope { proof.target() } }; callback()", setOf("consumer.scope"))

    fun testStoredCallbackInsideKnownOwnerComposes() =
        assertComplete("scope { val callback = { scope { proof.target() } }; callback() }", setOf("consumer.scope"))

    fun testStoredCallbackSuppliedInsideKnownOwnerComposes() =
        assertComplete(
            "scope { val callback = { scope { proof.target() } }; scope(callback) }",
            setOf("consumer.scope"),
        )

    fun testStoredPropertyInsideNamedOwnerKeepsItsLocalIdentity() =
        assertLocalPropertyProjection("val outer = { scope { proof.target() } }; outer()")

    fun testStoredPropertyInsideAnonymousOwnerKeepsItsLocalIdentity() =
        assertLocalPropertyProjection("scope { val outer = { scope { proof.target() } }; outer() }")

    private fun assertLocalPropertyProjection(expression: String) {
        val text = source(expression)
        val fixture = fixture(text, emptyList())
        runBlocking {
            readAction {
                val file = psiManager.findFile(fixture.callerFile) as KtFile
                val property =
                    PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java).single { it.name == "outer" }
                assertTrue(property.isLocal)
                val projected =
                    IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot, proofObservation)
                        .project(property)
                assertTrue(
                    "Expected LocalProperty, received $projected; ${proofObservation.counters}",
                    projected is IntellijRelationDeclarationProjection.Projected,
                )
                val evidence = (projected as IntellijRelationDeclarationProjection.Projected).evidence
                assertTrue(evidence.signature is CanonicalCompilerSignature.LocalProperty)
                assertEquals("outer", evidence.name.value)
                val declaration = "val outer = { scope { proof.target() } }"
                assertEquals(text.indexOf(declaration), evidence.range.startInclusive)
                assertEquals(text.indexOf(declaration) + declaration.length, evidence.range.endExclusive)
                val address = (evidence.signature as CanonicalCompilerSignature.LocalProperty).address
                assertEquals(text.indexOf("fun caller"), address.ownerRange.startInclusive)
                assertEquals(text.trimEnd().length, address.ownerRange.endExclusive)
            }
        }
    }

    fun testAnonymousOwnerDoesNotBecomeANamedDeclaration() {
        val fixture = fixture(source("scope { val outer = { scope { proof.target() } }; outer() }"), emptyList())
        runBlocking {
            readAction {
                val file = psiManager.findFile(fixture.callerFile) as KtFile
                val property =
                    PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java).single { it.name == "outer" }
                val owner = checkNotNull(PsiTreeUtil.getParentOfType(property, KtFunctionLiteral::class.java))
                val projected =
                    IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot, proofObservation)
                        .project(owner)
                assertEquals(IntellijRelationDeclarationProjection.Unsupported, projected)
                assertEquals(
                    1,
                    proofObservation.counters[IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_KIND_UNSUPPORTED],
                )
            }
        }
    }

    fun testStoredCallbackInsideOpaqueOwnerKeepsItsEffectGap() {
        DeclaredEffectArchive.compile().use { archive ->
            val observed =
                observation(
                    source(
                        "effects.absent(marker = 1, block = { " +
                            "val callback = { scope { proof.target() } }; callback() })"
                    ),
                    listOf(archive.jar),
                )
            val flow = (observed.flow as CallbackInvocationFlowRead.Observed).flow
            assertTrue(
                "Expected the opaque owner's effect gap, found ${flow.obligations}",
                flow.obligations.contains(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN),
            )
            assertTrue(CompleteStaticCallbackGraph.admit(observed) is Refinement.Rejected)
        }
    }

    fun testCapturedFormalInsideKnownCallbackKeepsItsInvocationOwner() {
        val text = source("invokeCaptured { proof.target() }")
        val observed = observation(text)
        val flow = (observed.flow as CallbackInvocationFlowRead.Observed).flow
        assertEquals(emptySet<CallbackInvocationFlowCause>(), flow.obligations)
        val graph = CompleteStaticCallbackGraph.admit(observed)
        assertTrue("Expected a complete captured-formal graph", graph is Refinement.Refined)
        val edges = (graph as Refinement.Refined).value.edges
        val invocation =
            edges.filterIsInstance<StaticCallbackEdge.Invoke>().single {
                it.target.body.range == observed.callbackBody.range
            }
        assertTrue(invocation.owner is StaticCallbackNode.Anonymous)
        val owner = invocation.owner as StaticCallbackNode.Anonymous
        val start = text.indexOf("{ block() }", text.indexOf("fun invokeCaptured"))
        assertEquals(start, owner.body.range.startInclusive)
        assertEquals(start + "{ block() }".length, owner.body.range.endExclusive)
    }

    fun testKnownDependencyInsideOpaqueOwnerKeepsItsEffectGap() {
        DeclaredEffectArchive.compile().use { archive ->
            val observed =
                observation(
                    source("effects.absent(marker = 1, block = { Unit.also { proof.target() } })"),
                    listOf(archive.jar),
                )
            val flow = (observed.flow as CallbackInvocationFlowRead.Observed).flow
            assertTrue(flow.obligations.contains(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN))
            assertTrue(CompleteStaticCallbackGraph.admit(observed) is Refinement.Rejected)
        }
    }

    fun testDirectInvocationInsideOpaqueOwnerKeepsItsEffectGap() {
        DeclaredEffectArchive.compile().use { archive ->
            val observed =
                observation(
                    source("effects.absent(marker = 1, block = { ({ proof.target() })() })"),
                    listOf(archive.jar),
                )
            val flow = (observed.flow as CallbackInvocationFlowRead.Observed).flow
            assertTrue(flow.obligations.contains(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN))
            assertTrue(CompleteStaticCallbackGraph.admit(observed) is Refinement.Rejected)
        }
    }

    fun testEscapedCaptureDoesNotAcquireAnInvocationRoute() {
        val observed = observation(source("scope { capture { proof.target() } }"))
        val flow = (observed.flow as CallbackInvocationFlowRead.Observed).flow
        assertTrue(flow.obligations.contains(CallbackInvocationFlowCause.PARAMETER_ESCAPES))
        assertTrue(CompleteStaticCallbackGraph.admit(observed) is Refinement.Rejected)
    }

    fun testMissingBinaryEffectRemainsUnprovenInsideAKnownOwner() {
        DeclaredEffectArchive.compile().use { archive ->
            val observed =
                observation(
                    source("scope { effects.absent(marker = 1, block = { proof.target() }) }"),
                    listOf(archive.jar),
                )
            val flow = (observed.flow as CallbackInvocationFlowRead.Observed).flow
            assertTrue(flow.obligations.contains(CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN))
            assertTrue(CompleteStaticCallbackGraph.admit(observed) is Refinement.Rejected)
        }
    }

    private fun assertComplete(expression: String, owners: Set<String>) {
        val text = source(expression)
        val observed = observation(text)
        val flow = (observed.flow as CallbackInvocationFlowRead.Observed).flow
        assertEquals(
            "Known nested owners must discharge their obligations. ${proofObservation.gauges}",
            emptySet<CallbackInvocationFlowCause>(),
            flow.obligations,
        )
        val admitted = CompleteStaticCallbackGraph.admit(observed)
        assertTrue(
            "Expected a complete composed graph, received ${admitted::class.simpleName}",
            admitted is Refinement.Refined,
        )
        val graph = (admitted as Refinement.Refined).value
        val target = graph.edges.filterIsInstance<StaticCallbackEdge.BodyTarget>().single()
        assertEquals(observed.occurrence, target.occurrence)
        assertEquals("proof.target", identity(target.target))
        assertEquals(observed.callbackBody.range, target.source.body.range)
        val named = graph.nodes.filterIsInstance<StaticCallbackNode.Named>()
        val formals = graph.nodes.filterIsInstance<StaticCallbackNode.Formal>()
        val formalOwners = formals.map {
            (it.parameter.callable.signature as CanonicalCompilerSignature.Function).qualifiedIdentity.value
        }
        assertEquals(owners + setOf("consumer.caller", "proof.target"), (named.map(::identity) + formalOwners).toSet())
        val anonymous =
            graph.nodes.filterIsInstance<StaticCallbackNode.Anonymous>().sortedBy { it.body.range.startInclusive }
        assertEquals(
            bodyRanges(expression, text.indexOf(expression)),
            anonymous.map { it.body.range.startInclusive to it.body.range.endExclusive },
        )
        val route = listOf(named.single { identity(it) == "consumer.caller" }) + anonymous + target.target
        route.zipWithNext().forEach { (from, to) ->
            assertTrue("The composed graph must connect consecutive owner bodies", reachable(graph.edges, from, to))
        }
        val start = text.indexOf("target()")
        assertEquals(start, observed.occurrence.range.startInclusive)
        assertEquals(start + "target".length, observed.occurrence.range.endExclusive)
        println("$name callback proof storage: ${proofObservation.gauges}")
    }

    private fun observation(text: String, libraries: List<Path> = emptyList()): RelationCallbackObservation {
        val fixture = fixture(text, libraries)
        val compilation = runBlocking { fixture.read(RelationMeaning.Callers, proofObservation) }
        assertTrue("Expected exhausted compiler read", compilation is RelationCompilation.Complete)
        val observed = (compilation as RelationCompilation.Complete).batch.callbackObservations.single()
        assertTrue(
            "Expected callback flow evidence, received ${observed.flow}",
            observed.flow is CallbackInvocationFlowRead.Observed,
        )
        return observed
    }

    private fun source(expression: String) =
        "package consumer\n" +
            "fun scope(block: () -> Unit) { block() }\n" +
            "fun relay(block: () -> Unit) { scope(block) }\n" +
            "fun capture(block: () -> Unit) { var captured = block }\n" +
            "fun invokeCaptured(block: () -> Unit) { scope { block() } }\n" +
            "fun caller() { $expression }\n"

    private fun identity(node: StaticCallbackNode.Named) =
        (node.callable.evidence.signature as CanonicalCompilerSignature.Function).qualifiedIdentity.value

    private fun bodyRanges(expression: String, offset: Int): List<Pair<Int, Int>> {
        val starts = ArrayDeque<Int>()
        val ranges = mutableListOf<Pair<Int, Int>>()
        expression.forEachIndexed { index, character ->
            when (character) {
                '{' -> {
                    val declarationPrefix = if (expression.take(index).endsWith("fun() ")) "fun() ".length else 0
                    starts.addLast(offset + index - declarationPrefix)
                }
                '}' -> ranges += starts.removeLast() to offset + index + 1
            }
        }
        return ranges.sortedBy { it.first }
    }

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

private class CompositionProofObservation : IntellijReadObservation by IntellijReadObservation.None {
    val gauges = mutableMapOf<IntellijReadGauge, Long>()
    val counters = mutableMapOf<IntellijReadCounter, Int>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counters[counter] = counters.getOrDefault(counter, 0) + amount
    }

    override fun measure(gauge: IntellijReadGauge, value: IntellijReadGaugeValue) {
        gauges[gauge] = value.value
    }
}
