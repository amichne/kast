package io.github.amichne.kast.diagnostic.intellij

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class DiagnosticCoroutineRuntimeTest {
    @Test
    fun `unit test classpath has one coroutine implementation and resolves compiled calls`() = runTest {
        val runtimes = javaClass.classLoader.getResources("kotlinx/coroutines/BuildersKt.class").toList()
        assertEquals(1, runtimes.size, runtimes.joinToString())
        assertNotNull(
            javaClass.classLoader.getResource("kotlinx/coroutines/internal/intellij/IntellijCoroutines.class")
        )
        assertEquals(42, async { 42 }.await())
    }
}
