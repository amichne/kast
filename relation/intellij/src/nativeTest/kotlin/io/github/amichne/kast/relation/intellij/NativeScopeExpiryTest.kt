package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearch
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real indexed SDK search; only the original allowance's clock is a controlled external observation. */
class NativeScopeExpiryTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "scope-expiry-case-jdk"

    fun testExcludedNativeScopeExpiryDrainsBeforeProviderDeliveryAndKeepsIncompleteCoverage() {
        val fixture = prepareReferenceFixture()
        assertPositiveControl(fixture)
        val narrowed = fixture.targetFileOnly()
        assertCurrentExcludedSearch(fixture, narrowed)

        val now = AtomicLong()
        val observed =
            ExpireAtNativeScopeMembership(now, narrowed.request.budget.resources.elapsedTimeLimit.value * 1_000_000L)
        val compiled = narrowed.scope(observed)
        val collector = IntellijRelationCollector(narrowed.request, clockNanoseconds = now::get, observation = observed)
        val cancellationCheck = { ProgressManager.checkCanceled() }
        val locators =
            IntellijRelationLocators(
                project = project,
                scope = compiled,
                projection =
                    IntellijK2RelationProjection(project, narrowed.request.subject.lease.workspaceRoot, observed),
                cancellationCheck = cancellationCheck,
                observation = observed,
            )
        val prepared =
            IntellijReferenceInventory(
                    scope = compiled,
                    locators = locators,
                    collector = collector,
                    cancellationCheck = cancellationCheck,
                    limits = ReadLimits.Default,
                    observation = observed,
                )
                .prepare(fixture.target)

        assertSame(RelationInventoryPreparation.Unavailable, prepared)
        assertEquals(1, observed.searches.get())
        assertEquals(1, observed.memberships.get())
        assertEquals(0, observed.callbacks.get())
        assertEquals(1, observed.halted.get())
        assertEquals(1, observed.finished.get())
        assertEquals(ScopeExpirySearchState.CANCELLED, observed.state.get())
        val result = collector.finish(IntellijRelationTermination.Terminal) as RelationCompilation.Qualified
        assertEquals(
            setOf(RelationLimitation.TIME_LIMIT_REACHED, RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE),
            result.coverage.limitations,
        )
        assertEquals(0L, result.batch.examinedWorkUnits.value)
        assertTrue(result.batch.facts.isEmpty())
    }

    private fun assertCurrentExcludedSearch(fixture: NativeReferenceFixture, narrowed: NativeReferenceFixture) {
        val control = narrowed.scope(IntellijReadObservation.None)
        assertEquals(
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
            control.admitProviderSite(fixture.callerFile),
        )
        assertTrue(
            IntellijReadObservation.None.forEachReference(
                fixture.target,
                control.nativeScope,
                nativeScopeTestAdmission(narrowed.request, IntellijReadObservation.None),
            ) {
                fail("The caller is outside this exact source domain")
                false
            }
        )
    }
}

private enum class ScopeExpirySearchState {
    NOT_ENTERED,
    ACTIVE,
    RETURNED,
    CANCELLED,
    FAILED,
}

private class ExpireAtNativeScopeMembership(private val now: AtomicLong, private val deadline: Long) :
    IntellijReadObservation {
    val searches = AtomicInteger()
    val memberships = AtomicInteger()
    val callbacks = AtomicInteger()
    val halted = AtomicInteger()
    val finished = AtomicInteger()
    val state = AtomicReference(ScopeExpirySearchState.NOT_ENTERED)

    override fun enterSearch(search: IntellijReadSearch): IntellijReadSearchScope {
        check(search == IntellijReadSearch.REFERENCES)
        check(state.compareAndSet(ScopeExpirySearchState.NOT_ENTERED, ScopeExpirySearchState.ACTIVE))
        searches.incrementAndGet()
        return object : IntellijReadSearchScope {
            override fun callbackEntered() {
                callbacks.incrementAndGet()
            }

            override fun finish(outcome: IntellijReadCallOutcome) {
                val terminal =
                    when (outcome) {
                        IntellijReadCallOutcome.RETURNED -> ScopeExpirySearchState.RETURNED
                        IntellijReadCallOutcome.CANCELLED -> ScopeExpirySearchState.CANCELLED
                        IntellijReadCallOutcome.FAILED -> ScopeExpirySearchState.FAILED
                    }
                check(state.compareAndSet(ScopeExpirySearchState.ACTIVE, terminal))
                finished.incrementAndGet()
            }
        }
    }

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        if (call == IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP && state.get() == ScopeExpirySearchState.ACTIVE) {
            memberships.incrementAndGet()
            now.set(deadline)
        }
        return IntellijReadCallScope.None
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        if (counter == IntellijReadCounter.RELATION_SCOPE_ADMISSIONS_HALTED) halted.addAndGet(amount)
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
}
