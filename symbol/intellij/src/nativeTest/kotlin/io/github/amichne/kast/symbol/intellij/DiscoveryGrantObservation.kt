package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearch
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** Counts actual native supply and scope calls. Hidden executor work remains outside these measurements. */
internal class DiscoveryGrantObservation : IntellijReadObservation {
    val calls = mutableMapOf<IntellijReadCall, Int>()
    val counters = mutableMapOf<IntellijReadCounter, Int>()
    val stops = mutableListOf<IntellijReadTermination>()
    val preCallbackProbes = mutableMapOf<IntellijReadSearch, Int>()
    val namedCallbacks = mutableMapOf<IntellijNamedDeclarationIndex, FirstNamedCallback>()
    private val stack = ArrayDeque<IntellijReadCall>()
    private var finished = 0

    override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
        calls[call] = (calls[call] ?: 0) + 1
        stack.addLast(call)
        return object : IntellijReadCallScope {
            override fun finish(outcome: IntellijReadCallOutcome) {
                check(stack.removeLast() == call)
                check(outcome == IntellijReadCallOutcome.RETURNED)
                finished++
            }
        }
    }

    override fun enterSearch(search: IntellijReadSearch): IntellijReadSearchScope {
        val scope = enterCall(search.call)
        val start = membershipCalls()
        var first = true
        return object : IntellijReadSearchScope {
            override fun callbackEntered() {
                if (first) {
                    preCallbackProbes[search] = membershipCalls() - start
                    first = false
                }
            }

            override fun finish(outcome: IntellijReadCallOutcome) = scope.finish(outcome)
        }
    }

    fun indexes(native: IntellijDeclarationIndexes): IntellijDeclarationIndexes =
        object : IntellijDeclarationIndexes by native {
            override fun named(
                index: IntellijNamedDeclarationIndex,
                name: String,
                scope: GlobalSearchScope,
                accept: (NavigationItem) -> Boolean,
            ): Boolean {
                val start = membershipCalls()
                namedCallbacks[index] = FirstNamedCallback.NoCallback
                return native.named(
                    index = index,
                    name = name,
                    scope = scope,
                    accept = { item ->
                        if (namedCallbacks[index] == FirstNamedCallback.NoCallback)
                            namedCallbacks[index] = FirstNamedCallback.Entered(membershipCalls() - start)
                        accept(item)
                    },
                )
            }
        }

    private fun membershipCalls(): Int = calls[IntellijReadCall.DISCOVERY_SCOPE_FILE_MEMBERSHIP] ?: 0

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        counters[counter] = (counters[counter] ?: 0) + amount
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        stops += reason
    }

    fun assertFinished() {
        check(stack.isEmpty())
        check(finished == calls.values.sum())
    }
}

internal sealed interface FirstNamedCallback {
    data object NoCallback : FirstNamedCallback

    data class Entered(val probesBeforeFirst: Int) : FirstNamedCallback
}
