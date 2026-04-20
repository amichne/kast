
package io.github.amichne.kast.api.docs

internal class IndentedWriter(private val sb: StringBuilder = StringBuilder()) {
    private var depth: Int = 0
    private val indent: String get() = "    ".repeat(depth)

    fun line(text: String = "") {
        if (text.isEmpty()) sb.appendLine() else sb.appendLine("$indent$text")
    }

    fun lines(text: String) {
        for (l in text.lines()) line(l)
    }

    inline fun indented(block: IndentedWriter.() -> Unit) {
        depth++
        block()
        depth--
    }

    inline fun tab(title: String, block: IndentedWriter.() -> Unit) {
        line("=== \"$title\"")
        line()
        indented(block)
    }

    inline fun details(type: String, title: String, block: IndentedWriter.() -> Unit) {
        line("??? $type \"$title\"")
        line()
        indented(block)
    }

    inline fun admonition(type: String, title: String, block: IndentedWriter.() -> Unit) {
        line("!!! $type \"$title\"")
        line()
        indented(block)
    }

    override fun toString(): String = sb.toString()
}
