@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.readAction
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Frozen source positions supply the oracle independently of the native transfers. */
internal class NativeReviewedCallbackReturnTest : ReviewedCallbackNativeTestBase() {
    fun testE0ReturnKeepsTheExactCallbackOwnerAndPayload() = runBlocking {
        val fixture = fixture("E0", "babe9037f525c8c3102305bd5d34ce4d5455faccb37f32a52c87d335302780d2")
        val steps = trace(fixture)
        assertPrefix(steps)
        val returned =
            steps
                .flatMap { it.transfers }
                .singleOrNull { edge ->
                    edge.kind == ValueTransferKind.SOURCE_CALL_RETURN &&
                        edge.target.range == exactRange(CALL_START, CALL_END)
                }
        assertNotNull(
            "Missing source return into [436, 457): ${steps.flatMap { it.obligations }}; ${observation.terminations}",
            returned,
        )
        val transfer = checkNotNull(returned)
        assertEquals(ValueTransferKind.SOURCE_CALL_RETURN, transfer.kind)
        assertEquals(transfer.source.valuePath, transfer.target.valuePath)
        assertTrue(transfer.target.executionOwner.context != ValueExecutionContext.Declaration)
        assertEquals(exactRange(BODY_START, BODY_END), transfer.target.executionOwner.range)
        assertTrue(observation.count(IntellijReadCounter.VALUE_RETURN_CALL_BINDINGS_ADMITTED) > 0)
        assertTrue(observation.count(IntellijReadCounter.VALUE_RETURN_OWNERS_ADMITTED) > 0)
        assertTrue(IntellijReadTermination.VALUE_REVIEWED_CALLBACK_ADMITTED in observation.terminations)
        assertEquals(observation.calls, observation.returns)
    }

    fun testE1KeepsTheCompleteFiveTransferPrefix() = runBlocking {
        val fixture = fixture("E1", "9a7ae785438f45cc6fd1580bedd6b2476dc8018735c8b39414be0bd04ffc08bb")
        val steps = trace(fixture)
        assertPrefix(steps)
        assertEquals(PREFIX_SIZE, steps.flatMap { it.transfers }.size)
        assertTrue("E1 must exhaust its source route", steps.flatMap { it.obligations }.isEmpty())
    }

    fun testE0CompletesThroughTheConvertReturnElementPayload() = runBlocking {
        val steps = trace(fixture("E0", "babe9037f525c8c3102305bd5d34ce4d5455faccb37f32a52c87d335302780d2"))
        assertTrue(
            "E0 must exhaust its exact source route: ${steps.flatMap { it.obligations }}",
            steps.flatMap { it.obligations }.isEmpty(),
        )
        val output =
            steps
                .single { step ->
                    step.source.enclosing.name.value == "convert" &&
                        step.source.role == ValueRole.Return &&
                        step.source.executionOwner.context == ValueExecutionContext.Declaration
                }
                .source
        assertEquals(2, output.valuePath.depth)
        assertEquals(ValuePathSegment.Element, output.valuePath.segments.first())
        assertEquals(
            "payload",
            (output.valuePath.segments.last() as ValuePathSegment.Field).field.declaration.name.value,
        )
    }

    fun testE2KeepsTheDirectEnvelopeValuePosition() = runBlocking {
        val steps = trace(fixture("E2", "6bc8d9364ea7b5d2701157086e4b42eb7647a705f0960732d628dce38a730724"))
        assertPrefix(steps)
        assertTrue(steps.flatMap { it.obligations }.isEmpty())
        assertTrue(steps.flatMap { it.reviewedTransfers }.isEmpty())
        val returned =
            steps.single { it.source.enclosing.name.value == "convertOne" && it.source.role == ValueRole.Return }.source
        assertEquals(
            listOf("payload"),
            returned.valuePath.segments.filterIsInstance<ValuePathSegment.Field>().map {
                it.field.declaration.name.value
            },
        )
        assertEquals(1, returned.valuePath.depth)
        assertEquals(DIRECT_ROUTE_SIZE, steps.sumOf { it.outputCount })
    }

    fun testE3KeepsTheCallbackValueAndIndependentWrapperRoute() = runBlocking {
        val steps = trace(fixture("E3", "1c712ee3b3fef49f5135a9717c20dd1bff886cb2b4e5dbd1b86a9e98e7e4872b"))
        assertTrue(steps.flatMap { it.obligations }.isEmpty())
        val output =
            steps
                .single { step ->
                    step.source.role == ValueRole.Return &&
                        step.source.enclosing.name.value == "convert" &&
                        step.source.executionOwner.context == ValueExecutionContext.Declaration
                }
                .source
        assertEquals(listOf(ValuePathSegment.Element), output.valuePath.segments)
        val wrapper =
            steps
                .single { it.source.role == ValueRole.Return && it.source.enclosing.name.value == "toClientEnvelope" }
                .source
        assertEquals(
            "payload",
            (wrapper.valuePath.segments.single() as ValuePathSegment.Field).field.declaration.name.value,
        )
    }

    fun testE4ExcludesTheUnrelatedConstantProducer() = runBlocking {
        val steps = trace(fixture("E4", "4d98aed3d4e9b6c46e1ce5f6cafc350d88d475cd85d2f1fbec3199a11a615035"))
        assertPrefix(steps)
        assertTrue(steps.flatMap { it.obligations }.isEmpty())
        assertEquals(PREFIX_SIZE, steps.sumOf { it.outputCount })
        assertTrue(steps.none { it.source.enclosing.name.value == "convert" })
        assertTrue(steps.flatMap { it.reviewedTransfers }.isEmpty())
    }

    fun testE5RejectsTheCallbackIgnoringSourceOverload() = runBlocking {
        val steps = trace(fixture("E5", "bcc02012a91a3e651e3d1dc35cd4e19b94abf11004b608679e9ac6ffed00c044"))
        assertPrefix(steps)
        assertTrue(steps.flatMap { it.reviewedTransfers }.isEmpty())
        assertTrue(steps.none { it.source.enclosing.name.value == "convert" })
        assertTrue(steps.flatMap { it.obligations }.any { it.cause == ValueFlowUnsupportedCause.NESTED_EXECUTION })
        assertTrue(observation.count(IntellijReadCounter.VALUE_RETURN_OWNERS_REJECTED) > 0)
        assertEquals(observation.calls, observation.returns)
    }

    fun testE6KeepsEmptyInputConditionalWithoutInvocationFacts() = runBlocking {
        val steps = trace(fixture("E6", "b4b969c5cf9dd73195bc13e7fa348eb62a74da0427b9ac4e7a35075333737223"))
        assertTrue(steps.flatMap { it.obligations }.isEmpty())
        val reviewed = steps.flatMap { it.reviewedTransfers }.single()
        assertTrue(reviewed.source.executionOwner.context is ValueExecutionContext.ReviewedCallback)
        assertEquals(ValuePathSegment.Element, reviewed.target.valuePath.segments.first())
        assertTrue(steps.none { it.source.executionOwner.context is ValueExecutionContext.Callback })
    }

    fun testCallBindingRejectsTheExternalMapBeforeOwnerAdmission() = runBlocking {
        val fixture = fixture("E0", "babe9037f525c8c3102305bd5d34ce4d5455faccb37f32a52c87d335302780d2")
        val seeded =
            IntellijValueFlowCompilerAdapter(observation)
                .seed(project = project, current = fixture.lease, request = fixture.seed, model = fixture.model)
        val source = (seeded as ValueProducerSeedRead.Seeded).seed.site
        readAction {
            val request =
                RelationRequest.start(
                    subject = source.enclosing,
                    meaning = RelationMeaning.References,
                    budget = fixture.seed.budget,
                    boundary = domain(),
                )
            val scope =
                (IntellijRelationScopeCompiler()
                        .compile(
                            project = project,
                            request = request,
                            modelCompilation = fixture.model,
                            observation = observation,
                        ) as IntellijRelationScopeCompilation.Compiled)
                    .scope
            val projection = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot, observation)
            val file = psiManager.findFile(fixture.file) as KtFile
            val host = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "convert" }
            val evidence = (projection.project(host) as IntellijRelationDeclarationProjection.Projected).evidence
            val enclosing = RelationEndpoint.resolve(fixture.lease, domain().scope, evidence).nativeRefined()
            val call =
                PsiTreeUtil.findChildrenOfType(host, KtCallExpression::class.java).single {
                    it.calleeExpression?.text == "map"
                }
            val result =
                NativeValueCallerFrames(source, scope, projection)
                    .initialInvocation(
                        call = call,
                        host = host,
                        enclosing = enclosing,
                        allowance = IntellijRelationAllowance(System.nanoTime()) { System.nanoTime() },
                    )
            assertEquals(Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL), result)
        }
        assertEquals(1, observation.count(IntellijReadCounter.VALUE_RETURN_CALL_BINDINGS_REJECTED))
        assertEquals(0, observation.calls[IntellijReadCall.VALUE_RETURN_OWNER_ADMISSION] ?: 0)
        assertEquals(observation.calls, observation.returns)
    }

    private fun assertPrefix(steps: List<ValueFlowStep>) {
        val prefix = steps.flatMap { it.transfers }.take(PREFIX_SIZE)
        assertEquals(
            listOf(
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueTransferKind.ARGUMENT,
                ValueTransferKind.CONSTRUCTOR_FIELD,
                ValueTransferKind.RETURN,
            ),
            prefix.map { it.kind },
        )
        val field = prefix.last().target.valuePath.segments.single() as ValuePathSegment.Field
        assertEquals("payload", field.field.declaration.name.value)
        assertEquals(
            prefix.single { it.kind == ValueTransferKind.CONSTRUCTOR_FIELD }.target.valuePath,
            prefix.last().target.valuePath,
        )
    }
}
