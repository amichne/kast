package io.github.amichne.kast.api.continuation

internal fun ServerHeldContinuationStore<TestToken, String, TestState, TestProjection>.issueToken(
    query: String,
    state: TestState,
): TestToken = when (val issued = issue(query, state)) {
    is ContinuationIssueResult.Issued -> issued.token
    is ContinuationIssueResult.Rejected -> error("Issue was rejected: ${issued.failure}")
}

internal data class TestState(val name: String) : ContinuationOwnedState()

internal data class TestProjection(val value: String?) : ContinuationProjection()

@JvmInline
internal value class TestToken(val value: Int)

internal class IncrementingTokenIssuer : ContinuationTokenIssuer<TestToken> {
    private var next = 0

    override fun issue(): TestToken = TestToken(next++)
}

internal class ScriptedTokenIssuer(vararg tokens: Int) : ContinuationTokenIssuer<TestToken> {
    private val tokens = tokens.iterator()

    override fun issue(): TestToken = TestToken(tokens.nextInt())
}

internal class FakeClock : ContinuationClock {
    private var nowNanos = 0L

    override fun nowNanos(): Long = nowNanos

    fun advanceNanos(nanoseconds: Long) {
        nowNanos = Math.addExact(nowNanos, nanoseconds)
    }
}
