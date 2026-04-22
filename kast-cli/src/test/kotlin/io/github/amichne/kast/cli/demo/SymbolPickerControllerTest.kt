package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SymbolPickerControllerTest {

    // ── helpers ──────────────────────────────────────────────────────

    private fun sym(
        fqName: String,
        kind: SymbolKind = SymbolKind.CLASS,
        filePath: String = "/src/main/kotlin/Foo.kt",
    ): Symbol = Symbol(
        fqName = fqName,
        kind = kind,
        location = Location(
            filePath = filePath,
            startOffset = 0,
            endOffset = 10,
            startLine = 1,
            startColumn = 1,
            preview = fqName.substringAfterLast('.'),
        ),
    )

    private fun controller(
        allSymbols: List<Symbol> = emptyList(),
        verbose: Boolean = false,
        minSearchChars: Int = 3,
    ): SymbolPickerController = SymbolPickerController(
        verbose = verbose,
        minSearchChars = minSearchChars,
    ).also { if (allSymbols.isNotEmpty()) it.onSymbolsLoaded(allSymbols) }

    // ── search text ─────────────────────────────────────────────────

    @Nested
    inner class SearchText {
        @Test
        fun `typing characters appends to search text`() {
            val ctrl = controller()
            ctrl.onChar('E')
            ctrl.onChar('r')
            ctrl.onChar('r')
            assertEquals("Err", ctrl.state.searchText)
        }

        @Test
        fun `backspace removes last character`() {
            val ctrl = controller()
            ctrl.onChar('A')
            ctrl.onChar('B')
            ctrl.onBackspace()
            assertEquals("A", ctrl.state.searchText)
        }

        @Test
        fun `backspace on empty text is a no-op`() {
            val ctrl = controller()
            ctrl.onBackspace()
            assertEquals("", ctrl.state.searchText)
        }
    }

    // ── min-chars threshold ─────────────────────────────────────────

    @Nested
    inner class MinCharsThreshold {
        @Test
        fun `below min chars shows no results and needs-more-chars flag`() {
            val symbols = listOf(sym("com.example.ErrorMessage"))
            val ctrl = controller(allSymbols = symbols, minSearchChars = 3)
            ctrl.onChar('E')
            ctrl.onChar('r')
            assertTrue(ctrl.state.needsMoreChars)
            assertTrue(ctrl.state.filteredResults.isEmpty())
        }

        @Test
        fun `at min chars starts filtering`() {
            val symbols = listOf(sym("com.example.ErrorMessage"))
            val ctrl = controller(allSymbols = symbols, minSearchChars = 3)
            ctrl.onChar('E')
            ctrl.onChar('r')
            ctrl.onChar('r')
            assertFalse(ctrl.state.needsMoreChars)
            assertEquals(1, ctrl.state.filteredResults.size)
        }
    }

    // ── kind filters ────────────────────────────────────────────────

    @Nested
    inner class KindFilters {
        @Test
        fun `all kinds enabled by default`() {
            val ctrl = controller()
            SymbolPickerController.FILTERABLE_KINDS.forEach { kind ->
                assertTrue(ctrl.state.kindFilters.getValue(kind))
            }
        }

        @Test
        fun `tab cycles focused kind chip`() {
            val ctrl = controller()
            assertEquals(0, ctrl.state.focusedKindIndex)
            ctrl.onTab()
            assertEquals(1, ctrl.state.focusedKindIndex)
            ctrl.onTab()
            assertEquals(2, ctrl.state.focusedKindIndex)
        }

        @Test
        fun `tab wraps around at end`() {
            val ctrl = controller()
            val kindCount = SymbolPickerController.FILTERABLE_KINDS.size
            repeat(kindCount) { ctrl.onTab() }
            assertEquals(0, ctrl.state.focusedKindIndex)
        }

        @Test
        fun `space toggles focused kind chip off and on`() {
            val ctrl = controller()
            val firstKind = SymbolPickerController.FILTERABLE_KINDS.first()
            assertTrue(ctrl.state.kindFilters.getValue(firstKind))
            ctrl.onSpace()
            assertFalse(ctrl.state.kindFilters.getValue(firstKind))
            ctrl.onSpace()
            assertTrue(ctrl.state.kindFilters.getValue(firstKind))
        }

        @Test
        fun `disabling a kind filters results`() {
            val symbols = listOf(
                sym("com.example.ErrorMessage", kind = SymbolKind.CLASS),
                sym("com.example.handleError", kind = SymbolKind.FUNCTION),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('E')

            assertEquals(2, ctrl.state.filteredResults.size)

            // Focus on FUNCTION kind and toggle it off
            val funcIndex = SymbolPickerController.FILTERABLE_KINDS.indexOf(SymbolKind.FUNCTION)
            repeat(funcIndex) { ctrl.onTab() }
            ctrl.onSpace()

            assertEquals(1, ctrl.state.filteredResults.size)
            assertEquals(SymbolKind.CLASS, ctrl.state.filteredResults.first().kind)
        }
    }

    // ── build-dir exclusion ─────────────────────────────────────────

    @Nested
    inner class BuildDirExclusion {
        @Test
        fun `excludes symbols from build directories`() {
            val symbols = listOf(
                sym("com.example.Good", filePath = "/src/main/kotlin/Good.kt"),
                sym("com.example.Bad", filePath = "/project/build/generated/Bad.kt"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('o') // matches "Good" and might match "Bad" but Bad is excluded

            // The build-dir symbol should be filtered out
            val paths = ctrl.state.filteredResults.map { it.location.filePath }
            assertFalse(paths.any { "/build/" in it })
        }

        @Test
        fun `excludes symbols from buildSrc build`() {
            val symbols = listOf(
                sym("com.example.Plugin", filePath = "/project/buildSrc/build/classes/Plugin.kt"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('P')
            assertTrue(ctrl.state.filteredResults.isEmpty())
        }

        @Test
        fun `excludes symbols from gradle cache`() {
            val symbols = listOf(
                sym("com.example.Cached", filePath = "/home/.gradle/caches/Cached.kt"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('C')
            assertTrue(ctrl.state.filteredResults.isEmpty())
        }

        @Test
        fun `keeps source directory symbols`() {
            val symbols = listOf(
                sym("com.example.Good", filePath = "/project/src/main/kotlin/Good.kt"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('G')
            assertEquals(1, ctrl.state.filteredResults.size)
        }
    }

    // ── result navigation ───────────────────────────────────────────

    @Nested
    inner class ResultNavigation {
        @Test
        fun `up and down navigate selected index`() {
            val symbols = listOf(
                sym("com.example.Alpha"),
                sym("com.example.Aba"),
                sym("com.example.Abc"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('A')
            assertEquals(0, ctrl.state.selectedIndex)

            ctrl.onDown()
            assertEquals(1, ctrl.state.selectedIndex)
            ctrl.onDown()
            assertEquals(2, ctrl.state.selectedIndex)
        }

        @Test
        fun `down clamps at last result`() {
            val symbols = listOf(sym("com.example.Only"))
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('O')
            ctrl.onDown()
            ctrl.onDown()
            assertEquals(0, ctrl.state.selectedIndex)
        }

        @Test
        fun `up clamps at zero`() {
            val symbols = listOf(sym("com.example.Only"))
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('O')
            ctrl.onUp()
            assertEquals(0, ctrl.state.selectedIndex)
        }

        @Test
        fun `selected index resets when search text changes`() {
            val symbols = listOf(
                sym("com.example.Alpha"),
                sym("com.example.Aba"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('A')
            ctrl.onDown()
            assertEquals(1, ctrl.state.selectedIndex)

            ctrl.onChar('l') // "Al" — narrows results
            assertEquals(0, ctrl.state.selectedIndex)
        }

        @Test
        fun `selected index clamps when results shrink`() {
            val symbols = listOf(
                sym("com.example.Alpha"),
                sym("com.example.Aba"),
                sym("com.example.Abc"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('A')
            ctrl.onDown()
            ctrl.onDown()
            assertEquals(2, ctrl.state.selectedIndex)

            ctrl.onChar('l') // "Al" — only Alpha matches
            assertEquals(0, ctrl.state.selectedIndex)
        }
    }

    // ── selection ───────────────────────────────────────────────────

    @Nested
    inner class Selection {
        @Test
        fun `enter returns selected symbol`() {
            val symbols = listOf(
                sym("com.example.First"),
                sym("com.example.Fox"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('F')
            ctrl.onDown()
            val result = ctrl.onEnter()
            assertEquals("com.example.Fox", result?.fqName)
        }

        @Test
        fun `enter with no results returns null`() {
            val ctrl = controller(minSearchChars = 1)
            ctrl.onChar('Z')
            assertNull(ctrl.onEnter())
        }

        @Test
        fun `esc returns null for quit`() {
            val ctrl = controller()
            assertNull(ctrl.onEsc())
        }
    }

    // ── display names ───────────────────────────────────────────────

    @Nested
    inner class DisplayNames {
        @Test
        fun `simple names used by default when unique`() {
            val symbols = listOf(
                sym("com.example.ErrorMessage"),
                sym("com.example.ErrorHandler"),
            )
            val ctrl = controller(allSymbols = symbols, verbose = false, minSearchChars = 1)
            ctrl.onChar('E')
            val names = ctrl.state.displayRows.map { it.displayName }
            assertEquals(listOf("ErrorMessage", "ErrorHandler"), names)
        }

        @Test
        fun `colliding simple names get parent package prefix`() {
            val symbols = listOf(
                sym("com.response.ErrorMessage"),
                sym("com.api.ErrorMessage"),
            )
            val ctrl = controller(allSymbols = symbols, verbose = false, minSearchChars = 1)
            ctrl.onChar('E')
            val names = ctrl.state.displayRows.map { it.displayName }.sorted()
            assertEquals(listOf("api.ErrorMessage", "response.ErrorMessage"), names)
        }

        @Test
        fun `verbose mode shows full fqn`() {
            val symbols = listOf(sym("com.example.deep.ErrorMessage"))
            val ctrl = controller(allSymbols = symbols, verbose = true, minSearchChars = 1)
            ctrl.onChar('E')
            val names = ctrl.state.displayRows.map { it.displayName }
            assertEquals(listOf("com.example.deep.ErrorMessage"), names)
        }

        @Test
        fun `display row includes kind label`() {
            val symbols = listOf(sym("com.example.Handler", kind = SymbolKind.INTERFACE))
            val ctrl = controller(allSymbols = symbols, verbose = false, minSearchChars = 1)
            ctrl.onChar('H')
            assertEquals("interface", ctrl.state.displayRows.first().kindLabel)
        }

        @Test
        fun `display row includes context hint from package`() {
            val symbols = listOf(sym("com.example.service.Handler"))
            val ctrl = controller(allSymbols = symbols, verbose = false, minSearchChars = 1)
            ctrl.onChar('H')
            assertEquals("service", ctrl.state.displayRows.first().contextHint)
        }
    }

    // ── case sensitivity ────────────────────────────────────────────

    @Nested
    inner class CaseSensitivity {
        @Test
        fun `search is case-sensitive`() {
            val symbols = listOf(
                sym("com.example.ErrorMessage"),
                sym("com.example.errorMapper"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('E')
            assertEquals(1, ctrl.state.filteredResults.size)
            assertEquals("com.example.ErrorMessage", ctrl.state.filteredResults.first().fqName)
        }

        @Test
        fun `lowercase search finds lowercase-starting symbols`() {
            val symbols = listOf(
                sym("com.example.Alpha"),
                sym("com.example.alpha"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('a')
            // 'a' matches 'alpha' (contains 'a') but NOT 'Alpha' (starts with uppercase 'A')
            // Actually, 'Alpha' also contains 'a' in 'lpha'. Let's use a clearer example.
            val names = ctrl.state.filteredResults.map { it.fqName.substringAfterLast('.') }
            // Both contain lowercase 'a', so both match — case-sensitive substring match
            assertEquals(2, ctrl.state.filteredResults.size)
        }

        @Test
        fun `uppercase char does not match lowercase-only names`() {
            val symbols = listOf(
                sym("com.example.zoo"),
                sym("com.example.Zoo"),
            )
            val ctrl = controller(allSymbols = symbols, minSearchChars = 1)
            ctrl.onChar('Z')
            // 'Z' matches 'Zoo' but not 'zoo' (case-sensitive)
            assertEquals(1, ctrl.state.filteredResults.size)
            assertEquals("com.example.Zoo", ctrl.state.filteredResults.first().fqName)
        }
    }
}
