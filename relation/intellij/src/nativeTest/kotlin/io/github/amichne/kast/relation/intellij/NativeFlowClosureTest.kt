@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/** Synthetic comparisons keep the producer, domain, grants, and callback implementation fixed. */
class NativeFlowClosureTest : NativeFlowClosureFixture() {
    override val fixtureSdkName = "native-flow-closure-jdk"

    fun testNamedConcrete() = connected("namedConcrete")

    fun testAnonymousConcrete() = connected("anonymousConcrete")

    fun testNamedInterface() = connected("namedInterface")

    fun testAnonymousInterface() = connected("anonymousInterface")

    fun testInnerConcreteProducer() = connected("innerConcrete", "convert")

    fun testInnerInterfaceProducer() = connected("innerInterface", "convert")

    fun testHelperReturnInCallback() = connected("helperCase")

    fun testErrorCallback() = connected("errorCase")

    fun testObjectStringUsesItsMemberBody() =
        connected(
            "objectString",
            assertion = { fixture, steps ->
                val member = fixture.callableDeclaration(PacketDeclaration(CONTROLS, "ObjectToken", "toString"))
                val calls =
                    steps
                        .flatMap { it.transfers }
                        .mapNotNull { (it.target.role as? ValueRole.StringConversionResult)?.invocation }
                assertTrue(calls.isNotEmpty())
                assertTrue(calls.all { it.callable.compilerIdentity == member.compilerIdentity })
            },
        )

    fun testOpenConversionKeepsItsUnprovedDispatch() = qualifiedConversion("openConversion", "UNMODELED_CALL")

    fun testExternalConversionKeepsItsExactFrontier() = qualifiedConversion("externalConversion", "EXTERNAL_CALL")

    fun testConstantConversionDiscardsInput() = connected("constantConversion", expected = false)

    fun testConstantConversionResultContributesContent() =
        connected(
            function = "constantConversionResult",
            producer = "toString",
            producerDeclaration = PacketDeclaration(CONTROLS, "ConstantToken", "toString"),
        )

    fun testRepeatedConversionKeepsBothOccurrences() =
        connected(
            "repeatedConversion",
            assertion = { _, steps ->
                val content = steps.flatMap { it.transfers }.filter { it.kind.name == "STRING_CONTENT" }
                assertEquals(2, content.size)
                assertEquals(
                    2,
                    content
                        .map { (it.source.role as ValueRole.StringConversionResult).invocation.range }
                        .distinct()
                        .size,
                )
                assertEquals(1, content.map { it.target.identity }.distinct().size)
            },
        )

    fun testStringSuffixHasNoFieldRead() =
        connected(
            "stringSuffix",
            assertion = { _, steps ->
                assertTrue(steps.flatMap { it.transfers }.none { it.kind.name == "FIELD_READ" })
            },
        )

    fun testRawLiteralDollarHasNoContent() = connected("rawLiteralDollar", expected = false)

    fun testNullStringKeepsExplainedConstantConstruction() =
        connected(
            function = "nullString",
            producer = "nullOrigin",
            expected = false,
            assertion = { _, steps ->
                val records = steps.flatMap { step ->
                    val getter = step.javaClass.methods.singleOrNull { it.name == "getStringConstructions" }
                    (getter?.invoke(step) as? List<*>).orEmpty()
                }
                assertEquals("The null branch must keep its successful constant construction", 1, records.size)
            },
        )

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
        val steps = comparedWalk(fixture, request)
        assertTrue(
            "Original token must reach template construction",
            steps.flatMap { it.transfers }.any { it.kind.name == "STRING_CONTENT" },
        )
        val header = fixture.callableDeclaration(declaration("buildHeaders"))
        val frontier = headerFrontier()
        val fetch = fixture.callableDeclaration(PacketDeclaration(CONTROLS, "RemoteApi", "fetch"))
        val obligation = steps.flatMap { it.obligations }.single()
        assertEquals(header.compilerIdentity, obligation.site.enclosing.compilerIdentity)
        assertEquals("EXTERNAL_CALL", obligation.cause.name)
        assertEquals(
            frontier,
            (obligation.evidence as io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence.Expression)
                .range,
        )
        assertTrue(
            "Fallback cannot assert header argument arrival",
            steps
                .flatMap { it.transfers }
                .none {
                    (it.target.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity == fetch.compilerIdentity
                },
        )
    }

    fun testDownstreamHeaderSeedKeepsEmptyImplementationInventory() = runBlocking {
        val fixture = fixture()
        val request = fixture.selection(declaration("headerRoute"), "buildHeaders", declaration("buildHeaders"))
        val steps = comparedWalk(fixture, request)
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
        val fetch = fixture.callableDeclaration(PacketDeclaration(CONTROLS, "RemoteApi", "fetch"))
        val argument = obligations.single().site.role as ValueRole.Argument
        assertEquals(fetch.compilerIdentity, argument.call.callable.compilerIdentity)
        assertEquals(0, argument.position.value)
        assertEquals(request.anchor, obligations.single().site.range)
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

    fun testImplementationAlternativesKeepTheirOwnFieldProvenance() =
        connected(
            function = "implementationAlternatives",
            assertion = { fixture, steps ->
                val pass = fixture.callableDeclaration(PacketDeclaration(CONTROLS, "Pass", "map"))
                val drop = fixture.callableDeclaration(PacketDeclaration(CONTROLS, "Drop", "map"))
                val entries =
                    steps
                        .flatMap { it.transfers }
                        .mapNotNull { transfer ->
                            (transfer.evidence as? io.github.amichne.kast.relation.contract.ValueSourceTransfer)
                                ?.operation
                                as? io.github.amichne.kast.relation.contract.ValueSourceOperation.CallbackArgumentEntry
                        }
                assertTrue(entries.isNotEmpty())
                assertTrue(
                    entries.all { entry ->
                        entry.invocation.supply.frame.callee.enclosing.compilerIdentity == pass.compilerIdentity
                    }
                )
                val alternatives =
                    steps
                        .flatMap { it.transfers }
                        .flatMap { it.target.callStack.frames }
                        .mapNotNull {
                            it.target as? io.github.amichne.kast.relation.contract.ValueCallTarget.Implementation
                        }
                assertTrue(
                    alternatives.any { target ->
                        target.dispatch.batch.facts.any { it.source.compilerIdentity == drop.compilerIdentity }
                    }
                )
            },
        )

    fun testGetterResultContributesItsOwnContent() = runBlocking {
        val fixture = fixture()
        val selected = fixture.markedExpression(declaration("getterResult"), "result")
        val domain =
            io.github.amichne.kast.relation.contract.RelationRequest.start(
                selector = selected.enclosing,
                meaning = io.github.amichne.kast.relation.contract.RelationMeaning.References,
                budget = fixture.budget,
                boundary = RelationSearchBoundary.RETAINED_SUBJECT,
            )
        val adapter = IntellijValueFlowCompilerAdapter()
        val resolved =
            adapter.resolve(
                project = project,
                current = fixture.lease,
                enclosing = selected.enclosing,
                anchor = selected.anchor,
                domain = domain,
                model = fixture.model,
            )
        assertTrue("Getter result must be an admitted expression: $resolved", resolved is ValueSiteResolution.Found)
        val steps = walk(fixture, adapter, (resolved as ValueSiteResolution.Found).site)
        capture(1, steps)
        assertTrue(steps.all { it.obligations.isEmpty() })
        assertEquals(1, steps.flatMap { it.transfers }.count { it.kind.name == "STRING_CONTENT" })
        val expected = fixture.markedExpression(declaration("getterResult"), "observer")
        assertTrue(
            steps
                .flatMap { it.transfers }
                .any { it.target.range == expected.anchor && it.target.role is ValueRole.Argument }
        )
    }
}
