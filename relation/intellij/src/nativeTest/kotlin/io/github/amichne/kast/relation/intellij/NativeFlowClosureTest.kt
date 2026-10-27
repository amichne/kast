@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/** Synthetic comparisons keep the producer, domain, grants, and callback implementation fixed. */
class NativeFlowClosureTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "native-flow-closure-jdk"

    fun testNamedConcrete() = connected("namedConcrete")

    fun testAnonymousConcrete() = connected("anonymousConcrete")

    fun testNamedInterface() = connected("namedInterface")

    fun testAnonymousInterface() = connected("anonymousInterface")

    fun testInnerConcreteProducer() = connected("innerConcrete", "convert")

    fun testInnerInterfaceProducer() = connected("innerInterface", "convert")

    fun testHelperReturnInCallback() = connected("helperCase")

    fun testErrorCallback() = connected("errorCase")

    fun testConstantCallbackDiscardsInput() = connected("constantCallback", expected = false)

    fun testSiblingImplementationDiscardsInput() = connected("otherImplementation", expected = false)

    fun testSimpleStringContent() = connected("simpleString")

    fun testBracedStringContent() = connected("bracedString")

    fun testRepeatedStringContent() = connected("repeatedString")

    fun testRawStringContent() = connected("rawString")

    fun testNullableStringContent() = connected("nullableString", "nullableOrigin")

    fun testEscapedDollarHasNoContent() = connected("escapedDollar", expected = false)

    fun testLiteralDollarHasNoContent() = connected("literalDollar", expected = false)

    fun testConstantGetterDiscardsInput() = connected("constantGetter", expected = false)

    fun testConstantResultContributesContent() = connected("constantResult", "constant")

    fun testConstantCallDiscardsInput() = connected("constantResult", expected = false)

    fun testHelperResultReturnsToItsOwnCallback() = connected("helperCase", "wrap")

    fun testGenericFactoryInCallback() = connected("factoryCase")

    fun testUninvokedProducerKeepsTheAcquisitionCause() = runBlocking {
        val fixture = fixture()
        val request = fixture.selection(declaration("uninvoked"), "origin", declaration("origin"))
        val results =
            (1..2).map {
                IntellijValueFlowCompilerAdapter()
                    .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
            }
        println("UNINVOKED anchor=${request.anchor} results=$results")
        results.forEach { result ->
            assertTrue(result is ValueProducerSeedRead.Rejected)
            assertEquals("NESTED_EXECUTION", (result as ValueProducerSeedRead.Rejected).cause.name)
        }
    }

    fun testOriginalTokenKeepsExactLibraryFrontier() = runBlocking {
        val fixture = fixture()
        val request = fixture.selection(declaration("headerRoute"), "origin", declaration("origin"))
        val adapter = IntellijValueFlowCompilerAdapter()
        val seeded = adapter.seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        assertTrue(seeded is ValueProducerSeedRead.Seeded)
        val steps = walk(fixture, adapter, (seeded as ValueProducerSeedRead.Seeded).seed.site)
        capture(1, steps)
        assertTrue(
            "Original token must reach template construction",
            steps.flatMap { it.transfers }.any { it.kind.name == "STRING_CONTENT" },
        )
        val header = fixture.callableDeclaration(declaration("buildHeaders"))
        assertTrue(
            "The exact library frontier remains in buildHeaders",
            steps
                .flatMap { it.obligations }
                .any {
                    it.site.enclosing.compilerIdentity == header.compilerIdentity && it.cause.name == "EXTERNAL_CALL"
                },
        )
        assertTrue(
            "Fallback cannot assert header argument arrival",
            steps
                .flatMap { it.transfers }
                .none {
                    (it.target.role as? ValueRole.Argument)?.call?.callable?.signature.toString().contains("fetch")
                },
        )
    }

    fun testDownstreamHeaderSeedKeepsEmptyImplementationInventory() = runBlocking {
        val fixture = fixture()
        val request = fixture.selection(declaration("headerRoute"), "buildHeaders", declaration("buildHeaders"))
        val adapter = IntellijValueFlowCompilerAdapter()
        val seeded = adapter.seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        assertTrue(seeded is ValueProducerSeedRead.Seeded)
        val steps = walk(fixture, adapter, (seeded as ValueProducerSeedRead.Seeded).seed.site)
        capture(1, steps)
        val obligations = steps.flatMap { it.obligations }
        assertEquals(1, obligations.size)
        assertEquals("UNMODELED_CALL", obligations.single().cause.name)
        val evidence =
            obligations.single().evidence
                as io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence.ImplementationDispatch
        assertTrue(
            evidence.dispatch.coverage is io.github.amichne.kast.relation.contract.ValueImplementationCoverage.Exhausted
        )
        assertTrue(evidence.dispatch.batch.facts.isEmpty())
    }

    fun testHeaderSelectedLibraryOverloads() {
        fixture()
        ReadAction.run<RuntimeException> {
            val virtual = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
            val file = PsiManager.getInstance(project).findFile(virtual) as KtFile
            IntellijReadObservation.None.observedAnalyze(file) {
                val maps =
                    PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java).filter {
                        it.calleeExpression?.text == "mapOf"
                    }
                assertEquals(1, maps.size)
                val selected = checkNotNull(maps.single().resolveCall())
                val symbol = selected.signature.symbol as KaNamedFunctionSymbol
                assertEquals("kotlin.collections.mapOf", symbol.callableId?.asSingleFqName()?.asString())
                assertEquals(1, symbol.valueParameters.size)
                assertFalse("Single-pair overload, not vararg", symbol.valueParameters.single().isVararg)
                println(
                    "HEADER mapOf signature=${selected.signature} " +
                        "declaration=${symbol.psi?.text} file=${symbol.psi?.containingFile?.virtualFile?.url}"
                )
                val pair =
                    PsiTreeUtil.collectElementsOfType(file, KtBinaryExpression::class.java).single {
                        it.operationReference.text == "to"
                    }
                val to =
                    checkNotNull(pair.resolveCall() as? org.jetbrains.kotlin.analysis.api.resolution.KaSingleCall<*, *>)
                val callable = to.signature.symbol as KaNamedFunctionSymbol
                assertEquals("kotlin.to", callable.callableId?.asSingleFqName()?.asString())
                println(
                    "HEADER to signature=${to.signature} " +
                        "declaration=${callable.psi?.text} file=${callable.psi?.containingFile?.virtualFile?.url}"
                )
            }
        }
    }

    private fun connected(function: String, producer: String = "origin", expected: Boolean = true) = runBlocking {
        val fixture = fixture()
        val request = fixture.selection(declaration(function), producer, declaration(producer))
        println(
            "CASE=$function producer=$producer anchor=${request.anchor} basis=${fixture.lease.identity} " +
                "scope=production/exclude-generated budget=${fixture.budget}"
        )
        val adapter = IntellijValueFlowCompilerAdapter()
        val seeds =
            (1..2).map { run ->
                adapter
                    .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
                    .also { println("RUN=$run seed=$it") }
            }
        val runs = seeds.mapIndexed { index, seeded ->
            val run = index + 1
            assertTrue("Producer admission: $seeded", seeded is ValueProducerSeedRead.Seeded)
            val steps = walk(fixture, adapter, (seeded as ValueProducerSeedRead.Seeded).seed.site)
            capture(run, steps)
            steps
        }
        // Equality includes basis, implementations, frames, conditions, field paths and multiplicity.
        assertEquals(runs[0].map(::projection), runs[1].map(::projection))
        assertTrue(
            "Unexpected unresolved route: ${callbackRouteSummary(runs[0])}",
            runs[0].all { it.obligations.isEmpty() },
        )
        val observer = fixture.callableDeclaration(declaration("observe"))
        val arrivals =
            runs[0]
                .flatMap { it.transfers }
                .map { it.target }
                .filter {
                    (it.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity == observer.compilerIdentity
                }
        assertEquals(
            "Expected fixed original-seed arrival: ${callbackRouteSummary(runs[0])}",
            expected,
            arrivals.isNotEmpty(),
        )
        if (expected) {
            val site = fixture.markedExpression(declaration(function), "observer")
            assertTrue(
                arrivals.all {
                    it.range == site.anchor && it.enclosing.compilerIdentity == site.enclosing.compilerIdentity
                }
            )
        }
    }

    private fun capture(run: Int, steps: List<ValueFlowStep>) {
        println("RUN=$run ${callbackRouteSummary(steps)}")
        steps.forEach { step ->
            println(
                "READ range=${step.source.range} owner=${step.source.enclosing.signature} " +
                    "execution=${step.source.executionOwner.context} path=${step.source.valuePath.segments} " +
                    "stack=${step.source.callStack.frames} conditions=${step.source.conditions.facts} " +
                    "terminal=${step.terminal} work=${step.examinedWorkUnits.value}"
            )
            step.transfers.forEach {
                println("EDGE ${it.kind} ${it.source.range}->${it.target.range} evidence=${it.evidence}")
            }
            step.obligations.forEach { println("OBLIGATION ${it.cause} ${it.site.range} ${it.evidence}") }
        }
    }

    private fun projection(step: ValueFlowStep) =
        listOf(
            step.source.identity,
            step.transfers,
            step.reviewedTransfers,
            step.obligations,
            step.terminal,
            step.examinedWorkUnits,
        )

    private suspend fun walk(
        fixture: NativePacketValueFixture,
        adapter: IntellijValueFlowCompilerAdapter,
        seed: ValueSite,
    ): List<ValueFlowStep> {
        val pending = ArrayDeque<ValueSite>().apply { add(seed) }
        val visited = linkedSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(MAX_READ_BYTES).nativeRefined())
        while (pending.isNotEmpty()) {
            val source = pending.removeFirst()
            if (!visited.add(source.identity)) continue
            assertTrue("Finite synthetic route exceeded 256 sites", visited.size <= MAX_SITES)
            val read =
                adapter.read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
            assertTrue("Expected native read at ${source.range}: $read", read is ValueFlowRead.Observed)
            val step = (read as ValueFlowRead.Observed).step
            steps += step
            step.transfers.forEach { pending.add(it.target) }
        }
        return steps
    }

    private fun declaration(name: String) = PacketDeclaration(CONTROLS, "", name)

    private fun fixture(): NativePacketValueFixture {
        val fixture = packetFixture(listOf(CONTROLS))
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) { file.setBinaryContent(SOURCE.toByteArray()) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        fixture.assertNoFixtureErrors()
        return fixture
    }

    private companion object {
        const val MAX_SITES = 256
        const val MAX_READ_BYTES = 16_000_000L
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
        const val NATIVE_PATH = "src/fixture/controls/DestructuringControls.kt"
        val SOURCE =
            """
            package fixture.closure
            fun origin(): String = "token"
            fun uninvoked(): () -> String = { origin() }
            fun nullableOrigin(): String? = "token"
            fun convert(value: String): String = value
            fun observe(value: String?) = Unit
            fun <T> invokeNow(block: () -> T): T = block()
            fun <T> wrap(value: T): T = value
            fun constant(ignored: String): String = "fixed"
            class Plain<T>(val value: T) {
                fun <R> map(block: (T) -> R): Plain<R> = Plain(block(value))
            }
            interface Result<T> {
                fun <R> map(block: (T) -> R): Result<R>
                fun payload(): T
            }
            class Good<T>(val value: T): Result<T> {
                override fun <R> map(block: (T) -> R): Result<R> = Good(block(value))
                override fun payload(): T = value
            }
            class Other(val value: String): Result<String> {
                override fun <R> map(block: (String) -> R): Result<R> = Empty<R>()
                override fun payload(): String = value
            }
            class Empty<T>: Result<T> {
                override fun <R> map(block: (T) -> R): Result<R> = Empty<R>()
                override fun payload(): T = throw IllegalStateException()
            }
            class Failure<E>(val error: E) {
                fun <F> mapError(block: (E) -> F): Failure<F> = Failure(block(error))
            }
            class Envelope<T>(val value: T)
            fun <T> factory(value: T): Envelope<T> = Envelope(value)
            fun factoryCase() = invokeNow { val result = Plain(origin()).map { factory(it) }; observe(/*<observer>*/result.value.value/*</observer>*/) }
            data class AccessToken(val raw: String)
            interface RemoteApi { fun fetch(headers: Map<String, String>) }
            fun buildHeaders(token: AccessToken) = mapOf("Authorization" to "Bearer @{token.raw}")
            fun headerRoute(api: RemoteApi) = api.fetch(buildHeaders(AccessToken(origin())))
            class Constant(val ignored: String) { val value: String get() = "fixed" }
            fun namedConcrete() { val result = Plain(origin()).map { it }; observe(/*<observer>*/result.value/*</observer>*/) }
            fun anonymousConcrete() = invokeNow { val result = Plain(origin()).map { it }; observe(/*<observer>*/result.value/*</observer>*/) }
            fun namedInterface() { val initial: Result<String> = Good(origin()); val result = initial.map { it }; observe(/*<observer>*/result.payload()/*</observer>*/) }
            fun anonymousInterface() = invokeNow { val initial: Result<String> = Good(origin()); val result = initial.map { it }; observe(/*<observer>*/result.payload()/*</observer>*/) }
            fun innerConcrete() = invokeNow { val result = Plain("input").map { convert(it) }; observe(/*<observer>*/result.value/*</observer>*/) }
            fun innerInterface() = invokeNow { val initial: Result<String> = Good("input"); val result = initial.map { convert(it) }; observe(/*<observer>*/result.payload()/*</observer>*/) }
            fun helperCase() = invokeNow { val result = Plain(origin()).map { wrap(it) }; observe(/*<observer>*/result.value/*</observer>*/) }
            fun errorCase() = invokeNow { val result = Failure(origin()).mapError { it }; observe(/*<observer>*/result.error/*</observer>*/) }
            fun constantCallback() { val result = Plain(origin()).map { "fixed" }; observe(result.value) }
            fun otherImplementation() { val initial: Result<String> = Other(origin()); val result = initial.map { it }; observe(result.payload()) }
            fun simpleString() { val value = origin(); observe(/*<observer>*/"Bearer @value"/*</observer>*/) }
            fun bracedString() { observe(/*<observer>*/"Bearer @{origin()}"/*</observer>*/) }
            fun repeatedString() { val value = origin(); observe(/*<observer>*/"@value:@{value}"/*</observer>*/) }
            fun rawString() { val value = origin(); observe(/*<observer>*/TRIPLE\@valueTRIPLE/*</observer>*/) }
            fun nullableString() { val value = nullableOrigin(); observe(/*<observer>*/"Bearer @value"/*</observer>*/) }
            fun escapedDollar() { val value = origin(); observe("\@value") }
            fun literalDollar() { val value = origin(); observe("@{'@'}value") }
            fun constantGetter() { val value = Constant(origin()); observe("@{value.value}") }
            fun constantResult() { observe(/*<observer>*/"Bearer @{constant(origin())}"/*</observer>*/) }
            """
                .trimIndent()
                .replace("@", "${'$'}")
                .replace("TRIPLE", "\"\"\"")
    }
}
