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
import org.jetbrains.kotlin.psi.KtFile

/** Shared native fixture and semantic comparison for the closure controls. */
abstract class NativeFlowClosureFixture : NativePacketValueFixtureTest() {
    abstract override val fixtureSdkName: String

    protected fun headerFrontier(): io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange =
        ReadAction.compute<io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange, RuntimeException> {
            val virtual = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
            val file = PsiManager.getInstance(project).findFile(virtual) as KtFile
            val binary =
                PsiTreeUtil.collectElementsOfType(file, KtBinaryExpression::class.java).single {
                    it.operationReference.text == "to"
                }
            IntellijReadObservation.None.observedAnalyze(file) {
                val selected =
                    checkNotNull(
                        binary.resolveCall() as? org.jetbrains.kotlin.analysis.api.resolution.KaSingleCall<*, *>
                    )
                val callable = selected.signature.symbol as KaNamedFunctionSymbol
                assertEquals("kotlin.to", callable.callableId?.asSingleFqName()?.asString())
                assertEquals(1, callable.valueParameters.size)
                assertEquals("that", callable.valueParameters.single().name.asString())
                println("HRT frontier=${selected.signature} file=${callable.psi?.containingFile?.virtualFile?.url}")
            }
            sourceValueRange(binary)
        }

    protected fun connected(
        function: String,
        producer: String = "origin",
        expected: Boolean = true,
        producerDeclaration: PacketDeclaration = declaration(producer),
        assertion: (NativePacketValueFixture, List<ValueFlowStep>) -> Unit = { _, _ -> },
    ) = runBlocking {
        val fixture = fixture()
        val request = fixture.selection(declaration(function), producer, producerDeclaration)
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
        assertStringEvidence(function, runs[0], arrivals)
        assertion(fixture, runs[0])
    }

    protected suspend fun comparedWalk(
        fixture: NativePacketValueFixture,
        request: io.github.amichne.kast.relation.contract.ValueProducerSeedRequest,
    ): List<ValueFlowStep> {
        val adapter = IntellijValueFlowCompilerAdapter()
        val runs =
            (1..2).map { run ->
                val seed =
                    adapter.seed(project = project, current = fixture.lease, request = request, model = fixture.model)
                assertTrue("Producer admission: $seed", seed is ValueProducerSeedRead.Seeded)
                walk(fixture, adapter, (seed as ValueProducerSeedRead.Seeded).seed.site).also { capture(run, it) }
            }
        assertEquals(runs[0].map(::projection), runs[1].map(::projection))
        return runs[0]
    }

    protected fun qualifiedConversion(function: String, cause: String) = runBlocking {
        val fixture = fixture()
        val request = fixture.selection(declaration(function), "origin", declaration("origin"))
        val adapter = IntellijValueFlowCompilerAdapter()
        val runs =
            (1..2).map { run ->
                val seeded =
                    adapter.seed(project = project, current = fixture.lease, request = request, model = fixture.model)
                assertTrue(seeded is ValueProducerSeedRead.Seeded)
                walk(fixture, adapter, (seeded as ValueProducerSeedRead.Seeded).seed.site).also { capture(run, it) }
            }
        assertEquals(runs[0].map(::projection), runs[1].map(::projection))
        val frontier = fixture.markedExpression(declaration(function), "frontier")
        val obligation = runs[0].flatMap { it.obligations }.single()
        assertEquals(cause, obligation.cause.name)
        assertEquals(frontier.anchor, obligation.site.range)
        assertTrue(runs[0].flatMap { it.transfers }.none { it.kind.name == "STRING_CONTENT" })
    }

    protected fun assertStringEvidence(function: String, steps: List<ValueFlowStep>, arrivals: List<ValueSite>) {
        val content = steps.flatMap { it.transfers }.filter { it.kind.name == "STRING_CONTENT" }
        if (function == "repeatedString") {
            assertEquals(1, arrivals.distinctBy { it.identity }.size)
            assertEquals(2, content.size)
            assertEquals(
                2,
                content
                    .map { (it.evidence as io.github.amichne.kast.relation.contract.ValueStringContent).entryRange }
                    .distinct()
                    .size,
            )
            assertEquals(1, content.map { it.target.identity }.distinct().size)
        }
        if (function == "nullableString") {
            val edge = content.single()
            val evidence = edge.evidence as io.github.amichne.kast.relation.contract.ValueStringContent
            assertEquals(
                io.github.amichne.kast.relation.contract.ValueStringContentInput.NULLABLE_STRING,
                evidence.input,
            )
            assertEquals(
                io.github.amichne.kast.relation.contract.ValueStringNullAlternative
                    .CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT,
                evidence.nullAlternative,
            )
            assertTrue(
                edge.target.conditions.facts.last()
                    is io.github.amichne.kast.relation.contract.ValueCondition.StringContentNonNull
            )
        }
    }

    protected fun capture(run: Int, steps: List<ValueFlowStep>) {
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

    protected fun projection(step: ValueFlowStep) =
        listOf(
            step.source.identity,
            step.transfers,
            step.reviewedTransfers,
            step.obligations,
            step.terminal,
            step.examinedWorkUnits,
            step.stringConstructions,
        )

    protected suspend fun walk(
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

    protected fun declaration(name: String) = PacketDeclaration(CONTROLS, "", name)

    protected fun fixture(): NativePacketValueFixture {
        val fixture = packetFixture(listOf(CONTROLS))
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(NATIVE_PATH))
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                file.setBinaryContent((SOURCE + "\n" + IMPLEMENTATION_CONTROLS).toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        fixture.assertNoFixtureErrors()
        return fixture
    }

    companion object {
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
        val IMPLEMENTATION_CONTROLS =
            """
            fun nullOrigin(): Nothing? = null
            fun nullString() { val value = nullOrigin(); observe("@value") }
            class ObjectToken(val raw: String) { override fun toString(): String = raw }
            fun ObjectToken.toString(): String = "extension"
            fun objectString() { val value = ObjectToken(origin()); observe(/*<observer>*/"@value"/*</observer>*/) }
            class ConstantToken(val ignored: String) { override fun toString(): String = "fixed" }
            open class OpenToken(val raw: String) { override fun toString(): String = raw }
            class ExternalToken(val raw: String)
            fun constantConversion() { val value = ConstantToken(origin()); observe("@value") }
            fun constantConversionResult() { val value = ConstantToken("input"); observe(/*<observer>*/"@{value.toString()}"/*</observer>*/) }
            fun repeatedConversion() { val value = ObjectToken(origin()); observe(/*<observer>*/"@value:@value"/*</observer>*/) }
            fun openConversion() { val value = OpenToken(origin()); observe("@{/*<frontier>*/value/*</frontier>*/}") }
            fun externalConversion() { val value = ExternalToken(origin()); observe("@{/*<frontier>*/value/*</frontier>*/}") }
            fun stringSuffix() { val value = origin(); observe(/*<observer>*/"@value.field"/*</observer>*/) }
            fun rawLiteralDollar() { val value = origin(); observe(TRIPLE@{'@'}valueTRIPLE) }
            interface Choice {
                fun <R> map(block: (String) -> R): Plain<R>
            }
            class Pass(val selected: String, val other: String): Choice {
                override fun <R> map(block: (String) -> R): Plain<R> = Plain(block(selected))
            }
            class Drop(val selected: String, val other: String): Choice {
                override fun <R> map(block: (String) -> R): Plain<R> = Plain(block(other))
            }
            fun getterResult() {
                val value = Constant("input")
                observe(/*<observer>*/"Bearer @{/*<result>*/value.value/*</result>*/}"/*</observer>*/)
            }
            fun implementationAlternatives(flag: Boolean) {
                val raw = origin()
                val initial: Choice = if (flag) Pass(raw, "other") else Drop(raw, "other")
                val result = initial.map { it }
                observe(/*<observer>*/result.value/*</observer>*/)
            }
            """
                .trimIndent()
                .replace("@", "${'$'}")
                .replace("TRIPLE", "\"\"\"")
    }
}
