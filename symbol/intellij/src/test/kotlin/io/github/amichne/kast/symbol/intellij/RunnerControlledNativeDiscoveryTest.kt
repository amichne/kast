package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RunnerControlledNativeDiscoveryTest {
    @Test
    fun `runner capture exhausts native occurrences beyond bounded candidate and work limits`() {
        val limits = ReadLimits.resolve(environment = mapOf("KAST_READ_DISCOVERY_CANDIDATES" to "1")).nativeFixture()
        val element =
            Proxy.newProxyInstance(
                PsiElement::class.java.classLoader,
                arrayOf(PsiElement::class.java),
            ) { _, _, _ ->
                error("Capture must not inspect PSI")
            } as PsiElement
        val bounded = WorkAllowance.Bounded(WorkUnitLimit.parse(1).nativeFixture())
        for ((allowance, expected, expectedQualifications) in
            listOf(
                Triple(bounded, listOf(0), setOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED)),
                Triple(WorkAllowance.Unlimited, listOf(0, 1, 2), emptySet()),
            )) {
            val projected = mutableListOf<Int>()
            val qualifications = mutableSetOf<SymbolDiscoveryQualification>()
            collectTextDiscoveryOccurrences(
                workLimit = allowance,
                observe = { true },
                qualify = qualifications::add,
                process = { accept -> (0..2).all { accept(element, it) } },
                project = { _, offset -> projected.add(offset) },
                limits = limits,
            )
            assertEquals(expected, projected)
            assertEquals(expectedQualifications, qualifications)
        }
    }
}

private fun <Value> Refinement<Value, *>.nativeFixture(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid native capture fixture")
    }
