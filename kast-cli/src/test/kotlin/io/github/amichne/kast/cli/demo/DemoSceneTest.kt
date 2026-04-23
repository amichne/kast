package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.cli.CliTextTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoSceneTest {
    @Test
    fun `DSL preserves scene order and types`() {
        val script = demoScript {
            panel("title") {
                line("hello")
                blank()
                line("world", emphasis = LineEmphasis.STRONG)
            }
            section("Act 1")
            step("walker›") { info() }
            blank()
        }

        val kinds = script.scenes.map { it::class.simpleName }
        assertEquals(
            listOf("Panel", "SectionHeading", "StepOutcome", "BlankLine"),
            kinds,
        )
    }

    @Test
    fun `panel lines are captured verbatim`() {
        val script = demoScript {
            panel("p") {
                line("one", emphasis = LineEmphasis.SUCCESS)
                blank()
                line("two")
            }
        }
        val panel = script.scenes.single() as DemoScene.Panel
        assertEquals("p", panel.title)
        assertEquals(listOf("one", "", "two"), panel.lines.map(PanelLine::text))
        assertEquals(LineEmphasis.SUCCESS, panel.lines.first().emphasis)
    }

    @Test
    fun `renderer emits the section heading and panel title in order`() {
        val script = demoScript {
            section("Act 1 · baseline")
            panel("demo target") {
                line("Symbol  Foo")
            }
        }
        val output = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false).render(script)
        assertTrue(output.contains("Act 1 · baseline"), output)
        assertTrue(output.contains("demo target"), output)
        assertTrue(output.contains("Symbol  Foo"), output)
        val actIdx = output.indexOf("Act 1")
        val panelIdx = output.indexOf("demo target")
        assertTrue(actIdx < panelIdx, "section before panel")
    }

    @Test
    fun `step with failure includes outcome icon in rendered text`() {
        val script = demoScript {
            step("references") { failure() }
        }
        val output = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false).render(script)
        assertTrue(output.contains("references"), output)
        assertTrue(output.contains("✕"), output)
    }

    @Test
    fun `panel lines longer than inner width are wrapped on whitespace`() {
        // 60 is below MIN_PANEL_WIDTH (58) plus border; use 80 so the panel
        // actually respects the requested terminal width.
        val width = 80
        val renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false, width = width)
        val long = "grep only sees text, so it mixes real usages with imports, comments, string literals, and substring collisions that have nothing to do with the symbol."
        val script = demoScript { panel("why the semantic pass wins") { line(long) } }

        val lines = renderer.render(script).trimEnd().lines()
        val bodyLines = lines.filter { it.startsWith("│ ") && !it.contains("why the semantic pass wins") }
        assertTrue(bodyLines.size >= 2, "expected wrapped output across multiple rows, got:\n${lines.joinToString("\n")}")
        // Every body line must fit the configured terminal width.
        bodyLines.forEach { row ->
            assertTrue(row.length <= width, "row overflows $width cols: '$row' (${row.length})")
            assertTrue(row.endsWith(" │"), "row must end with right border: '$row'")
        }
    }

    @Test
    fun `panel tokens that cannot fit a single row are truncated with an ellipsis`() {
        val width = 60
        val renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false, width = width)
        val giant = "io.github.amichne.kast.backend.standalone.CliServiceSymbolGraphThatIsLongerThanAnyBodyCellWouldEverTolerateInRealLife"
        val script = demoScript { panel("p") { line(giant) } }

        val lines = renderer.render(script).trimEnd().lines()
        val bodyLines = lines.filter { it.startsWith("│ ") && !it.contains("│ p ") }
        assertTrue(bodyLines.any { it.contains("…") }, "expected unicode ellipsis in:\n${lines.joinToString("\n")}")
        bodyLines.forEach { assertTrue(it.length <= width, "row overflows $width cols: '$it' (${it.length})") }
    }

    @Test
    fun `wrapForWidth keeps tokens intact when they fit and breaks oversized tokens with ellipsis`() {
        val renderer = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false, width = 80)
        assertEquals(listOf("hello world"), renderer.wrapForWidth("hello world", 20))
        assertEquals(listOf("short one", "bar baz"), renderer.wrapForWidth("short one bar baz", 10))
        val chunks = renderer.wrapForWidth("longertokenthanwidthsoitmustbreak", 10)
        assertEquals(listOf("longertok…"), chunks)
        assertEquals(listOf(""), renderer.wrapForWidth("", 10))
    }
}
