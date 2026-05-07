package io.amichne.konditional.rules.versions

import io.amichne.konditional.context.Version
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionRangeTest {
    @Test
    fun `fully bound ranges include both edges`() {
        val range = FullyBound(Version.of(1, 0, 0), Version.of(2, 0, 0))

        assertTrue(range.contains(Version.of(1, 0, 0)))
        assertTrue(range.contains(Version.of(2, 0, 0)))
        assertFalse(range.contains(Version.of(2, 0, 1)))
    }

    @Test
    fun `left right and unbounded ranges match their documented semantics`() {
        assertTrue(LeftBound(Version.of(2, 0, 0)).contains(Version.of(2, 1, 0)))
        assertFalse(LeftBound(Version.of(2, 0, 0)).contains(Version.of(1, 9, 9)))

        assertTrue(RightBound(Version.of(2, 0, 0)).contains(Version.of(1, 9, 9)))
        assertFalse(RightBound(Version.of(2, 0, 0)).contains(Version.of(2, 1, 0)))

        assertTrue(Unbounded.contains(Version.of(99, 99, 99)))
    }
}
