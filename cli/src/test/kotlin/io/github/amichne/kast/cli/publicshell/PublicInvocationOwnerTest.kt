package io.github.amichne.kast.cli.publicshell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class PublicInvocationOwnerTest {
    @Test
    fun `defect during publication cannot become success or publish a diagnostic record`() {
        val owner = PublicInvocationOwner()
        val output =
            object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    super.write(bytes, offset, length)
                    owner.publishDefect({ fail<PublicPreparedOutput>("Second document requested") }, this)
                }
            }
        owner.publish(PublicPreparedOutput("record", PublicShellExit.COMPLETE), output)
        assertEquals(PublicShellExit.DEFECT, owner.exit)
        assertEquals("record\n", output.toString(Charsets.UTF_8))
    }

    @Test
    fun `observed defect wins before its error document is prepared`() {
        val owner = PublicInvocationOwner()
        val output = ByteArrayOutputStream()
        owner.publishDefect(
            {
                assertFalse(owner.cancel(PublicShellExit.TERMINATED))
                PublicPreparedOutput("defect", PublicShellExit.DEFECT)
            },
            output,
        )
        assertEquals(PublicShellExit.DEFECT, owner.exit)
        assertEquals("defect\n", output.toString(Charsets.UTF_8))
    }

    @Test
    fun `signal before failed flush wins and later events cannot replace it`() {
        val owner = PublicInvocationOwner()
        val output =
            object : ByteArrayOutputStream() {
                override fun flush() {
                    assertTrue(owner.cancel(PublicShellExit.INTERRUPTED))
                    throw IOException("closed consumer")
                }
            }
        owner.publish(PublicPreparedOutput("record", PublicShellExit.COMPLETE), output)
        assertEquals(PublicShellExit.INTERRUPTED, owner.exit)
        assertFalse(owner.outputFailed())
        owner.publish(PublicPreparedOutput("second", PublicShellExit.COMPLETE), output)
        assertEquals("record\n", output.toString(Charsets.UTF_8))
    }

    @Test
    fun `failed flush before signal wins`() {
        val owner = PublicInvocationOwner()
        val output =
            object : ByteArrayOutputStream() {
                override fun flush() {
                    throw IOException("closed consumer")
                }
            }
        owner.publish(PublicPreparedOutput("record", PublicShellExit.COMPLETE), output)
        assertEquals(PublicShellExit.IO, owner.exit)
        assertFalse(owner.cancel(PublicShellExit.TERMINATED))
    }

    @Test
    fun `completed flush before signal keeps published status`() {
        val owner = PublicInvocationOwner()
        val output = ByteArrayOutputStream()
        owner.publish(PublicPreparedOutput("record", PublicShellExit.SEMANTIC), output)
        assertFalse(owner.cancel(PublicShellExit.INTERRUPTED))
        assertEquals(PublicShellExit.SEMANTIC, owner.exit)
        assertEquals("record\n", output.toString(Charsets.UTF_8))
    }

    @Test
    fun `signal before response prevents all writes`() {
        val owner = PublicInvocationOwner()
        assertTrue(owner.cancel(PublicShellExit.TERMINATED))
        owner.publish(
            PublicPreparedOutput("record", PublicShellExit.COMPLETE),
            object : OutputStream() {
                override fun write(value: Int) {
                    fail<Unit>("No write after cancellation")
                }
            },
        )
        assertEquals(PublicShellExit.TERMINATED, owner.exit)
    }

    @Test
    fun `unexpected writer defect cannot emit a second document`() {
        val owner = PublicInvocationOwner()
        val output =
            object : ByteArrayOutputStream() {
                override fun flush() {
                    error("fixture defect")
                }
            }
        owner.publish(PublicPreparedOutput("record", PublicShellExit.COMPLETE), output)
        owner.publish(PublicPreparedOutput("second", PublicShellExit.COMPLETE), output)
        assertEquals(PublicShellExit.DEFECT, owner.exit)
        assertEquals("record\n", output.toString(Charsets.UTF_8))
    }
}
