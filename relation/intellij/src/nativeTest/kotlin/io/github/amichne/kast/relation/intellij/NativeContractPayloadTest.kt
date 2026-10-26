package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import kotlinx.coroutines.runBlocking

/** Equal callsInPlace contracts do not equate functions with different return dependencies. */
class NativeContractPayloadTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "contract-payload-jdk"

    fun testCallsInPlaceAndSourceReturnPreserveTheCallbackPayload() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = declaration("keptContractPayload")
        val steps = walk(fixture, seed(fixture, owner))
        assertTrue(
            "The source return did not preserve its payload",
            reached(steps, fixture.markedExpression(owner, "output")),
        )
    }

    fun testCallsInPlaceDoesNotInventAPayloadReturnFromAConstantResult() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = declaration("discardedContractPayload")
        val steps = walk(fixture, seed(fixture, owner))
        assertFalse(
            "The contract invented a payload dependency",
            reached(steps, fixture.markedExpression(owner, "output")),
        )
        assertTrue(
            "The actual callback invocation was not reached",
            steps.any { it.source.enclosing.name.value == "discardContractResult" },
        )
    }

    fun testSameNamedSourceFunctionKeepsItsActualCallbackInvocation() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = declaration("discardedLookalikePayload")
        val steps = walk(fixture, seed(fixture, owner))
        assertFalse(reached(steps, fixture.markedExpression(owner, "output")))
        assertTrue(
            "The source function named contract lost its real invocation",
            steps.any { it.source.enclosing.name.value == "contract" },
        )
    }

    private fun seed(fixture: NativePacketValueFixture, owner: PacketDeclaration): ValueSite = runBlocking {
        val request = fixture.selection(owner, "contractPayload", declaration("contractPayload"))
        val read =
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        assertTrue(
            "Contract callback producer was not admitted: $read; ${callbackEvidence(fixture, owner)}",
            read is ValueProducerSeedRead.Seeded,
        )
        (read as ValueProducerSeedRead.Seeded).seed.site
    }

    private fun walk(fixture: NativePacketValueFixture, origin: ValueSite): List<ValueFlowStep> = runBlocking {
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(16_000_000L).nativeRefined())
        val pending = ArrayDeque<ValueSite>()
        val visited = linkedSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        pending.addLast(origin)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            assertTrue("Contract payload fixture exceeded its finite site bound", visited.size <= MAX_SITES)
            val read =
                IntellijValueFlowCompilerAdapter()
                    .read(
                        project = project,
                        current = fixture.lease,
                        request = ValueFlowRequest(site, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                        model = fixture.model,
                    )
            assertTrue("Contract payload read failed: $read", read is ValueFlowRead.Observed)
            val step = (read as ValueFlowRead.Observed).step
            assertTrue("Contract payload retained obligations: ${step.obligations}", step.obligations.isEmpty())
            assertTrue(step.reviewedTransfers.isEmpty())
            steps += step
            step.transfers.forEach { pending.addLast(it.target) }
        }
        assertTrue(steps.isNotEmpty())
        steps
    }

    private fun reached(steps: List<ValueFlowStep>, expression: PacketExpression): Boolean = steps.any { step ->
        step.source.enclosing.compilerIdentity == expression.enclosing.compilerIdentity &&
            step.source.range == expression.anchor &&
            step.source.role == ValueRole.LocalRead
    }

    private fun declaration(name: String) = PacketDeclaration(CONTROLS, "", name)

    private suspend fun callbackEvidence(fixture: NativePacketValueFixture, owner: PacketDeclaration): String =
        com.intellij.openapi.application.readAction {
            val selector = fixture.callableDeclaration(owner)
            val request = RelationRequest.start(selector, RelationMeaning.References, fixture.budget)
            val adapter = IntellijValueFlowCompilerAdapter()
            val prepared = adapter.prepare(project, request, fixture.model)
            if (prepared !is IntellijValueFlowCompilerAdapter.Prepared.Ready) return@readAction prepared.toString()
            val literal =
                com.intellij.psi.util.PsiTreeUtil.findChildOfType(
                    prepared.owner,
                    org.jetbrains.kotlin.psi.KtLambdaExpression::class.java,
                ) ?: return@readAction "missing callback literal"
            val evidence =
                (prepared.projection.project(prepared.owner as com.intellij.psi.PsiNamedElement)
                        as IntellijRelationDeclarationProjection.Projected)
                    .evidence
            val collector = IntellijRelationCollector(request)
            val read =
                readCallbackInvocationFlow(
                    boundary = literal.functionLiteral,
                    lexicalOwner = evidence,
                    scope = prepared.scope,
                    projection = prepared.projection,
                    admitWork = collector::admitCallbackWork,
                    summaries = CallbackParameterSummaries(fixture.budget, adapter.observation),
                    callbackExpiry = collector::callbackExpiry,
                )
            when (read) {
                is CallbackInvocationFlowRead.Observed ->
                    "scan=${read.flow.scan}, obligations=${read.flow.obligations}, " +
                        "invocations=${read.flow.invocations.size}"
                is CallbackInvocationFlowRead.Immutable -> "immutable callback"
                is CallbackInvocationFlowRead.Unavailable -> "unavailable=${read.cause}"
                is CallbackInvocationFlowRead.ContractRejected -> "contract rejected"
            }
        }

    private companion object {
        const val MAX_SITES = 128
        const val CONTROLS = "src/main/kotlin/fixture/controls/ContractPayloadControls.kt"
    }
}
