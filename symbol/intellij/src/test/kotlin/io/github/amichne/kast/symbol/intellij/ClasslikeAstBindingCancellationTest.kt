package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withParserDefinition
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/** Actual cache owner and source/factory qualification; each case owns its initially empty metadata cache. */
class ClasslikeAstBindingCancellationTest {
    @TestFactory
    fun `binding cancellation preserves the original exception and retries uncached qualification`(
        @TempDir home: Path
    ): List<DynamicTest> =
        KotlinAstParserVariant.entries.map { variant ->
            DynamicTest.dynamicTest("${variant.name} cancellation retries qualification") {
                withParser(home.resolve(variant.name)) {
                    withParserDefinition(ClasslikeAstK2ParserFixture.definition(variant)) {
                        val owner = IntellijClasslikeAstBinding.Owner()
                        val cancellation = ProcessCanceledException()
                        var stopped = false
                        val observation =
                            onCounter(IntellijReadCounter.DECLARATION_AST_MAPPING_PSI_PROBES) { stopped = true }
                        val caught =
                            assertThrows(ProcessCanceledException::class.java) {
                                owner.acquire(
                                    {
                                        if (stopped) throw cancellation
                                        true
                                    },
                                    observation,
                                )
                            }
                        assertSame(cancellation, caught)
                        assertFreshRetryAndMemo(owner, variant)
                    }
                }
            }
        }

    @TestFactory
    fun `halted binding qualification does not memoize incomplete source evidence`(
        @TempDir home: Path
    ): List<DynamicTest> =
        KotlinAstParserVariant.entries.map { variant ->
            DynamicTest.dynamicTest("${variant.name} halt retries qualification") {
                withParser(home.resolve(variant.name)) {
                    withParserDefinition(ClasslikeAstK2ParserFixture.definition(variant)) {
                        val owner = IntellijClasslikeAstBinding.Owner()
                        var stopped = false
                        val observation =
                            onCounter(IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ) { stopped = true }
                        val outcome = owner.acquire({ !stopped }, observation)
                        assertSame(IntellijClasslikeAstBinding.Halted, outcome)
                        assertFreshRetryAndMemo(owner, variant)
                    }
                }
            }
        }

    private fun onCounter(expected: IntellijReadCounter, action: () -> Unit): IntellijReadObservation =
        object : IntellijReadObservation by IntellijReadObservation.None {
            override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
                if (counter == expected) action()
            }
        }

    private fun assertFreshRetryAndMemo(owner: IntellijClasslikeAstBinding.Owner, variant: KotlinAstParserVariant) {
        val retry = RecordingDiscoveryObservation { 0L }
        val result = owner.acquire({ true }, retry)
        assertTrue(result is IntellijClasslikeAstBinding.Supported)
        assertEquals(
            if (variant == KotlinAstParserVariant.BASE) 169_937 else 173_712,
            retry.counters[IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ],
        )
        assertEquals(116, retry.counters[IntellijReadCounter.DECLARATION_AST_MAPPING_PSI_PROBES])
        val memo = RecordingDiscoveryObservation { 0L }
        assertSame(result, owner.acquire({ true }, memo))
        assertEquals(0, memo.counters[IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ] ?: 0)
        assertEquals(0, memo.counters[IntellijReadCounter.DECLARATION_AST_MAPPING_PSI_PROBES] ?: 0)
    }
}
