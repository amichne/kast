@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.relation.contract.LocalBindingReadRemainder
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueLocalVersion
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty

/** Original producer calls, not fabricated bindings, establish each local payload's permitted read set. */
class NativeLocalValueVersionTest : NativeLocalValueVersionFixtureTest() {
    override val fixtureSdkName = "local-value-version-jdk"

    fun testStableMutableLocalIfRefinementReachesExactConsumer() = assertStableRefinement("stableIfLocalPayload")

    fun testStableMutableLocalWhenRefinementReachesExactConsumer() = assertStableRefinement("stableWhenLocalPayload")

    fun testStableMutableLocalShortCircuitReachesExactConsumer() =
        assertStableRefinement("stableShortCircuitLocalPayload")

    fun testStableMutableLocalGuardClauseReachesExactConsumer() = assertStableRefinement("stableGuardLocalPayload")

    fun testStableMutableLocalInNestedBlockReachesExactConsumer() = assertStableRefinement("stableNestedLocalPayload")

    fun testCompilerStableLocalRefinementDoesNotRequireAFinalInitializerType() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner("stableUnknownLocalPayload")
        val selected = fixture.markedExpression(owner, "selected")
        assertCompilerStableRefinement(selected)
        val source =
            binding(
                fixture,
                seeded(fixture, owner, "unknownLocalPayload"),
                initialBinding(fixture, owner, "unknownLocalPayload").anchor,
            )
        val reads = step(fixture, source)
        assertTrue("Stable compiler fact was dropped: ${reads.obligations}", reads.obligations.isEmpty())
        assertEquals(
            setOf(fixture.markedExpression(owner, "guard").anchor, selected.anchor),
            reads.transfers.map { it.target.range }.toSet(),
        )
        val refined = reads.transfers.single { it.target.range == selected.anchor }.target
        val fact = refined.conditions.facts.single() as ValueCondition.StableLocal
        assertEquals(source.localVersion, fact.version)
        assertTrue(fact.origin.matches(source))
        assertEquals(selected.anchor, fact.useRange)
        assertEquals("kotlin.Any", fact.originalType.value)
        assertEquals("fixture.controls.LocalVersionPayload", fact.refinedType.value)
        assertTrue(reads.transfers.single { it.target.range != selected.anchor }.target.conditions.facts.isEmpty())
        assertRefinedConsumer(fixture, refined, selected)
        val resources = fixture.budget.resources as ResourceBudget.Bounded
        val budget = fixture.budget.copy(resources = resources.copy(resultLimit = ResultLimit.parse(1).nativeRefined()))
        val first = readPage(fixture = fixture, source = source, budget = budget, remainder = null)
        assertTrue("Two reads must exercise pagination", first is ValueFlowRead.Suspended)
        first as ValueFlowRead.Suspended
        val second = readPage(fixture = fixture, source = source, budget = budget, remainder = first.remainder)
        val pages =
            when (second) {
                is ValueFlowRead.Observed -> listOf(first.step, second.step)
                is ValueFlowRead.Suspended -> {
                    val last =
                        readPage(fixture = fixture, source = source, budget = budget, remainder = second.remainder)
                    assertTrue("Read enumeration did not exhaust", last is ValueFlowRead.Observed)
                    listOf(first.step, second.step, (last as ValueFlowRead.Observed).step)
                }
                else -> error("Condition-bearing resume rejected: $second")
            }
        assertTrue(pages.all { it.obligations.isEmpty() })
        assertEquals(reads.transfers.toSet(), pages.flatMap { it.transfers }.toSet())
        assertEquals(reads.transfers.size, pages.sumOf { it.transfers.size })
    }

    fun testStraightLineReassignmentBetweenStableIfAndWhenKeepsVersionsSeparate() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner("reassignedRefinedLocalPayload")
        val before = fixture.markedExpression(owner, "before-selected")
        val after = fixture.markedExpression(owner, "after-selected")
        assertCompilerStableRefinement(before)
        assertCompilerStableRefinement(after)
        val original =
            binding(fixture, seeded(fixture, owner, "originalLocalPayload"), initialBinding(fixture, owner).anchor)
        val oldReads = step(fixture, original)
        assertNoArrival(oldReads, after)
        assertNoArrival(oldReads, fixture.markedExpression(owner, "after-guard"))
        assertNoArrival(oldReads, fixture.markedExpression(owner, "replacement-binding"))
        val originalRead =
            assertReads(oldReads, listOf(fixture.markedExpression(owner, "before-guard"), before)).single {
                it.range == before.anchor
            }
        assertRefinedConsumer(fixture, originalRead, before)
        val replacement =
            binding(
                fixture,
                seeded(fixture, owner, "replacementLocalPayload"),
                fixture.markedExpression(owner, "replacement-binding").anchor,
            )
        assertTrue(original.localVersion is ValueLocalVersion.Defined)
        assertTrue(replacement.localVersion is ValueLocalVersion.Defined)
        assertFalse("Direct write reused the previous definition", original.identity == replacement.identity)
        val newReads = step(fixture, replacement)
        assertNoArrival(newReads, before)
        assertNoArrival(newReads, fixture.markedExpression(owner, "before-guard"))
        val replacementRead =
            assertReads(newReads, listOf(fixture.markedExpression(owner, "after-guard"), after)).single {
                it.range == after.anchor
            }
        assertRefinedConsumer(fixture, replacementRead, after)
    }

    fun testOriginalProducerReachesOnlyTheReadBeforeReassignment() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner("reassignedLocalPayload")
        val origin = seeded(fixture, owner, "originalLocalPayload")
        val binding = binding(fixture, origin, initialBinding(fixture, owner).anchor)
        val reads = step(fixture, binding)
        assertNoArrival(reads, fixture.markedExpression(owner, "after"))
        assertNoArrival(reads, fixture.markedExpression(owner, "replacement-binding"))
        assertReads(reads, listOf(fixture.markedExpression(owner, "before")))
    }

    fun testReplacementProducerReachesOnlyTheReadAfterReassignment() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner("reassignedLocalPayload")
        val origin = seeded(fixture, owner, "replacementLocalPayload")
        val binding = binding(fixture, origin, fixture.markedExpression(owner, "replacement-binding").anchor)
        val reads = step(fixture, binding)
        assertNoArrival(reads, fixture.markedExpression(owner, "before"))
        assertNoArrival(reads, initialBinding(fixture, owner))
        assertReads(reads, listOf(fixture.markedExpression(owner, "after")))
    }

    fun testSelfAssignmentReadsTheOldVersionAndCreatesANewVersionForLaterUse() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner("selfAssignedLocalPayload")
        val origin = seeded(fixture, owner, "originalLocalPayload")
        val initial = binding(fixture, origin, initialBinding(fixture, owner).anchor)
        val before = fixture.markedExpression(owner, "before")
        val rhs = fixture.markedExpression(owner, "rhs")
        val write = fixture.markedExpression(owner, "write")
        val after = fixture.markedExpression(owner, "after")
        val oldReads = step(fixture, initial)
        assertNoArrival(oldReads, write)
        assertNoArrival(oldReads, after)
        val rhsRead = assertReads(oldReads, listOf(before, rhs)).single { it.range == rhs.anchor }
        val assignment =
            ExactDeclarationTextRange.parse(write.anchor.startInclusive, rhs.anchor.endExclusive).nativeRefined()
        val replacement = binding(fixture, rhsRead, assignment)
        assertFalse("Self-assignment must retain a distinct definition", initial.identity == replacement.identity)
        val newReads = step(fixture, replacement)
        assertNoArrival(newReads, before)
        assertNoArrival(newReads, rhs)
        assertNoArrival(newReads, write)
        assertReads(newReads, listOf(after))
    }

    fun testConditionalMutationDoesNotInventAnUnqualifiedPostBranchRead() {
        assertUnsupportedMutation(
            "conditionallyAssignedLocalPayload",
            setOf(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW),
        )
    }

    fun testCapturedMutationDoesNotInventAnUnqualifiedPostInvocationRead() {
        assertUnsupportedMutation(
            "capturedLocalPayload",
            setOf(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW, ValueFlowUnsupportedCause.NESTED_EXECUTION),
        )
    }

    fun testCompoundMutationDoesNotReuseThePreviousDefinition() {
        assertUnsupportedMutation("compoundAssignedLocalPayload", setOf(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW))
    }

    fun testParenthesizedIncrementDoesNotReuseThePreviousDefinition() {
        assertUnsupportedMutation("incrementedLocalPayload", setOf(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW))
    }

    fun testRestoredInitializerAndReassignmentRetainTheExactVersion() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner("reassignedLocalPayload")
        for ((callee, position) in
            listOf(
                "originalLocalPayload" to initialBinding(fixture, owner),
                "replacementLocalPayload" to fixture.markedExpression(owner, "replacement-binding"),
            )) {
            val arrived = binding(fixture, seeded(fixture, owner, callee), position.anchor)
            val restored = revalidate(fixture, position, ValueSiteRoleClaim.LocalBinding)
            assertTrue("Exact definition did not restore: $restored", restored is ValueModelSiteRead.Revalidated)
            val site = (restored as ValueModelSiteRead.Revalidated).position.site
            assertTrue(site.localVersion is ValueLocalVersion.Defined)
            assertEquals(arrived.identity, site.identity)
            assertEquals(step(fixture, arrived).transfers, step(fixture, site).transfers)
        }
    }

    fun testRestorationRejectsWriteAsReadAndConditionalBinding() {
        val fixture = packetFixture(listOf(CONTROLS))
        val write = fixture.markedExpression(owner("selfAssignedLocalPayload"), "write")
        assertEquals(
            ValueModelSiteRead.Unsupported(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW),
            revalidate(fixture, write, ValueSiteRoleClaim.LocalRead),
        )
        val conditional = initialBinding(fixture, owner("conditionallyAssignedLocalPayload"))
        assertEquals(
            ValueModelSiteRead.Unsupported(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW),
            revalidate(fixture, conditional, ValueSiteRoleClaim.LocalBinding),
        )
    }

    fun testRestoredReadsOnBothSidesOfAssignmentAreCompilerAdmittedReads() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner("reassignedLocalPayload")
        for (marker in listOf("before", "after")) {
            val position = fixture.markedExpression(owner, marker)
            val read = revalidate(fixture, position, ValueSiteRoleClaim.LocalRead)
            assertTrue("Read did not restore: $read", read is ValueModelSiteRead.Revalidated)
            assertEquals(position.anchor, (read as ValueModelSiteRead.Revalidated).position.site.range)
        }
    }

    fun testPagedReadsRetainOldVersionAndDoNotLeakPastSelfAssignment() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner("selfAssignedLocalPayload")
        val source =
            binding(fixture, seeded(fixture, owner, "originalLocalPayload"), initialBinding(fixture, owner).anchor)
        val resources = fixture.budget.resources as ResourceBudget.Bounded
        val budget = fixture.budget.copy(resources = resources.copy(resultLimit = ResultLimit.parse(1).nativeRefined()))
        val targets = mutableListOf<ValueSite>()
        var remainder: LocalBindingReadRemainder? = null
        var exhausted = false
        var suspensions = 0
        for (page in 1..MAX_PAGES) {
            if (exhausted) break
            val read = readPage(fixture = fixture, source = source, budget = budget, remainder = remainder)
            val step =
                when (read) {
                    is ValueFlowRead.Suspended -> {
                        assertEquals(source.identity, read.remainder.source.identity)
                        remainder = read.remainder
                        suspensions++
                        read.step
                    }
                    is ValueFlowRead.Observed -> {
                        exhausted = true
                        read.step
                    }
                    else -> error("Paged native read rejected on page $page instead of advancing: $read")
                }
            assertEquals(source, step.source)
            assertTrue("Paged version lost its proof: ${step.obligations}", step.obligations.isEmpty())
            targets += step.transfers.map { it.target }
        }
        assertTrue("Fixture did not exercise suspension", suspensions > 0)
        assertTrue("Version scan did not exhaust within its finite page bound", exhausted)
        assertEquals(
            listOf("before", "rhs").map { fixture.markedExpression(owner, it).anchor }.toSet(),
            targets.map { it.range }.toSet(),
        )
        assertEquals(2, targets.size)
    }
}

abstract class NativeLocalValueVersionFixtureTest : NativePacketValueFixtureTest() {
    abstract override val fixtureSdkName: String

    protected fun assertStableRefinement(name: String) {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner(name)
        val selected = fixture.markedExpression(owner, "selected")
        assertCompilerStableRefinement(selected)
        val source =
            binding(fixture, seeded(fixture, owner, "originalLocalPayload"), initialBinding(fixture, owner).anchor)
        val reads = step(fixture, source)
        assertNoArrival(reads, fixture.markedExpression(owner, "unrelated"))
        val selectedRead =
            assertReads(reads, listOf(fixture.markedExpression(owner, "guard"), selected)).single {
                it.range == selected.anchor
            }
        assertTrue("Stable mutable binding lost its exact definition", source.localVersion is ValueLocalVersion.Defined)
        assertRefinedConsumer(fixture, selectedRead, selected)
    }

    protected fun assertCompilerStableRefinement(position: PacketExpression) {
        ReadAction.run<RuntimeException> {
            val virtual =
                checkNotNull(LocalFileSystem.getInstance().findFileByPath(position.enclosing.file.stableValue))
            val file = checkNotNull(PsiManager.getInstance(project).findFile(virtual))
            val leaf = checkNotNull(file.findElementAt(position.anchor.startInclusive))
            val reference =
                checkNotNull(PsiTreeUtil.getParentOfType(leaf, KtNameReferenceExpression::class.java, false))
            assertEquals(position.anchor, range(reference))
            IntellijReadObservation.None.observedAnalyze(reference) {
                val smartcast =
                    checkNotNull(reference.smartCastInfo) { "Fixture has no compiler smartcast at ${position.anchor}" }
                assertTrue("Fixture smartcast is not compiler-stable", smartcast.isStable)
                assertEquals("kotlin.Any", (smartcast.originalType as KaClassType).classId.asSingleFqName().asString())
                assertEquals(
                    "fixture.controls.LocalVersionPayload",
                    (smartcast.smartCastType as KaClassType).classId.asSingleFqName().asString(),
                )
            }
        }
    }

    protected fun assertRefinedConsumer(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        expected: PacketExpression,
    ) {
        val read = step(fixture, source)
        assertSupported(read)
        assertEquals(listOf(ValueTransferKind.ARGUMENT), read.transfers.map { it.kind })
        val target = read.transfers.single().target
        assertEquals(expected.anchor, target.range)
        assertStatePreserved(source, target)
        val argument =
            target.role as? ValueRole.Argument ?: error("Refined local did not reach an argument: ${target.role}")
        assertEquals(0, argument.position.value)
        assertEquals(
            fixture.callableDeclaration(owner("consumeLocalPayload")).compilerIdentity,
            argument.call.callable.compilerIdentity,
        )
    }

    protected fun readPage(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        budget: RelationBudget,
        remainder: LocalBindingReadRemainder?,
    ): ValueFlowRead = runBlocking {
        IntellijValueFlowCompilerAdapter()
            .read(
                project = project,
                current = fixture.lease,
                request =
                    ValueFlowRequest(
                        source = source,
                        budget = budget,
                        boundary = RelationSearchBoundary.RETAINED_SUBJECT,
                        remainder = remainder,
                    ),
                model = fixture.model,
            )
    }

    protected fun revalidate(fixture: NativePacketValueFixture, position: PacketExpression, role: ValueSiteRoleClaim) =
        runBlocking {
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(
                    project = project,
                    current = fixture.lease,
                    request =
                        ValueSiteRevalidationRequest.create(
                                enclosing = position.enclosing,
                                anchor = position.anchor,
                                role = role,
                                budget = fixture.budget,
                            )
                            .nativeRefined(),
                    model = fixture.model,
                )
        }

    protected fun assertUnsupportedMutation(name: String, permittedCauses: Set<ValueFlowUnsupportedCause>) {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = owner(name)
        val origin = seeded(fixture, owner, "originalLocalPayload")
        val binding = binding(fixture, origin, initialBinding(fixture, owner).anchor)
        val read = step(fixture, binding)
        assertNoArrival(read, fixture.markedExpression(owner, "after"))
        assertNoArrival(read, fixture.markedExpression(owner, "write"))
        assertTrue(
            "Unsupported mutation invented another payload transfer: ${read.transfers}",
            read.transfers.isEmpty(),
        )
        assertTrue("Unsupported mutation must not invent a reviewed transfer", read.reviewedTransfers.isEmpty())
        assertEquals(ValueFlowTerminal.Unresolved, read.terminal)
        assertTrue("Unsupported mutation lost its finite obligation", read.obligations.isNotEmpty())
        read.obligations.forEach { obligation ->
            assertEquals(binding, obligation.site)
            assertTrue(
                "Unexpected rejection instead of mutation evidence: $obligation",
                obligation.cause in permittedCauses,
            )
        }
    }

    protected fun seeded(fixture: NativePacketValueFixture, enclosing: PacketDeclaration, callee: String): ValueSite {
        val request = fixture.selection(enclosing, callee, owner(callee))
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue(
            "Original producer must be admitted before checking local flow: $read",
            read is ValueProducerSeedRead.Seeded,
        )
        val site = (read as ValueProducerSeedRead.Seeded).seed.site
        assertEquals(request.anchor, site.range)
        assertEquals(request.enclosing.compilerIdentity, site.enclosing.compilerIdentity)
        assertEquals(ValueRole.ExpressionResult, site.role)
        return site
    }

    protected fun binding(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        expectedRange: ExactDeclarationTextRange,
    ): ValueSite {
        val read = step(fixture, source)
        assertEquals(
            "Missing local definition at ${source.range}: ${read.obligations}",
            listOf(ValueTransferKind.LOCAL_BINDING),
            read.transfers.map { it.kind },
        )
        assertSupported(read)
        val transfer = read.transfers.single()
        assertEquals(source, transfer.source)
        assertEquals(expectedRange, transfer.target.range)
        assertStatePreserved(source, transfer.target)
        return transfer.target
    }

    protected fun assertReads(read: ValueFlowStep, expected: List<PacketExpression>): List<ValueSite> {
        assertEquals(
            "Local read set is incomplete: ${read.obligations}",
            expected.size,
            read.transfers.size,
        )
        assertSupported(read)
        assertTrue(
            "Binding emitted a non-read transfer",
            read.transfers.all { it.kind == ValueTransferKind.LOCAL_READ },
        )
        val targets = read.transfers.map { it.target }
        assertEquals(expected.map { it.anchor }.toSet(), targets.map { it.range }.toSet())
        targets.forEach { target ->
            assertEquals(ValueRole.LocalRead, target.role)
            assertEquals(
                expected.single { it.anchor == target.range }.enclosing.compilerIdentity,
                target.enclosing.compilerIdentity,
            )
            assertStatePreserved(read.source, target)
        }
        return targets
    }

    protected fun assertNoArrival(read: ValueFlowStep, forbidden: PacketExpression) {
        assertTrue(
            "Forbidden local value arrival at ${forbidden.anchor}",
            read.transfers.none { transfer ->
                transfer.target.range == forbidden.anchor &&
                    transfer.target.enclosing.compilerIdentity == forbidden.enclosing.compilerIdentity
            },
        )
    }

    private fun assertStatePreserved(source: ValueSite, target: ValueSite) {
        assertEquals(source.basis, target.basis)
        assertEquals(source.enclosing, target.enclosing)
        assertEquals(source.executionOwner, target.executionOwner)
        assertEquals(source.valuePath, target.valuePath)
        assertEquals(source.callStack, target.callStack)
        assertEquals(source.conditions, target.conditions)
    }

    private fun assertSupported(read: ValueFlowStep) {
        assertTrue("Unexpected local flow obligations: ${read.obligations}", read.obligations.isEmpty())
        assertTrue("Local definitions must not use reviewed transfers", read.reviewedTransfers.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, read.terminal)
    }

    protected fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue(
            "Native read must execute, not reject upstream at ${source.range}: $read",
            read is ValueFlowRead.Observed,
        )
        val step = (read as ValueFlowRead.Observed).step
        assertEquals(source, step.source)
        return step
    }

    protected fun initialBinding(
        fixture: NativePacketValueFixture,
        enclosing: PacketDeclaration,
        producer: String = "originalLocalPayload",
    ): PacketExpression {
        val seed = fixture.selection(enclosing, producer, owner(producer))
        return ReadAction.compute<PacketExpression, RuntimeException> {
            val virtual = checkNotNull(LocalFileSystem.getInstance().findFileByPath(seed.enclosing.file.stableValue))
            val file = checkNotNull(PsiManager.getInstance(project).findFile(virtual))
            val leaf = checkNotNull(file.findElementAt(seed.anchor.startInclusive))
            val property = checkNotNull(PsiTreeUtil.getParentOfType(leaf, KtProperty::class.java))
            assertEquals("value", property.name)
            assertTrue(property.isLocal && property.isVar)
            assertEquals(seed.anchor, range(checkNotNull(property.initializer)))
            PacketExpression(seed.enclosing, range(property))
        }
    }

    protected fun owner(name: String) = PacketDeclaration(CONTROLS, "", name)

    protected companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/LocalValueVersionControls.kt"
        const val MAX_PAGES = 8
    }
}
