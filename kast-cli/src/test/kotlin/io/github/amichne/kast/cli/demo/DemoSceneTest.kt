package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.cli.CliTextTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoSceneTest {
    @Test
    fun `DSL preserves scene order and types`() {
        val script = demoScript {
            banner("title") {
                line("hello")
                blank()
                line("world", emphasis = LineEmphasis.STRONG)
            }
            section("Act 1")
            progress("running...")
            step("resolve") {
                success()
                body { line("ok") }
            }
            comparisonTable {
                header("metric", "grep", "kast")
                row("m", "g", "k")
            }
            blank()
        }

        val kinds = script.scenes.map { it::class.simpleName }
        assertEquals(
            listOf("Panel", "SectionHeading", "StepProgress", "StepOutcome", "StepBody", "ComparisonTable", "BlankLine"),
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
    fun `renderer emits the section heading, panel title, and table header in order`() {
        val script = demoScript {
            section("Act 1 · baseline")
            panel("demo target") {
                line("Symbol  Foo")
            }
            comparisonTable {
                header("metric", "grep + sed", "kast")
                row("Matches found", "5", "2")
            }
        }
        val output = DemoRenderer(CliTextTheme.ansi(), ansiEnabled = false).render(script)
        assertTrue(output.contains("Act 1 · baseline"), output)
        assertTrue(output.contains("demo target"), output)
        assertTrue(output.contains("grep + sed"), output)
        assertTrue(output.contains("Symbol  Foo"), output)
        val actIdx = output.indexOf("Act 1")
        val panelIdx = output.indexOf("demo target")
        val tableIdx = output.indexOf("grep + sed")
        assertTrue(actIdx < panelIdx, "section before panel")
        assertTrue(panelIdx < tableIdx, "panel before table")
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
}
