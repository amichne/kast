package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning

/** Kotlin conventions call functions without call expressions; a complete call relation must include them. */
class NativeConventionCallTest : NativeConventionFixture() {
    override val fixtureSdkName = "convention-call-jdk"

    fun testCallersIncludeEveryConventionCallThatNativeSearchFinds() {
        val fixture = conventionFixture(CONVENTIONS)
        val actual = EXPECTED_CALLERS.keys.associateWith { fixture.rows(it, RelationMeaning.Callers) }
        assertEquals(EXPECTED_CALLERS.mapValues { (_, callers) -> Rows.Complete(callers) }, actual)
    }

    fun testCalleesIncludeEveryConventionCall() {
        val fixture = conventionFixture(CONVENTIONS)
        val actual = EXPECTED_CALLEES.keys.associateWith { fixture.rows(it, RelationMeaning.Callees) }
        assertEquals(EXPECTED_CALLEES.mapValues { (_, callees) -> Rows.Complete(callees) }, actual)
    }

    fun testReferencesResolveConventionSitesWithSeveralTargets() {
        val fixture = conventionFixture(CONVENTIONS)
        val expected = mapOf("Money.component1" to setOf("useDestructure"), "Walk.iterator" to setOf("useFor"))
        val actual = expected.keys.associateWith { fixture.rows(it, RelationMeaning.References) }
        assertEquals(expected.mapValues { (_, owners) -> Rows.Complete(owners) }, actual)
    }

    /** Native search cannot enumerate these implicit uses, so an empty result is not proof of absence. */
    fun testImplicitUsesOutsideNativeSearchFailClosed() {
        val fixture = conventionFixture(CONVENTIONS)
        val meanings = listOf(RelationMeaning.Callers, RelationMeaning.References)
        val actual =
            UNSEARCHABLE.flatMap { callee -> meanings.map { (callee to it) to fixture.rows(callee, it) } }.toMap()
        assertEquals(actual.mapValues { Rows.Incomplete }, actual)
    }

    private sealed interface Rows {
        data class Complete(val names: Set<String>) : Rows

        data object Incomplete : Rows
    }

    private fun NativeReferenceFixture.rows(name: String, meaning: RelationMeaning): Rows =
        when (val read = relation(name, meaning)) {
            is RelationCompilation.Complete ->
                Rows.Complete(
                    read.batch.facts
                        .map { if (meaning == RelationMeaning.Callees) it.target.name.value else it.source.name.value }
                        .toSet()
                )
            is RelationCompilation.Qualified -> Rows.Incomplete
            is RelationCompilation.Rejected -> error("Convention read for $name returned ${read.reason}")
        }
}

private val EXPECTED_CALLERS: Map<String, Set<String>> =
    mapOf(
        "Money.combine" to setOf("useInfix"),
        "Money.plus" to setOf("usePlus"),
        "Money.times" to setOf("useTimesAssign"),
        "Ledger.plusAssign" to setOf("usePlusAssign"),
        "Money.unaryMinus" to setOf("useUnary"),
        "Money.inc" to setOf("useInc"),
        "Money.get" to setOf("useGet"),
        "Money.contains" to setOf("useIn", "useNotIn"),
        "Money.compareTo" to setOf("useCompare"),
        "Money.rangeTo" to setOf("useRange"),
        "Money.component1" to setOf("useDestructure"),
        "Money.invoke" to setOf("useInvoke"),
        "Walk.iterator" to setOf("useFor"),
        "Money.named" to setOf("useNamed"),
    )

private val EXPECTED_CALLEES: Map<String, Set<String>> =
    mapOf(
        "useInfix" to setOf("combine"),
        "usePlus" to setOf("plus"),
        "useTimesAssign" to setOf("times"),
        "usePlusAssign" to setOf("plusAssign"),
        "useUnary" to setOf("unaryMinus"),
        "useInc" to setOf("inc"),
        "useGet" to setOf("get"),
        "useSet" to setOf("set"),
        "useIn" to setOf("contains"),
        "useNotIn" to setOf("contains"),
        "useCompare" to setOf("compareTo"),
        "useRange" to setOf("rangeTo"),
        "useDestructure" to setOf("component1"),
        "useInvoke" to setOf("invoke"),
        "useFor" to setOf("iterator", "hasNext", "next"),
        "useDelegate" to setOf("Delegate", "getValue", "setValue"),
        "useProvider" to setOf("Provider", "provideDelegate", "getValue", "setValue"),
        "useNamed" to setOf("named"),
        // Short-circuit, identity, elvis, cast, and assignment operators call no function.
        "useNonCalls" to emptySet(),
    )

private val UNSEARCHABLE = listOf("Steps.next", "Steps.hasNext", "Delegate.getValue", "Delegate.setValue", "Money.set")

private val CONVENTIONS =
    """
    package consumer
    class Money(val cents: Int) {
        infix fun combine(other: Money) = other
        operator fun plus(other: Money) = other
        operator fun times(factor: Int) = this
        operator fun unaryMinus() = this
        operator fun inc() = this
        operator fun get(index: Int) = index
        operator fun set(index: Int, value: Int) {}
        operator fun contains(index: Int) = true
        operator fun compareTo(other: Money) = 0
        operator fun rangeTo(other: Money) = other
        operator fun component1() = cents
        operator fun invoke(index: Int) = index
        fun named() = cents
    }
    class Ledger { operator fun plusAssign(money: Money) {} }
    class Steps { operator fun hasNext() = false; operator fun next() = 1 }
    class Walk { operator fun iterator(): Steps = throw Throwable() }
    class Delegate {
        operator fun getValue(owner: Any?, property: Any?) = 1
        operator fun setValue(owner: Any?, property: Any?, value: Int) {}
    }
    class Provider { operator fun provideDelegate(owner: Any?, property: Any?): Delegate = throw Throwable() }
    fun useInfix(a: Money, b: Money) = a combine b
    fun usePlus(a: Money, b: Money) = a + b
    fun useTimesAssign(a: Money): Money { var c = a; c *= 2; return c }
    fun usePlusAssign(a: Ledger, b: Money) { a += b }
    fun useUnary(a: Money) = -a
    fun useInc(a: Money): Money { var c = a; c++; return c }
    fun useGet(a: Money) = a[0]
    fun useSet(a: Money) { a[0] = 1 }
    fun useIn(a: Money) = 1 in a
    fun useNotIn(a: Money) = when (1) { !in a -> true; else -> false }
    fun useCompare(a: Money, b: Money) = a < b
    fun useRange(a: Money, b: Money) = a..b
    fun useDestructure(a: Money): Int { val (c) = a; return c }
    fun useInvoke(a: Money) = a(1)
    fun useFor(a: Walk) { for (step in a) {} }
    fun useDelegate(): Int { var x by Delegate(); x = 2; return x }
    fun useProvider(): Int { var x by Provider(); x = 2; return x }
    fun useNamed(a: Money) = a.named()
    fun useNonCalls(a: Any?, b: Boolean): Any? {
        var c = a
        c = (a as? Money) ?: a
        return if (b && a === c || a is Money) c else a!!
    }
    """
        .trimIndent()
