package io.github.amichne.kast.cli.demo

import kotlin.time.Duration

/**
 * Minimal scene DSL used by the interactive walker cards and prompts.
 *
 * The shipped `kast demo` flow now renders through the Kotter shell; this
 * small model survives only to keep the walker's boxed cards easy to test and
 * host-free.
 */
internal data class DemoScript(
    val scenes: List<DemoScene>,
)

internal sealed interface DemoScene {
    data class Panel(
        val title: String,
        val lines: List<PanelLine>,
    ) : DemoScene

    data class SectionHeading(
        val title: String,
    ) : DemoScene

    data class StepOutcome(
        val message: String,
        val outcome: StepResult,
        val elapsed: Duration?,
    ) : DemoScene

    data object BlankLine : DemoScene
}

internal enum class StepResult { SUCCESS, FAILURE, INFO }

/**
 * A line inside a [DemoScene.Panel].
 *
 * Width is always measured from [text] (plain, no ANSI). When [prerendered] is
 * non-null the renderer emits that string verbatim instead of applying
 * [emphasis]; this lets callers hand the renderer an ANSI-styled composition
 * while the renderer keeps ownership of padding and truncation using the plain
 * [text].
 */
internal data class PanelLine(
    val text: String,
    val emphasis: LineEmphasis = LineEmphasis.NORMAL,
    val prerendered: String? = null,
)

internal enum class LineEmphasis { NORMAL, DIM, STRONG, SUCCESS, WARN, ERROR }

internal fun demoScript(block: DemoScriptBuilder.() -> Unit): DemoScript {
    val builder = DemoScriptBuilder()
    builder.block()
    return DemoScript(builder.build())
}

@DslMarker
internal annotation class DemoDsl

@DemoDsl
internal class DemoScriptBuilder internal constructor() {
    private val scenes = mutableListOf<DemoScene>()

    fun panel(title: String, block: PanelBuilder.() -> Unit) {
        val builder = PanelBuilder()
        builder.block()
        scenes += DemoScene.Panel(title = title, lines = builder.build())
    }

    fun banner(title: String, block: PanelBuilder.() -> Unit) = panel(title, block)

    fun section(title: String) {
        scenes += DemoScene.SectionHeading(title)
    }

    fun blank() {
        scenes += DemoScene.BlankLine
    }

    fun step(message: String, block: StepBuilder.() -> Unit) {
        val builder = StepBuilder(message)
        builder.block()
        scenes += builder.build()
    }

    fun build(): List<DemoScene> = scenes.toList()
}

@DemoDsl
internal class PanelBuilder internal constructor() {
    private val lines = mutableListOf<PanelLine>()

    fun line(text: String, emphasis: LineEmphasis = LineEmphasis.NORMAL) {
        lines += PanelLine(text, emphasis)
    }

    /**
     * A panel line whose styling is owned by the caller. [plain] is used for
     * width measurement and padding; [rendered] is emitted verbatim. The
     * caller is responsible for pre-truncating [plain] / [rendered] to fit
     * the panel's inner width (use [DemoRenderer.truncate] / [DemoRenderer.truncateLeft]).
     */
    fun styledLine(plain: String, rendered: String) {
        lines += PanelLine(text = plain, prerendered = rendered)
    }

    fun blank() {
        lines += PanelLine("")
    }

    internal fun build(): List<PanelLine> = lines.toList()
}

@DemoDsl
internal class StepBuilder internal constructor(
    private val message: String,
) {
    private var outcome: StepResult = StepResult.SUCCESS
    private var elapsed: Duration? = null

    fun success(elapsed: Duration? = null) {
        outcome = StepResult.SUCCESS
        this.elapsed = elapsed
    }

    fun failure(elapsed: Duration? = null) {
        outcome = StepResult.FAILURE
        this.elapsed = elapsed
    }

    fun info(elapsed: Duration? = null) {
        outcome = StepResult.INFO
        this.elapsed = elapsed
    }

    internal fun build(): DemoScene.StepOutcome =
        DemoScene.StepOutcome(message = message, outcome = outcome, elapsed = elapsed)
}
