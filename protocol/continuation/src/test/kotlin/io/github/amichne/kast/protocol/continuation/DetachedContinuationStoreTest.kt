package io.github.amichne.kast.protocol.continuation

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class DetachedContinuationStoreTest {
    @Test
    fun `copied tokens reproduce deterministic single use pages on one generation`() {
        val issuer = IncrementingTokenIssuer()
        val store = store(tokenIssuer = issuer)
        val binding = binding()
        val records = records("alpha", "beta", "gamma")
        val offered = records.toMutableList()
        val issued = store.issue(binding, offered).issued()
        offered.clear()
        val copied = ContinuationToken.parse(issued.value).refined()

        val first = store.resume(copied, binding, pageBudget(records = 2)).resumed()
            as ContinuationPage.More
        assertEquals(0L, first.segment.position.value)
        assertEquals(records.take(2), first.segment.records)
        assertEquals(9L, first.segment.encodedBytes.value)
        assertEquals(
            ContinuationAccessFailure.UNKNOWN_TOKEN, store.resume(
            copied,
            binding,
            pageBudget(records = 2),
        ).rejected()
        )

        val second = store.resume(
            first.nextToken,
            binding,
            pageBudget(records = 2),
        ).resumed() as ContinuationPage.Complete
        assertEquals(2L, second.segment.position.value)
        assertEquals(records.drop(2), second.segment.records)

        val replay = store.resume(
            store.issue(binding, records).issued(),
            binding,
            pageBudget(records = 2),
        ).resumed() as ContinuationPage.More
        assertEquals(first.segment, replay.segment)
    }

    @Test
    fun `every binding mismatch is distinct and invalidates the token`() {
        val baseline = binding()
        val mismatches = listOf(
            binding(root = Path.of("/other")) to ContinuationAccessFailure.WRONG_WORKSPACE_ROOT,
            binding(generation = 22L) to ContinuationAccessFailure.GENERATION_CHANGED,
            binding(request = "request-2") to ContinuationAccessFailure.NORMALIZED_REQUEST_CHANGED,
            binding(scope = "scope-2") to ContinuationAccessFailure.SCOPE_CHANGED,
            binding(order = "descending") to ContinuationAccessFailure.ORDER_CHANGED,
            binding(owner = "agent-2") to ContinuationAccessFailure.RESOURCE_OWNER_CHANGED,
        )

        mismatches.forEach { (mismatch, expected) ->
            val store = store()
            val token = store.issue(baseline, records("one")).issued()
            assertEquals(expected, store.resume(token, mismatch, pageBudget()).rejected())
            assertEquals(
                ContinuationAccessFailure.UNKNOWN_TOKEN,
                store.resume(token, baseline, pageBudget()).rejected(),
            )
        }
    }

    @Test
    fun `token record and byte capacity reject before publication and release after cancellation`() {
        val binding = binding()
        val oneToken = store(tokenLimit = 1)
        val held = oneToken.issue(binding, records("held")).issued()
        assertEquals(
            ContinuationIssueFailure.TOKEN_LIMIT_REACHED,
            oneToken.issue(binding, records("other")).rejected(),
        )
        assertEquals(
            ContinuationAccessFailure.CANCELLED,
            oneToken.resume(
                held,
                binding,
                pageBudget(),
            ) { ContinuationCancellationStatus.CANCELLED }.rejected(),
        )
        oneToken.issue(binding, records("replacement")).issued()

        assertEquals(
            ContinuationIssueFailure.RECORD_LIMIT_REACHED,
            store(recordLimit = 1).issue(binding, records("one", "two")).rejected(),
        )
        assertEquals(
            ContinuationIssueFailure.BYTE_LIMIT_REACHED,
            store(byteLimit = 3L).issue(binding, records("four")).rejected(),
        )
        assertEquals(
            ContinuationIssueFailure.EMPTY_STATE,
            store().issue(binding, emptyList()).rejected(),
        )

        val aggregateRecords = store(recordLimit = 2)
        aggregateRecords.issue(binding, records("one")).issued()
        assertEquals(
            ContinuationIssueFailure.RECORD_LIMIT_REACHED,
            aggregateRecords.issue(binding, records("two", "three")).rejected(),
        )
        val aggregateBytes = store(byteLimit = 5L)
        aggregateBytes.issue(binding, records("one")).issued()
        assertEquals(
            ContinuationIssueFailure.BYTE_LIMIT_REACHED,
            aggregateBytes.issue(binding, records("two")).rejected(),
        )
    }

    @Test
    fun `mid page cancellation and no progress page budgets destroy owned state`() {
        val binding = binding()
        val store = store(tokenLimit = 1)
        val token = store.issue(binding, records("one", "two")).issued()
        var checks = 0
        val cancelled = store.resume(
            token,
            binding,
            pageBudget(records = 2),
        ) {
            checks += 1
            if (checks < 3) {
                ContinuationCancellationStatus.CONTINUE
            } else {
                ContinuationCancellationStatus.CANCELLED
            }
        }
        assertEquals(ContinuationAccessFailure.CANCELLED, cancelled.rejected())
        store.issue(binding, records("replacement")).issued()

        val tooSmallStore = store()
        val tooLarge = tooSmallStore.issue(binding, records("large")).issued()
        assertEquals(
            ContinuationAccessFailure.PAGE_BYTE_LIMIT_TOO_SMALL,
            tooSmallStore.resume(tooLarge, binding, pageBudget(bytes = 2L)).rejected(),
        )
        assertEquals(
            ContinuationAccessFailure.UNKNOWN_TOKEN,
            tooSmallStore.resume(tooLarge, binding, pageBudget()).rejected(),
        )
    }

    @Test
    fun `absolute ttl does not renew when a page reissues`() {
        val clock = MutableClock()
        val store = store(ttlMillis = 10L, clock = clock)
        val binding = binding()
        val token = store.issue(binding, records("one", "two")).issued()

        clock.nowNanos = 5_000_000L
        val next = (
            store.resume(token, binding, pageBudget(records = 1)).resumed()
                as ContinuationPage.More
                   ).nextToken
        clock.nowNanos = 10_000_000L
        assertEquals(
            ContinuationAccessFailure.EXPIRED,
            store.resume(next, binding, pageBudget(records = 1)).rejected(),
        )
    }

    @Test
    fun `token collision is closed and does not corrupt the published owner`() {
        val token = token(1)
        val store = store(tokenLimit = 2, tokenIssuer = ContinuationTokenIssuer { token })
        val binding = binding()
        val published = store.issue(binding, records("one")).issued()
        assertEquals(
            ContinuationIssueFailure.TOKEN_COLLISION,
            store.issue(binding, records("two")).rejected(),
        )
        assertTrue(
            store.resume(published, binding, pageBudget()).resumed()
                is ContinuationPage.Complete,
        )

        val reissueStore = store(
            tokenLimit = 2,
            tokenIssuer = ContinuationTokenIssuer { token(2) },
        )
        val reissueToken = reissueStore.issue(binding, records("one", "two")).issued()
        assertEquals(
            ContinuationAccessFailure.TOKEN_COLLISION,
            reissueStore.resume(
                reissueToken,
                binding,
                pageBudget(records = 1),
            ).rejected(),
        )
        assertEquals(
            ContinuationAccessFailure.UNKNOWN_TOKEN,
            reissueStore.resume(reissueToken, binding, pageBudget()).rejected(),
        )
    }

    @Test
    fun `issuer failure invalidation and close release without leaking state`() {
        val binding = binding()
        val failingIssue = store(
            tokenIssuer = ContinuationTokenIssuer { error("issuer failed") },
        )
        assertEquals(
            ContinuationIssueFailure.TOKEN_ISSUER_FAILURE,
            failingIssue.issue(binding, records("one")).rejected(),
        )

        var publications = 0
        val failingReissue = store(
            tokenIssuer = ContinuationTokenIssuer {
                publications += 1
                if (publications == 1) token(3) else error("issuer failed")
            },
        )
        val token = failingReissue.issue(binding, records("one", "two")).issued()
        assertEquals(
            ContinuationAccessFailure.TOKEN_ISSUER_FAILURE,
            failingReissue.resume(token, binding, pageBudget(records = 1)).rejected(),
        )
        assertEquals(
            ContinuationAccessFailure.UNKNOWN_TOKEN,
            failingReissue.resume(token, binding, pageBudget()).rejected(),
        )

        val invalidated = store(tokenLimit = 1)
        val invalidatedToken = invalidated.issue(binding, records("one")).issued()
        assertEquals(
            ContinuationInvalidationResult.Invalidated,
            invalidated.invalidate(invalidatedToken),
        )
        invalidated.issue(binding, records("replacement")).issued()
        invalidated.close()
        assertEquals(
            ContinuationIssueFailure.STORE_CLOSED,
            invalidated.issue(binding, records("closed")).rejected(),
        )
    }

    @Test
    fun `concurrent resume grants exactly one single use page`() {
        val store = store()
        val binding = binding()
        val token = store.issue(binding, records("one")).issued()
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                executor.submit<ContinuationResumeResult> {
                    gate.await()
                    store.resume(token, binding, pageBudget())
                }
            }
            gate.countDown()
            val results = attempts.map { it.get() }
            assertEquals(1, results.count { it is ContinuationResumeResult.Resumed })
            assertEquals(
                listOf(ContinuationAccessFailure.UNKNOWN_TOKEN),
                results.filterIsInstance<ContinuationResumeResult.Rejected>()
                    .map(ContinuationResumeResult.Rejected::failure),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `boundary parsers reject invalid limits and malformed copied tokens`() {
        assertEquals(ContinuationTokenFailure.MALFORMED, ContinuationToken.parse("bad").rejected())
        assertEquals(
            ContinuationTokenFailure.NON_CANONICAL,
            ContinuationToken.parse("00000000-0000-0000-0000-00000000000A").rejected(),
        )
        assertEquals(
            ContinuationPositiveLimitFailure.NOT_POSITIVE,
            ContinuationTokenLimit.parse(0).rejected(),
        )
        assertEquals(
            ContinuationTtlFailure.TOO_LARGE,
            ContinuationTtlMillis.parse(Long.MAX_VALUE).rejected(),
        )
    }

    private fun store(
        tokenLimit: Int = 8,
        recordLimit: Int = 32,
        byteLimit: Long = 4_096L,
        ttlMillis: Long = 1_000L,
        tokenIssuer: ContinuationTokenIssuer = IncrementingTokenIssuer(),
        clock: ContinuationClock = ContinuationClock { 0L },
    ): DetachedContinuationStore = DetachedContinuationStore(
        limits = ContinuationStoreLimits(
            tokens = ContinuationTokenLimit.parse(tokenLimit).refined(),
            cachedRecords = ContinuationRecordLimit.parse(recordLimit).refined(),
            cachedBytes = ContinuationByteLimit.parse(byteLimit).refined(),
            ttl = ContinuationTtlMillis.parse(ttlMillis).refined(),
        ),
        tokenIssuer = tokenIssuer,
        clock = clock,
    )

    private fun binding(
        root: Path = Path.of("/workspace"),
        generation: Long = 21L,
        request: String = "request-1",
        scope: String = "workspace-production",
        order: String = "qualified-name-ascending",
        owner: String = "agent-1",
    ): ContinuationBinding = ContinuationBinding(
        lease = SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(root).refined(),
            EvidenceGeneration.parse(generation).refined(),
        ),
        normalizedRequest = ContinuationRequestFingerprint.fromCanonical(request),
        scope = ContinuationScopeFingerprint.fromCanonical(scope),
        order = ContinuationOrderFingerprint.fromCanonical(order),
        owner = ContinuationResourceOwner.fromCanonical(owner),
    )

    private fun pageBudget(
        records: Int = 10,
        bytes: Long = 1_024L,
    ): ContinuationPageBudget = ContinuationPageBudget(
        records = ContinuationPageRecordLimit.parse(records).refined(),
        bytes = ContinuationPageByteLimit.parse(bytes).refined(),
    )

    private fun records(vararg values: String): List<DetachedContinuationRecord> =
        values.map(DetachedContinuationRecord::fromCanonical)

    private fun token(value: Int): ContinuationToken = ContinuationToken.parse(
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}",
    ).refined()

    private inner class IncrementingTokenIssuer : ContinuationTokenIssuer {
        private var next = 1

        override fun issue(): ContinuationToken = token(next++)
    }

    private class MutableClock : ContinuationClock {
        var nowNanos: Long = 0L

        override fun nowNanos(): Long = nowNanos
    }

    private fun ContinuationIssueResult.issued(): ContinuationToken =
        (this as ContinuationIssueResult.Issued).token

    private fun ContinuationIssueResult.rejected(): ContinuationIssueFailure =
        (this as ContinuationIssueResult.Rejected).failure

    private fun ContinuationResumeResult.resumed(): ContinuationPage =
        (this as ContinuationResumeResult.Resumed).page

    private fun ContinuationResumeResult.rejected(): ContinuationAccessFailure =
        (this as ContinuationResumeResult.Rejected).failure

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error(value.toString())
        is Refinement.Rejected -> failure
    }
}
