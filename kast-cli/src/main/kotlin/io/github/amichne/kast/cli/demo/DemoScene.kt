package io.github.amichne.kast.cli.demo

import kotlin.time.Duration

/**
 * Declarative description of a `kast demo` scene. A [DemoScript] is a flat,
 * ordered list of [DemoScene] nodes built through [demoScript]. The renderer
 * walks the tree once and produces an ANSI/markdown string; the DSL lets the
 * command flow stay declarative and lets tests assert on the tree before any
 * text is produced.
 */
internal data class DemoScript(
    val scenes: List<DemoScene>,
)

/**
 * A single scene in the demo. Sealed so the renderer exhausts every case and
 * new scene kinds cannot silently slip through.
 */
internal sealed interface DemoScene {
    /** Top or closing banner rendered as a boxed panel. */
    data class Panel(
        val title: String,
        val lines: List<PanelLine>,
    ) : DemoScene

    /** Section heading, e.g. `── Act 1 · text search baseline ──`. */
    data class SectionHeading(
        val title: String,
    ) : DemoScene

    /** One-line step progress indicator, e.g. `› Warming workspace daemon...`. */
    data class StepProgress(
        val message: String,
    ) : DemoScene

    /** Step outcome with elapsed wall-clock time. */
    data class StepOutcome(
        val message: String,
        val outcome: StepResult,
        val elapsed: Duration?,
    ) : DemoScene

    /** Indented body of a rendered step (e.g. resolved symbol, ref list). */
    data class StepBody(
        val lines: List<BodyLine>,
    ) : DemoScene

    /** Side-by-side comparison table. */
    data class ComparisonTable(
        val header: Triple<String, String, String>,
        val rows: List<Triple<String, String, String>>,
    ) : DemoScene

    /** Arbitrary blank line. */
    data object BlankLine : DemoScene
}

internal enum class StepResult { SUCCESS, FAILURE, INFO }

/**
 * A line inside a [DemoScene.Panel].
 *
 * Width is always measured from [text] (plain, no ANSI). When [prerendered] is
 * non-null the renderer emits that string verbatim instead of applying
 * [emphasis]; this lets callers (e.g. the walker) hand the renderer an
 * ANSI-styled composition while the renderer keeps ownership of padding and
 * truncation using the plain [text].
 */
internal data class PanelLine(
    val text: String,
    val emphasis: LineEmphasis = LineEmphasis.NORMAL,
    val prerendered: String? = null,
)

/** A line inside a [DemoScene.StepBody] — carries enough classification for the renderer to colour it. */
internal data class BodyLine(
    val text: String,
    val emphasis: LineEmphasis = LineEmphasis.NORMAL,
    val tag: BodyLineTag = BodyLineTag.NONE,
)

internal enum class LineEmphasis { NORMAL, DIM, STRONG, SUCCESS, WARN, ERROR }

/** Tag attached to individual body lines; used by the renderer to append trailing hints like `← comment`. */
internal enum class BodyLineTag {
    NONE,
    COMMENT,
    STRING,
    IMPORT,
    SUBSTRING,
    CORRECT,
}

/**
 * Entry point DSL. Returns a fully-built [DemoScript].
 *
 * ```
 * val script = demoScript {
 *     banner("kast demo") {
 *         line("semantic analysis vs text search")
 *         blank()
 *         line("Workspace  $workspaceRoot")
 *     }
 *     section("Act 1 · text search baseline")
 *     step("grep") {
 *         success(elapsed)
 *         body {
 *             line("grep found 128 matches", emphasis = LineEmphasis.STRONG)
 *         }
 *     }
 * }
 * ```
 */
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

    fun banner(title: String, block: PanelBuilder.() -> Unit) {
        val builder = PanelBuilder()
        builder.block()
        scenes += DemoScene.Panel(title = title, lines = builder.build())
    }

    fun panel(title: String, block: PanelBuilder.() -> Unit) = banner(title, block)

    fun section(title: String) {
        scenes += DemoScene.SectionHeading(title)
    }

    fun blank() {
        scenes += DemoScene.BlankLine
    }

    fun progress(message: String) {
        scenes += DemoScene.StepProgress(message)
    }

    fun step(message: String, block: StepBuilder.() -> Unit) {
        val builder = StepBuilder(message)
        builder.block()
        scenes += builder.outcomeScene()
        builder.bodyScene()?.let { scenes += it }
    }

    fun comparisonTable(block: ComparisonTableBuilder.() -> Unit) {
        val builder = ComparisonTableBuilder()
        builder.block()
        scenes += DemoScene.ComparisonTable(
            header = builder.headerOrDefault(),
            rows = builder.rows(),
        )
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
        lines += PanelLine("", LineEmphasis.NORMAL)
    }

    internal fun build(): List<PanelLine> = lines.toList()
}

@DemoDsl
internal class StepBuilder internal constructor(val message: String) {
    private var outcome: StepResult = StepResult.SUCCESS
    private var elapsed: Duration? = null
    private var body: List<BodyLine>? = null

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

    fun body(block: BodyBuilder.() -> Unit) {
        val builder = BodyBuilder()
        builder.block()
        body = builder.build()
    }

    internal fun outcomeScene(): DemoScene.StepOutcome =
        DemoScene.StepOutcome(message = message, outcome = outcome, elapsed = elapsed)

    internal fun bodyScene(): DemoScene.StepBody? = body?.let { DemoScene.StepBody(it) }
}

@DemoDsl
internal class BodyBuilder internal constructor() {
    private val lines = mutableListOf<BodyLine>()

    fun line(
        text: String,
        emphasis: LineEmphasis = LineEmphasis.NORMAL,
        tag: BodyLineTag = BodyLineTag.NONE,
    ) {
        lines += BodyLine(text = text, emphasis = emphasis, tag = tag)
    }

    fun blank() {
        lines += BodyLine("", LineEmphasis.NORMAL)
    }

    internal fun build(): List<BodyLine> = lines.toList()
}

@DemoDsl
internal class ComparisonTableBuilder internal constructor() {
    private var header: Triple<String, String, String>? = null
    private val rows = mutableListOf<Triple<String, String, String>>()

    fun header(metric: String, left: String, right: String) {
        header = Triple(metric, left, right)
    }

    fun row(metric: String, left: String, right: String) {
        rows += Triple(metric, left, right)
    }

    internal fun rows(): List<Triple<String, String, String>> = rows.toList()

    internal fun headerOrDefault(): Triple<String, String, String> =
        header ?: Triple("metric", "grep + sed", "kast")
}
