package kast.identity.fixture

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

interface Feed<out T> {
    val state: StateFlow<T>
    val events: SharedFlow<T>
    fun accepts(value: @UnsafeVariance T): Boolean
}

class StringFeed(initial: String) : Feed<String> {
    private val mutableState = MutableStateFlow(initial)
    private val mutableEvents = MutableSharedFlow<String>()
    override val state: StateFlow<String> = mutableState.asStateFlow()
    override val events: SharedFlow<String> = mutableEvents.asSharedFlow()
    override fun accepts(value: String): Boolean = value == state.value
}

fun <T> genericRead(feed: Feed<T>): T = feed.state.value
fun stringRead(feed: Feed<String>): String = feed.state.value
fun starRead(feed: Feed<*>): Any? = feed.state.value
fun stringAccepts(feed: Feed<String>): Boolean = feed.accepts("value")
fun implementationRead(feed: StringFeed): String = feed.state.value

// This subtype inherits generic members without declaring real overrides.
interface StringFeedView : Feed<String>
fun inheritedRead(feed: StringFeedView): String = feed.state.value
fun inheritedAccepts(feed: StringFeedView): Boolean = feed.accepts("value")

// Delegation must remain distinguishable from an explicitly declared override.
class DelegatingFeed(delegate: Feed<String>) : Feed<String> by delegate
fun delegatedRead(feed: DelegatingFeed): String = feed.state.value

typealias TextFeed = Feed<String>
fun aliasRead(feed: TextFeed): String = feed.state.value

// Multiple declaration origins: selecting the first super-declaration is not a proof.
interface Left<T> { fun consume(value: T) }
interface Right { fun consume(value: String) }
interface Combined : Left<String>, Right
fun intersectionCall(value: Combined) = value.consume("value")

// Distinct overloads must remain distinct, even when names are identical.
class Overloads {
    fun select(value: String): String = value
    fun select(value: Int): String = value.toString()
}
fun chooseText(value: Overloads): String = value.select("value")
fun chooseNumber(value: Overloads): String = value.select(1)

// Different type-parameter binders must not be identified by the spelling "T".
class Outer<T> {
    fun <U> pair(first: T, second: U): Pair<T, U> = first to second
}
fun nestedBinders(value: Outer<String>): Pair<String, Int> = value.pair("value", 1)

// Stable Kotlin type constructs, independent of a text renderer.
fun <T> definitelyNotNull(value: T & Any): T & Any = value
fun <T : Comparable<T>> recursiveBound(value: T): T = value
suspend fun <T> suspendRead(block: suspend () -> T): T = block()

fun main() {
    val feed = StringFeed("value")
    check(genericRead(feed) == "value")
    check(stringRead(feed) == "value")
    check(starRead(feed) == "value")
    check(stringAccepts(feed))
    check(implementationRead(feed) == "value")
    check(delegatedRead(DelegatingFeed(feed)) == "value")
    check(aliasRead(feed) == "value")
    val overloads = Overloads()
    check(chooseText(overloads) == "value")
    check(chooseNumber(overloads) == "1")
    check(nestedBinders(Outer<String>()) == ("value" to 1))
    check(definitelyNotNull<String?>("value") == "value")
    check(recursiveBound("value") == "value")
    println("PASS: public fixture compilation and runtime checks")
}
