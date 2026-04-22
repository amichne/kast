package io.github.amichne.kast.demo

import com.varabyte.kotter.foundation.text.Color
import kotlin.math.abs

/**
 * Stable color palette for module labels, shared across Act 2 and Act 3
 * to maintain visual continuity.
 */
internal object ModulePalette {
    private val COLORS = listOf(
        Color.CYAN,
        Color.YELLOW,
        Color.GREEN,
        Color.MAGENTA,
        Color.BLUE,
        Color.RED,
    )

    fun colorFor(moduleName: String): Color {
        val index = (moduleName.hashCode().let { if (it == Int.MIN_VALUE) 0 else abs(it) }) % COLORS.size
        return COLORS[index]
    }
}
