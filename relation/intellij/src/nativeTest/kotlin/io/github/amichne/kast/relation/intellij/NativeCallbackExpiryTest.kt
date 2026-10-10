package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
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

class NativeCallbackExpiryTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "expiry-case-jdk"

    fun testExpiredNativeCallbackRejectsInventoryBeforeSiteInspection() {
        val fixture = prepareReferenceFixture()
        assertPositiveControl(fixture)
        val request = fixture.request

        val now = AtomicLong()
        val observed = ExpireAtNativeCallback(now, request.budget.resources.elapsedTimeLimit.value * NANOS_PER_MILLI)
        val compiled = fixture.scope(observed)
        val collector = IntellijRelationCollector(request, clockNanoseconds = now::get, observation = observed)
        val checkCancellation = { ProgressManager.checkCanceled() }
        val locators =
            IntellijRelationLocators(
                project = project,
                scope = compiled,
                projection = IntellijK2RelationProjection(project, request.subject.lease.workspaceRoot, observed),
                cancellationCheck = checkCancellation,
                observation = observed,
            )
        val prepared =
            IntellijReferenceInventory(
                    scope = compiled,
                    locators = locators,
                    collector = collector,
                    cancellationCheck = checkCancellation,
                    limits = ReadLimits.Default,
                    observation = observed,
                )
                .prepare(fixture.target)

        assertEquals(RelationInventoryPreparation.Unavailable, prepared)
        assertEquals(1, observed.searches.get())
        assertEquals(1, observed.callbacks.get())
        assertEquals(0, observed.scopeChecksAfterCallback.get())
        assertEquals(0, observed.candidates.get())
        assertEquals(0, observed.partitions.get())
        assertEquals(1, observed.returned.get())
        val result =
            collector.finish(IntellijRelationTermination.Resumable(emptySet())) as RelationCompilation.Qualified
        assertEquals(
            setOf(RelationLimitation.TIME_LIMIT_REACHED, RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE),
            result.coverage.limitations,
        )
        assertEquals(0L, result.batch.examinedWorkUnits.value)
        assertEquals(0, result.batch.facts.size)
    }

    fun testExcludedNativeCallbackExpiresBeforeProductionSiteSupplier() {
        val fixture = prepareReferenceFixture()
        assertPositiveControl(fixture)
        val narrowed = fixture.targetFileOnly()
        val scope = narrowed.scope(IntellijReadObservation.None)
        assertEquals(RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED, scope.admitProviderSite(fixture.callerFile))
        val now = AtomicLong()
        val allowance = IntellijRelationAllowance(now::get)
        val context =
            IntellijCallbackFlowContext(
                scope = scope,
                projection = IntellijK2RelationProjection(project, narrowed.request.subject.lease.workspaceRoot),
                admitWork = { error("Provider-site admission must not debit eligible work") },
                observation = IntellijReadObservation.None,
                callbackExpiry = {
                    if (allowance.elapsedLimitReached(narrowed.request.budget.resources))
                        CallbackExpiryAdmission.EXPIRED
                    else CallbackExpiryAdmission.CURRENT
                },
            )
        // Deliberately broader SDK input exercises the production rule's excluded-candidate boundary.
        val nativeScope = GlobalSearchScope.allScope(project)
        assertCurrentExcludedCallback(fixture, nativeScope, context)
        val observed =
            ExpireAtNativeCallback(now, narrowed.request.budget.resources.elapsedTimeLimit.value * NANOS_PER_MILLI)
        val siteReads = AtomicInteger()
        assertFalse(
            observed.forEachReference(
                fixture.target,
                nativeScope,
                nativeScopeTestAdmission(fixture.request, observed),
            ) { reference ->
                assertEquals(
                    Refinement.Rejected(CallbackInvocationFlowCause.TIME_LIMIT_REACHED),
                    context.providerSite {
                        siteReads.incrementAndGet()
                        reference.element.containingFile.virtualFile
                    },
                )
                // The independent callback oracle is checked after the production rejection.
                assertExcludedReferenceOracle(fixture, reference)
                false
            }
        )
        assertEquals(1, observed.searches.get())
        assertEquals(1, observed.callbacks.get())
        assertEquals(1, observed.returned.get())
        assertEquals(0, siteReads.get())
    }

    private fun assertCurrentExcludedCallback(
        fixture: NativeReferenceFixture,
        nativeScope: GlobalSearchScope,
        context: IntellijCallbackFlowContext,
    ) {
        val controls = AtomicInteger()
        assertTrue(
            IntellijReadObservation.None.forEachReference(
                fixture.target,
                nativeScope,
                nativeScopeTestAdmission(fixture.request, IntellijReadObservation.None),
            ) { reference ->
                assertExcludedReferenceOracle(fixture, reference)
                assertEquals(
                    Refinement.Refined(RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED),
                    context.providerSite { reference.element.containingFile.virtualFile },
                )
                controls.incrementAndGet()
                true
            }
        )
        assertEquals(1, controls.get())
    }

    private fun assertExcludedReferenceOracle(fixture: NativeReferenceFixture, reference: PsiReference) {
        assertEquals(fixture.callerFile.path, reference.element.containingFile.virtualFile.path)
        assertEquals(CALLER_OFFSET, reference.element.textRange.startOffset)
        assertSame(fixture.target, reference.resolve())
    }

    private companion object {
        const val CALLER_OFFSET = 38
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Time is an injected observation. Production inventory owns admission and completeness. */
private class ExpireAtNativeCallback(private val now: AtomicLong, private val deadline: Long) :
    IntellijReadObservation {
    val searches = AtomicInteger()
    val callbacks = AtomicInteger()
    val scopeChecksAfterCallback = AtomicInteger()
    val candidates = AtomicInteger()
    val partitions = AtomicInteger()
    val returned = AtomicInteger()

    override fun enterSearch(search: IntellijReadSearch): IntellijReadSearchScope {
        check(search == IntellijReadSearch.REFERENCES)
        searches.incrementAndGet()
        return object : IntellijReadSearchScope {
            override fun callbackEntered() {
                callbacks.incrementAndGet()
                now.set(deadline)
            }

            override fun finish(outcome: IntellijReadCallOutcome) {
                check(outcome == IntellijReadCallOutcome.RETURNED)
                returned.incrementAndGet()
            }
        }
    }

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        if (call == IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP && callbacks.get() > 0) {
            scopeChecksAfterCallback.incrementAndGet()
        }
        return IntellijReadCallScope.None
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        when (counter) {
            IntellijReadCounter.RELATION_CANDIDATES -> candidates.addAndGet(amount)
            IntellijReadCounter.RELATION_PARTITIONS_PREPARED -> partitions.addAndGet(amount)
            else -> Unit
        }
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
}
