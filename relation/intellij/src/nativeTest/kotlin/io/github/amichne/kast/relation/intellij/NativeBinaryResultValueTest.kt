package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.DeclaredCallbackResultEffect
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.idea.facet.getOrCreateConfiguredFacet

/** Original callback producers cross a binary-only declared result effect, never a decompiled body. */
class NativeBinaryResultValueTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "binary-result-value-jdk"

    fun testSelectedBinaryCallbackReachesOnlyItsOwnConsumer() {
        val fixture = fixture()
        val owner = declaration("binarySelected")
        val steps = trace(fixture, owner, IntellijReadTermination.VALUE_DECLARED_CALLBACK_RESULT_ADMITTED)
        assertTrue("Selected callback result was dropped", reached(steps, fixture.markedExpression(owner, "selected")))
        assertFalse("Distinct supplier calls were merged", reached(steps, fixture.markedExpression(owner, "sibling")))
        assertConditionalResult(
            fixture = fixture,
            owner = owner,
            steps = steps,
            invocation = CallbackDependencyInvocationKind.EXACTLY_ONCE,
            position = 0,
        )
    }

    fun testBinaryAlternativeReturnRetainsAConditionalDependency() {
        val fixture = fixture()
        val owner = declaration("binaryAlternative")
        val steps = trace(fixture, owner, IntellijReadTermination.VALUE_DECLARED_CALLBACK_RESULT_ADMITTED)
        assertTrue("Possible callback result was dropped", reached(steps, fixture.markedExpression(owner, "selected")))
        assertConditionalResult(
            fixture = fixture,
            owner = owner,
            steps = steps,
            invocation = CallbackDependencyInvocationKind.AT_MOST_ONCE,
            position = 2,
        )
    }

    fun testBinaryUnlistedParameterDoesNotAcquireAnotherParametersResultEffect() = assertUnsupported("binaryUnlisted")

    fun testCallsInPlaceAloneDoesNotInventABinaryResultDependency() = assertUnsupported("binaryDiscarded")

    private fun assertConditionalResult(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        steps: List<ValueFlowStep>,
        invocation: CallbackDependencyInvocationKind,
        position: Int,
    ) {
        val obligations = steps.flatMap { it.obligations }
        assertTrue("Supported result retained obligations: $obligations", obligations.isEmpty())
        val expected = fixture.markedExpression(owner, "selected")
        val site = steps.single { it.source.range == expected.anchor && it.source.role == ValueRole.LocalRead }.source
        val condition = site.conditions.facts.single() as ValueCondition.DeclaredCallbackResult
        assertEquals(DeclaredCallbackResultEffect.RETURNS_RESULT_OF, condition.binding.resultEffect)
        assertEquals(invocation, condition.binding.contract.invocationKind)
        assertEquals(position, condition.binding.contract.position.value)
        assertEquals(fixture.lease.identity, condition.binding.basis)
        assertEquals(expected.enclosing.compilerIdentity, condition.binding.enclosing.compilerIdentity)
        assertTrue(site.valuePath.segments.isEmpty())
        assertTrue(site.callStack.frames.isEmpty())
    }

    private fun assertUnsupported(name: String) {
        val fixture = fixture()
        val owner = declaration(name)
        val steps = trace(fixture, owner, IntellijReadTermination.VALUE_DECLARED_CALLBACK_RESULT_UNSUPPORTED)
        assertFalse("Unproven callback result escaped", reached(steps, fixture.markedExpression(owner, "selected")))
        assertEquals(
            setOf(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            steps.flatMap { it.obligations }.map { it.cause }.toSet(),
        )
        assertTrue("Callback return itself must be reached", steps.any { it.source.role == ValueRole.Return })
    }

    private fun fixture(): NativePacketValueFixture =
        packetFixture(
            listOf(CONTROLS),
            configureCompiler = {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    for (library in model.moduleLibraryTable.libraries) model.moduleLibraryTable.removeLibrary(library)
                    val roots = model.moduleLibraryTable.createLibrary("binary-result-effects").modifiableModel
                    for (name in listOf("result-effects.jar", "kotlin-stdlib.jar")) {
                        val path = Path.of(checkNotNull(javaClass.getResource("/contract-libraries/$name")).toURI())
                        roots.addRoot("jar://$path!/", OrderRootType.CLASSES)
                    }
                    assertTrue(roots.getUrls(OrderRootType.SOURCES).isEmpty())
                    roots.commit()
                }
                val models = ProjectDataManager.getInstance().createModifiableModelsProvider(project)
                try {
                    module.getOrCreateConfiguredFacet(models, useProjectSettings = false, commitModel = true) {
                        configuration.settings.compilerArguments =
                            K2JVMCompilerArguments().apply {
                                languageVersion = "2.4"
                                apiVersion = "2.4"
                                allowReturnsResultOf = true
                                returnValueChecker = "check"
                            }
                    }
                } finally {
                    models.dispose()
                }
            },
        )

    private fun trace(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        result: IntellijReadTermination,
    ): List<ValueFlowStep> = runBlocking {
        val observation = ValueReturnTestObservation()
        val adapter = IntellijValueFlowCompilerAdapter(observation = observation)
        val seed =
            adapter.seed(
                project = project,
                current = fixture.lease,
                request = fixture.selection(owner, "binaryPayload", declaration("binaryPayload")),
                model = fixture.model,
            )
        assertTrue(
            "Original binary callback producer rejected: $seed; ${observation.terminations}",
            seed is ValueProducerSeedRead.Seeded,
        )
        val pending = ArrayDeque<ValueSite>()
        pending.addLast((seed as ValueProducerSeedRead.Seeded).seed.site)
        val visited = linkedSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(16_000_000L).nativeRefined())
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            assertTrue("Binary result traversal exceeded its finite bound", visited.size <= MAX_SITES)
            val read =
                adapter.read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
            assertTrue("Binary result read failed at ${site.range}: $read", read is ValueFlowRead.Observed)
            val step = (read as ValueFlowRead.Observed).step
            assertTrue("Declared effect became a reviewed model", step.reviewedTransfers.isEmpty())
            steps += step
            step.transfers.forEach { pending.addLast(it.target) }
        }
        assertTrue(IntellijReadTermination.VALUE_DECLARED_CALLBACK_ADMITTED in observation.terminations)
        assertTrue(result in observation.terminations)
        assertEquals(
            observation.calls[IntellijReadCall.VALUE_DECLARED_CALLBACK_ADMISSION],
            observation.returns[IntellijReadCall.VALUE_DECLARED_CALLBACK_ADMISSION],
        )
        steps
    }

    private fun reached(steps: List<ValueFlowStep>, expression: PacketExpression): Boolean = steps.any { step ->
        step.source.enclosing.compilerIdentity == expression.enclosing.compilerIdentity &&
            step.source.range == expression.anchor &&
            step.source.role == ValueRole.LocalRead
    }

    private fun declaration(name: String) = PacketDeclaration(CONTROLS, "", name)

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/BinaryResultControls.kt"
        const val MAX_SITES = 64
    }
}
