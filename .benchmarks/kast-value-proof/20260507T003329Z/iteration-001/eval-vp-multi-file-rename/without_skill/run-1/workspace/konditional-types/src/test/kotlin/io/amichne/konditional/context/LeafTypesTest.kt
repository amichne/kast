package io.amichne.konditional.context

import io.amichne.konditional.context.axis.Axis
import io.amichne.konditional.context.axis.AxisValue
import io.amichne.konditional.context.axis.axes
import io.amichne.konditional.core.id.StableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LeafTypesTest {
    @Test
    fun `version parsing normalizes missing components and compares structurally`() {
        assertEquals(Version.of(1, 2, 0), Version.parse("1.2").getOrThrow())
        assertTrue(Version.of(2, 0, 0) > Version.of(1, 9, 9))
    }

    @Test
    fun `stable ids are deterministic and lowercase normalized`() {
        val first = StableId.of("User-1")
        val second = StableId.of("user-1")

        assertEquals(first.hexId.id, second.hexId.id)
        assertFailsWith<IllegalArgumentException> { StableId.of("  ") }
    }

    @Test
    fun `axes group values by axis and return typed selections`() {
        val envAxis = Axis.of<TestEnvironment>()
        val values = axes(TestEnvironment.PROD, TestEnvironment.STAGE)

        assertEquals(setOf(TestEnvironment.PROD, TestEnvironment.STAGE), values[envAxis])
    }
}

private enum class TestEnvironment(
    override val id: String,
) : AxisValue<TestEnvironment> {
    STAGE("stage"),
    PROD("prod"),
}
