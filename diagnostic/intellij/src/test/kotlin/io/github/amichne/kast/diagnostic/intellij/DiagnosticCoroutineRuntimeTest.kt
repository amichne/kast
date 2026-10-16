package io.github.amichne.kast.diagnostic.intellij

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagnosticCoroutineRuntimeTest {
    @Test
    fun `unit test classpath has one coroutine implementation and resolves compiled calls`() {
        val runtimes = javaClass.classLoader.getResources("kotlinx/coroutines/BuildersKt.class").toList()
        assertEquals(1, runtimes.size, runtimes.joinToString())
        assertEquals(42, runBlocking { 42 })
    }
}
