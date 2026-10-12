package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscoveryPathOwnershipWorkTest {
    @Test
    fun `native ownership probes depend on path depth rather than unrelated roots`() {
        val file = Path.of("/workspace/module0/src/CacheManager.kt")
        val selected = Path.of("/workspace/module0/src")
        val failures = mutableListOf<String>()
        for (count in listOf(128, 1024, 4096)) {
            val ownership = (0 until count).map { Path.of("/workspace/module$it/src") }
            val policy = IntellijModelPathPolicy.SourceRoots(listOf(selected), ownership)
            for (sample in 0..5) {
                val observation = RecordingDiscoveryObservation { 0L }
                val start = System.nanoTime()
                repeat(2048) { assertTrue(policy.contains(file, observation)) }
                val elapsed = System.nanoTime() - start
                val probes = observation.counters[IntellijReadCounter.DISCOVERY_PATH_OWNERSHIP_PROBES] ?: 0
                println("nativeOwnership roots=$count sample=$sample fileAdmissionNs=$elapsed probes=$probes")
                if (probes > 2048 * (file.nameCount + 1)) failures += "roots=$count sample=$sample probes=$probes"
                assertTrue(probes > 0, "work must be observed rather than missing")
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `native deepest owner policy preserves tied roots exact paths and sibling collisions`() {
        val selected = listOf(Path.of("/workspace/src"), Path.of("/workspace/src/reincluded"))
        val ownership = selected + listOf(Path.of("/workspace/src/tests"), Path.of("/workspace/src/tests"))
        val policy = IntellijModelPathPolicy.SourceRoots(selected, ownership)
        val cases =
            mapOf(
                "/workspace/src" to true,
                "/workspace/src/CacheManager.kt" to true,
                "/workspace/src/tests" to false,
                "/workspace/src/tests/Test.kt" to false,
                "/workspace/src/reincluded/Main.kt" to true,
                "/workspace/src-other/Main.kt" to false,
                "/unknown/Main.kt" to false,
            )
        for ((raw, expected) in cases) {
            val path = Path.of(raw)
            val candidates = ownership.filter(path::startsWith)
            val depth = candidates.maxOfOrNull { it.nameCount }
            val reference = candidates.any { it.nameCount == depth && it in selected }
            assertEquals(expected, reference)
            assertEquals(expected, policy.contains(path), raw)
        }
    }
}
