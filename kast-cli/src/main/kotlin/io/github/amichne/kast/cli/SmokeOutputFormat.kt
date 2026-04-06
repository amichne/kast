package io.github.amichne.kast.cli

internal enum class SmokeOutputFormat(
    val cliValue: String,
) {
    JSON("json"),
    MARKDOWN("markdown"),
    ;

    companion object {
        fun fromCliValue(value: String): SmokeOutputFormat? = entries.firstOrNull { format ->
            format.cliValue == value.trim().lowercase()
        }
    }
}
