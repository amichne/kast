package io.github.amichne.kast.cli.broker.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class InstalledColdBrokerAcceptanceEntryPointTest {
    @Test
    fun `installed acceptance exposes the JVM executable entry point`() {
        val entry = InstalledColdBrokerAcceptance::class.java.getMethod("main", Array<String>::class.java)
        assertTrue(Modifier.isStatic(entry.modifiers))
        assertEquals(Void.TYPE, entry.returnType, "JavaExec requires public static void main(String[])")
    }
}
