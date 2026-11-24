package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning

/** Convention calls keep the same domain, ownership, and failure rules as call expressions. */
class NativeConventionBoundaryTest : NativeConventionFixture() {
    override val fixtureSdkName = "convention-boundary-jdk"

    fun testBinaryOperatorsStayOutsideTheSourceDomain() {
        val batch = conventionFixture(SOURCE).complete("useBuiltins", RelationMeaning.Callees)
        assertEquals(emptyList<String>(), batch.targets())
        assertEquals("Unnamed binary operators must not consume result capacity", 0, batch.scopeExclusions.size)
        assertEquals(0, batch.callableObservations.size)
    }

    fun testCompoundAssignmentCallsBothIndexedAccessors() {
        val fixture = conventionFixture(SOURCE)
        val callers = listOf("useCompound", "useIndexedIncrement")
        for (caller in callers) {
            val targets = fixture.complete(caller, RelationMeaning.Callees).targets()
            assertEquals(caller, setOf("get", "set"), targets.toSet())
        }
        assertEquals(callers.toSet(), fixture.complete("Money.get", RelationMeaning.Callers).sources().toSet())
        // Native search returns no compound indexed write, so incoming reads of the accessor stay qualified.
        assertTrue(fixture.relation("Money.set", RelationMeaning.Callers) is RelationCompilation.Qualified)
    }

    fun testDestructuringCallsOnlyNamedEntriesInTheEnclosingFunction() {
        val fixture = conventionFixture(SOURCE)
        assertEquals(
            setOf("iterator", "hasNext", "next", "component1"),
            fixture.complete("useLoop", RelationMeaning.Callees).targets().toSet(),
        )
        assertEquals(listOf("second"), fixture.complete("useIgnored", RelationMeaning.Callees).targets())
        assertEquals(
            listOf("useDestructure"),
            fixture.complete("Money.component1", RelationMeaning.Callers).sources(),
        )
    }

    /** Native search does not follow a source-declared iterator, so a loop element's operators stay qualified. */
    fun testOperatorsOnUnfollowedLoopElementsFailClosed() {
        val fixture = conventionFixture(SOURCE)
        for (meaning in listOf(RelationMeaning.Callers, RelationMeaning.References)) {
            for (subject in listOf("Coin.component1", "Coin.plus")) {
                val read = fixture.relation(subject, meaning)
                assertTrue("$subject $meaning must fail closed: $read", read is RelationCompilation.Qualified)
            }
            // An infix name is an ordinary word search, and the same iterator produces no Money.
            fixture.complete("Coin.join", meaning)
            fixture.complete("Money.plus", meaning)
        }
    }

    fun testOperatorInAStoredLambdaIsAnExcludedCallback() {
        val batch = conventionFixture(SOURCE).complete("Money.plus", RelationMeaning.Callers)
        assertEquals(listOf("useChained"), batch.sources())
        val callback = batch.callbackObservations.single()
        assertEquals(SOURCE.indexOf("+", SOURCE.indexOf("fun useStored")), callback.occurrence.range.startInclusive)
        assertTrue(callback.policy is CallbackNamedCallPolicy.Excluded)
    }

    fun testOperatorInsideACalleeExpressionClaimsNoProducerInvocation() {
        val batch = conventionFixture(SOURCE).complete("Money.plus", RelationMeaning.Callers)
        assertNull(batch.facts.single().producerRange)
    }

    fun testUnresolvedOperatorLeavesCalleesIncomplete() {
        val read = conventionFixture(SOURCE).relation("useBroken", RelationMeaning.Callees)
        assertTrue("An unresolved operator must fail closed, received $read", read is RelationCompilation.Qualified)
    }

    private fun RelationBatch.targets(): List<String> = facts.map { it.target.name.value }

    private fun RelationBatch.sources(): List<String> = facts.map { it.source.name.value }
}

private val SOURCE =
    """
    package consumer
    class Money(val cents: Int) {
        operator fun plus(other: Money) = this
        operator fun invoke(index: Int) = index
        operator fun get(index: Int) = index
        operator fun set(index: Int, value: Int) {}
        operator fun component1() = cents
    }
    class Coin(val cents: Int) {
        operator fun plus(other: Coin) = this
        operator fun component1() = cents
        infix fun join(other: Coin) = this
    }
    data class Entry(val first: Int, val second: Int)
    class Steps { operator fun hasNext() = false; operator fun next(): Coin = throw Throwable() }
    class Walk { operator fun iterator(): Steps = throw Throwable() }
    fun stored(block: () -> Money): () -> Money = block
    fun useBuiltins(a: Int, text: String, other: Any?, values: Array<Int>): Boolean {
        var total = 0
        for (step in 0..a) total += step
        total++
        return a + 1 > total && text + "x" != "y" && other == null && values[0] in 1..3
    }
    fun useCompound(a: Money) { a[0] += 1 }
    fun useIndexedIncrement(a: Money) { a[0]++ }
    fun useLoop(all: Walk) { for ((cents) in all) {} }
    fun useDestructure(a: Money): Int { val (cents) = a; return cents }
    fun useIgnored(entry: Entry): Int { val (_, second) = entry; return second }
    fun useStored(a: Money) = stored { a + a }
    fun useChained(a: Money) = (a + a)(1)
    fun useBroken(a: Money) = a - a
    """
        .trimIndent()
