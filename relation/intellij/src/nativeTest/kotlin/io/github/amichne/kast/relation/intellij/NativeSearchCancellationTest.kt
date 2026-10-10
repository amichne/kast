package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearch
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeSearchCancellationTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "cancellation-case-jdk"

    fun testCancellationDuringNativeMembershipDrainsBeforeFirstReferenceCallback() {
        val fixture = prepareReferenceFixture()
        assertPositiveControl(fixture)

        val indicator = EmptyProgressIndicator()
        val observed = CancelAtNativeMembership(indicator)
        val compiled = fixture.scope(observed)
        try {
            ProgressManager.getInstance()
                .runProcess(
                    {
                        observed.forEachReference(
                            fixture.target,
                            compiled.nativeScope,
                            nativeScopeTestAdmission(fixture.request, observed),
                        ) {
                            fail("Cancellation must drain before the first reference callback")
                            false
                        }
                    },
                    indicator,
                )
            fail("Expected native cancellation to escape the executed query")
        } catch (_: ProcessCanceledException) {
            assertTrue(indicator.isCanceled)
            assertEquals(1, observed.searches.get())
            assertEquals(1, observed.cancellationRequests.get())
            assertTrue(observed.memberships.get() > 0)
            assertEquals(0, observed.callbacks.get())
            assertEquals(0, observed.callbackCalls.get())
            assertEquals(NativeSearchState.CANCELLED, observed.state.get())
            assertEquals(1, observed.finished.get())
        }
    }
}

private enum class NativeSearchState {
    NOT_ENTERED,
    ACTIVE,
    RETURNED,
    CANCELLED,
    FAILED,
}

/** The fixture requests cancellation only. The native SDK owns the checkpoint that throws. */
private class CancelAtNativeMembership(private val indicator: EmptyProgressIndicator) : IntellijReadObservation {
    val searches = AtomicInteger()
    val memberships = AtomicInteger()
    val cancellationRequests = AtomicInteger()
    val callbacks = AtomicInteger()
    val callbackCalls = AtomicInteger()
    val finished = AtomicInteger()
    val state = AtomicReference(NativeSearchState.NOT_ENTERED)
    private val requested = AtomicBoolean()

    override fun enterSearch(search: IntellijReadSearch): IntellijReadSearchScope {
        check(search == IntellijReadSearch.REFERENCES)
        check(state.compareAndSet(NativeSearchState.NOT_ENTERED, NativeSearchState.ACTIVE))
        searches.incrementAndGet()
        return object : IntellijReadSearchScope {
            override fun callbackEntered() {
                callbacks.incrementAndGet()
            }

            override fun finish(outcome: IntellijReadCallOutcome) {
                val terminal =
                    when (outcome) {
                        IntellijReadCallOutcome.RETURNED -> NativeSearchState.RETURNED
                        IntellijReadCallOutcome.CANCELLED -> NativeSearchState.CANCELLED
                        IntellijReadCallOutcome.FAILED -> NativeSearchState.FAILED
                    }
                check(state.compareAndSet(NativeSearchState.ACTIVE, terminal))
                finished.incrementAndGet()
            }
        }
    }

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        if (call == IntellijReadCall.REFERENCE_CALLBACK) callbackCalls.incrementAndGet()
        if (call == IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP && state.get() == NativeSearchState.ACTIVE) {
            memberships.incrementAndGet()
            if (requested.compareAndSet(false, true)) {
                cancellationRequests.incrementAndGet()
                indicator.cancel()
            }
        }
        return IntellijReadCallScope.None
    }

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) = Unit

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
}
