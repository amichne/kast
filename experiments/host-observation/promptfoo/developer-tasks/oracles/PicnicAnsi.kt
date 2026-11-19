package oracle

import com.jakewharton.picnic.TextAlignment.TopRight
import com.jakewharton.picnic.renderText
import com.jakewharton.picnic.table

private const val esc = "\u001B"
private const val red = "$esc[31;1;4m"
private const val bold = "$esc[1m"
private const val reset = "$esc[0m"
private const val face = "\uD83D\uDE03"

private fun expect(label: String, expected: String, actual: String) {
    check(expected.encodeToByteArray().contentEquals(actual.encodeToByteArray())) {
        "$label rendered different UTF-8 bytes: ${actual.encodeToByteArray().joinToString(",")}"
    }
}

fun main() {
    // Public rendering regression from Picnic PR #22, CellSizeTest.
    expect("SGR width and retention", "a\n${red}a$reset\n", table {
        row("a")
        row("${red}a$reset")
    }.renderText())

    // Exercise the adjacent escape and supplementary-code-point cases of the upstream TextTest
    // through the public renderer and adjacent cells.
    expect("adjacent SGR sequences", "$red${bold}a$reset!\nb?\n", table {
        row("$red${bold}a$reset", "!")
        row("b", "?")
    }.renderText())
    expect("plain supplementary Unicode", "$face X\nBBY\n", table {
        row(face, "X")
        row("BB", "Y")
    }.renderText())

    expect(
        "multiline right alignment and borders",
        listOf(
            "┌────┬──┐",
            "│head│id│",
            "├────┼──┤",
            "│   $red$face$reset│X │",
            "│ Aβ${red}Z$reset│  │",
            "└────┴──┘",
        ).joinToString("\n", postfix = "\n"),
        table {
            cellStyle { border = true }
            row("head", "id")
            row {
                cell("$red$face$reset\nAβ${red}Z$reset") { alignment = TopRight }
                cell("X")
            }
        }.renderText(),
    )
    println("PASS")
}
